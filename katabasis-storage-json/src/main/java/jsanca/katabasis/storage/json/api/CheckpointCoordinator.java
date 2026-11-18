package jsanca.katabasis.storage.json.api;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCheckpointFailedEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadPausedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import jsanca.katabasis.core.api.manager.CheckpointSource;
import jsanca.katabasis.core.api.manager.CheckpointSourceProvider;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.DownloadStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
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
 * <h2>CheckpointSource pull</h2>
 * When a {@link CheckpointSourceProvider} is supplied, the coordinator calls
 * {@link CheckpointSource#buildSnapshot(Instant)} at flush time to obtain an
 * internally consistent snapshot that includes segment boundaries and ETag. If
 * no source is available for a given download (not yet registered, or the download
 * has already completed), the most recently event-derived snapshot is used instead.
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
 * <h2>Observable failures</h2>
 * Save or delete errors are reported via the {@code failureConsumer} supplied at
 * construction as {@link DownloadCheckpointFailedEvent} instances. The consumer
 * is called on the thread that encountered the error (scheduler thread or caller).
 *
 * <h2>Multi-process sharing</h2>
 * Not supported. Use separate storage directories per process.
 */
public final class CheckpointCoordinator implements AutoCloseable {

    private static final Logger log = Logger.getLogger(CheckpointCoordinator.class.getName());

    private final DownloadStateStore store;
    private final DownloadManager manager;
    private final CheckpointSourceProvider sourceProvider;
    private final Consumer<DownloadEvent> failureConsumer;
    private final Consumer<DownloadEvent> listener;
    private final ConcurrentHashMap<String, DownloadSnapshot> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> flushTask;

    private CheckpointCoordinator(
            final DownloadManager manager,
            final CheckpointSourceProvider sourceProvider,
            final DownloadStateStore store,
            final Duration checkpointInterval,
            final Consumer<DownloadEvent> failureConsumer) {
        this.store   = store;
        this.manager = manager;
        this.sourceProvider = sourceProvider;
        this.failureConsumer = failureConsumer;
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
     * <p>This overload uses a no-op source provider (snapshots are built from events only)
     * and a no-op failure consumer. It is provided for backward compatibility with existing
     * callers that have not yet integrated {@link CheckpointSourceProvider}.
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
        validateInterval(checkpointInterval);
        return new CheckpointCoordinator(manager, id -> Optional.empty(), store,
                checkpointInterval, event -> {});
    }

    /**
     * Creates and starts a coordinator with full source-provider and failure-consumer
     * integration.
     *
     * <p>At flush time the coordinator calls
     * {@link CheckpointSource#buildSnapshot(Instant)} via the provider to obtain an
     * internally consistent snapshot (segments, ETag, confirmed bytes). When no source
     * is available the event-derived snapshot is used as fallback.
     *
     * <p>Persistence errors are reported to {@code failureConsumer} as
     * {@link DownloadCheckpointFailedEvent} instances rather than being silently swallowed.
     *
     * @param manager            the download manager to observe
     * @param sourceProvider     provider of per-download checkpoint sources
     * @param store              the store to persist snapshots to
     * @param checkpointInterval flush interval; must be positive
     * @param failureConsumer    called when a save or delete operation fails
     * @return a started coordinator; call {@link #close()} to stop it
     */
    public static CheckpointCoordinator create(
            final DownloadManager manager,
            final CheckpointSourceProvider sourceProvider,
            final DownloadStateStore store,
            final Duration checkpointInterval,
            final Consumer<DownloadEvent> failureConsumer) {
        Objects.requireNonNull(manager,            "manager must not be null");
        Objects.requireNonNull(sourceProvider,     "sourceProvider must not be null");
        Objects.requireNonNull(store,              "store must not be null");
        Objects.requireNonNull(checkpointInterval, "checkpointInterval must not be null");
        Objects.requireNonNull(failureConsumer,    "failureConsumer must not be null");
        validateInterval(checkpointInterval);
        return new CheckpointCoordinator(manager, sourceProvider, store,
                checkpointInterval, failureConsumer);
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
        final String id = e.info().downloadId();
        final Instant createdAt = createdAtOrNow(id, e.occurredAt());

        final Optional<CheckpointSource> srcOpt = sourceProvider.findSource(id);
        final DownloadSnapshot snap;
        if (srcOpt.isPresent()) {
            final DownloadSnapshot fresh = srcOpt.get().buildSnapshot(e.occurredAt());
            snap = withStatus(fresh, DownloadStatus.PAUSED, e.occurredAt());
        } else {
            snap = build(e.info(), DownloadStatus.PAUSED,
                    e.downloadedBytes(), e.totalBytes(), createdAt, e.occurredAt());
        }
        pending.put(id, snap);
        saveWithObservability(snap);
    }

    private void handleCompleted(final DownloadCompletedEvent e) {
        pending.compute(e.info().downloadId(), (k, s) -> {
            deleteWithObservability(k, e.info());
            return null;
        });
    }

    private void handleCancelled(final DownloadCancelledEvent e) {
        // DownloadCancelledEvent uses downloadInfo() not info()
        pending.compute(e.downloadInfo().downloadId(), (k, s) -> {
            deleteWithObservability(k, e.downloadInfo());
            return null;
        });
    }

    private void handleFailed(final DownloadFailedEvent e) {
        pending.compute(e.info().downloadId(), (k, existing) -> {
            final DownloadSnapshot base;
            final Optional<CheckpointSource> srcOpt = sourceProvider.findSource(k);
            if (srcOpt.isPresent()) {
                base = srcOpt.get().buildSnapshot(e.occurredAt());
            } else if (existing != null) {
                base = existing;
            } else {
                base = build(e.info(), DownloadStatus.FAILED, 0L, -1L, e.occurredAt(), e.occurredAt());
            }
            final DownloadSnapshot failedSnap = withStatus(base, DownloadStatus.FAILED, Instant.now());
            saveWithObservability(failedSnap);
            return null;
        });
    }

    // ── flush ─────────────────────────────────────────────────────────────────

    /**
     * Records an authoritative post-quiescence snapshot for a download that was active
     * when the manager shut down. Atomically removes the stale event-derived entry from
     * the pending map (preventing a future flush from overwriting the authoritative snapshot)
     * and persists the snapshot to the store.
     *
     * <p>This must be called after the manager's workers have reached quiescence and before
     * {@link #close()} is called.
     *
     * @param snap the final consistent snapshot built from the download's {@link CheckpointSource}
     */
    public void notifyShutdownCheckpoint(final DownloadSnapshot snap) {
        Objects.requireNonNull(snap, "snap must not be null");
        // Remove before saving: if a concurrent periodic flush wins the computeIfPresent race
        // and saves the stale entry, our subsequent saveWithObservability call overwrites it.
        // If we remove first, the flush is a no-op. Either way the authoritative snapshot wins.
        pending.remove(snap.downloadId());
        saveWithObservability(snap);
    }

    private void flushPending() {
        pending.forEach((id, snap) ->
                pending.computeIfPresent(id, (k, existing) -> {
                    final Optional<CheckpointSource> srcOpt = sourceProvider.findSource(k);
                    final DownloadSnapshot toSave = srcOpt.isPresent()
                            ? srcOpt.get().buildSnapshot(Instant.now())
                            : existing;
                    saveWithObservability(toSave);
                    // Update pending entry so subsequent terminal events have the richest data.
                    return toSave;
                }));
    }

    // ── persistence helpers ───────────────────────────────────────────────────

    private void saveWithObservability(final DownloadSnapshot snap) {
        try {
            store.save(snap);
        } catch (final Exception e) {
            log.warning("Failed to checkpoint " + snap.downloadId() + ": " + e.getMessage());
            final DownloadInfo failureInfo = new DownloadInfo(
                    snap.downloadId(), snap.sourceUri(), snap.targetPath());
            failureConsumer.accept(new DownloadCheckpointFailedEvent(
                    failureInfo, e.getMessage(), e, Instant.now()));
        }
    }

    private void deleteWithObservability(final String downloadId, final DownloadInfo info) {
        try {
            store.delete(downloadId);
        } catch (final Exception e) {
            log.warning("Failed to delete checkpoint for " + downloadId + ": " + e.getMessage());
            failureConsumer.accept(new DownloadCheckpointFailedEvent(
                    info, e.getMessage(), e, Instant.now()));
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
                java.util.List.of(),
                createdAt,
                checkpointAt);
    }

    private static DownloadSnapshot withStatus(final DownloadSnapshot s,
                                                final DownloadStatus status,
                                                final Instant checkpointAt) {
        return new DownloadSnapshot(
                s.downloadId(), s.sourceUri(), s.targetPath(), s.partialPath(),
                s.etag(), s.expectedTotalBytes(), s.confirmedBytes(), status,
                s.segments(), s.createdAt(), checkpointAt);
    }

    private Instant createdAtOrNow(final String downloadId, final Instant fallback) {
        final DownloadSnapshot existing = pending.get(downloadId);
        return existing != null ? existing.createdAt() : fallback;
    }

    private static void validateInterval(final Duration checkpointInterval) {
        if (checkpointInterval.isNegative() || checkpointInterval.isZero()) {
            throw new IllegalArgumentException(
                    "checkpointInterval must be positive: " + checkpointInterval);
        }
    }
}
