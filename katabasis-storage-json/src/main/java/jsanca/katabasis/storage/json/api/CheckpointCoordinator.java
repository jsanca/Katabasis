package jsanca.katabasis.storage.json.api;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadPausedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.DownloadStatus;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Event-driven checkpoint coordinator that persists download progress at regular
 * intervals and on significant lifecycle transitions.
 *
 * <h2>Triggers</h2>
 * <ul>
 *   <li><strong>Periodic:</strong> all pending snapshots are flushed every
 *       {@code checkpointInterval}.</li>
 *   <li><strong>Immediate:</strong> on {@link DownloadPausedEvent}, the snapshot is
 *       persisted synchronously before the event handler returns.</li>
 * </ul>
 *
 * <h2>Terminal state handling</h2>
 * On {@link DownloadCompletedEvent} or {@link DownloadCancelledEvent} the stored
 * snapshot is deleted. On {@link DownloadFailedEvent} the snapshot is retained
 * so the user can inspect the failure and decide whether to retry.
 *
 * <h2>Coalescing</h2>
 * Only the most recent snapshot for each download ID is tracked. Rapid progress
 * events do not accumulate; only the last value reaches the store on each flush.
 *
 * <h2>Limitations at this scope</h2>
 * Snapshots are built from public event data only. Segment boundaries and ETag
 * are not populated here — those require access to internal download state
 * (planned for roadmap phase 0.2).
 *
 * <h2>Multi-process sharing</h2>
 * Not supported. Use separate storage directories per process.
 */
public final class CheckpointCoordinator implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CheckpointCoordinator.class.getName());

    private final DownloadStateStore store;
    private final DownloadManager manager;
    private final Consumer<DownloadEvent> listener;
    private final ConcurrentHashMap<String, DownloadSnapshot> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> flushTask;

    private CheckpointCoordinator(
            final DownloadManager manager,
            final DownloadStateStore store,
            final Duration checkpointInterval) {
        this.store   = store;
        this.manager = manager;
        this.listener = this::onEvent;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "checkpoint-coordinator");
            t.setDaemon(true);
            return t;
        });

        final long millis = checkpointInterval.toMillis();
        this.flushTask = scheduler.scheduleAtFixedRate(
                this::flushPending, millis, millis, TimeUnit.MILLISECONDS);

        manager.addListener(listener);
    }

    /**
     * Creates and starts a coordinator that listens to {@code manager} and writes
     * checkpoints to {@code store} at the given {@code checkpointInterval}.
     *
     * @param manager            the download manager to observe
     * @param store              the store to persist snapshots to
     * @param checkpointInterval flush interval; must be positive
     * @return a started coordinator; call {@link #close()} to stop it
     */
    public static CheckpointCoordinator create(
            final DownloadManager manager,
            final DownloadStateStore store,
            final Duration checkpointInterval) {
        Objects.requireNonNull(manager,            "manager must not be null");
        Objects.requireNonNull(store,              "store must not be null");
        Objects.requireNonNull(checkpointInterval, "checkpointInterval must not be null");
        if (checkpointInterval.isNegative() || checkpointInterval.isZero()) {
            throw new IllegalArgumentException(
                    "checkpointInterval must be positive: " + checkpointInterval);
        }
        return new CheckpointCoordinator(manager, store, checkpointInterval);
    }

    /**
     * Deregisters the event listener (stops new events from entering), cancels the
     * periodic flush task, shuts down the scheduler and awaits its termination (up to
     * 30 seconds), then performs a final flush on the calling thread to persist any
     * remaining pending snapshots.
     */
    @Override
    public void close() {
        manager.removeListener(listener);
        flushTask.cancel(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warning("checkpoint-coordinator scheduler did not terminate within 30 s");
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushPending();
    }

    // ── event handling ────────────────────────────────────────────────────────

    private void onEvent(final DownloadEvent event) {
        if (event instanceof DownloadStartedEvent e) {
            handleStarted(e);
        } else if (event instanceof DownloadProgressEvent e) {
            handleProgress(e);
        } else if (event instanceof DownloadPausedEvent e) {
            handlePaused(e);
        } else if (event instanceof DownloadCompletedEvent e) {
            handleCompleted(e);
        } else if (event instanceof DownloadCancelledEvent e) {
            handleCancelled(e);
        } else if (event instanceof DownloadFailedEvent e) {
            handleFailed(e);
        }
    }

    private void handleStarted(final DownloadStartedEvent e) {
        final DownloadSnapshot snap = build(
                e.info(), DownloadStatus.IN_PROGRESS,
                0L, -1L, e.occurredAt(), e.occurredAt());
        pending.put(snap.downloadId(), snap);
    }

    private void handleProgress(final DownloadProgressEvent e) {
        pending.compute(e.info().downloadId(), (id, existing) ->
                build(e.info(), DownloadStatus.IN_PROGRESS,
                        e.downloadedBytes(), e.totalBytes(),
                        existing != null ? existing.createdAt() : e.occurredAt(),
                        e.occurredAt()));
    }

    private void handlePaused(final DownloadPausedEvent e) {
        final DownloadSnapshot snap = build(
                e.info(), DownloadStatus.PAUSED,
                e.downloadedBytes(), e.totalBytes(),
                createdAtOrNow(e.info().downloadId(), e.occurredAt()),
                e.occurredAt());
        pending.put(snap.downloadId(), snap);
        saveImmediately(snap);
    }

    private void handleCompleted(final DownloadCompletedEvent e) {
        pending.compute(e.info().downloadId(), (k, s) -> {
            deleteQuietly(k);
            return null;
        });
    }

    private void handleCancelled(final DownloadCancelledEvent e) {
        // DownloadCancelledEvent uses downloadInfo() not info()
        pending.compute(e.downloadInfo().downloadId(), (k, s) -> {
            deleteQuietly(k);
            return null;
        });
    }

    private void handleFailed(final DownloadFailedEvent e) {
        pending.compute(e.info().downloadId(), (k, existing) -> {
            final DownloadSnapshot failedSnap;
            if (existing != null) {
                failedSnap = new DownloadSnapshot(
                        existing.downloadId(), existing.sourceUri(), existing.targetPath(),
                        existing.partialPath(), existing.etag(), existing.expectedTotalBytes(),
                        existing.confirmedBytes(), DownloadStatus.FAILED,
                        existing.segments(), existing.createdAt(), Instant.now());
            } else {
                // Failed before any Started/Progress event was seen; build from event info.
                failedSnap = build(e.info(), DownloadStatus.FAILED,
                        0L, -1L, e.occurredAt(), e.occurredAt());
            }
            saveQuietly(failedSnap);
            return null;
        });
    }

    // ── flush ─────────────────────────────────────────────────────────────────

    private void flushPending() {
        pending.forEach((id, snap) ->
                pending.computeIfPresent(id, (k, s) -> {
                    saveQuietly(s);
                    return s;
                }));
    }

    private void saveImmediately(final DownloadSnapshot snap) {
        saveQuietly(snap);
    }

    private void saveQuietly(final DownloadSnapshot snap) {
        try {
            store.save(snap);
        } catch (final Exception e) {
            log.warning("Failed to checkpoint " + snap.downloadId() + ": " + e.getMessage());
        }
    }

    private void deleteQuietly(final String downloadId) {
        try {
            store.delete(downloadId);
        } catch (final Exception e) {
            log.warning("Failed to delete checkpoint for " + downloadId + ": " + e.getMessage());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DownloadSnapshot build(
            final DownloadInfo info,
            final DownloadStatus status,
            final long confirmedBytes,
            final long expectedTotalBytes,
            final Instant createdAt,
            final Instant checkpointAt) {
        return new DownloadSnapshot(
                info.downloadId(),
                info.sourceUri(),
                info.targetPath(),
                null,
                null,
                expectedTotalBytes,
                confirmedBytes,
                status,
                List.of(),
                createdAt,
                checkpointAt);
    }

    private Instant createdAtOrNow(final String downloadId, final Instant fallback) {
        final DownloadSnapshot existing = pending.get(downloadId);
        return existing != null ? existing.createdAt() : fallback;
    }
}
