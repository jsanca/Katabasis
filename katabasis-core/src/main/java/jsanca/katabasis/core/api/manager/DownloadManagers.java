package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadErrorCode;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import jsanca.katabasis.core.api.exception.DownloadRejectedException;
import jsanca.katabasis.core.api.model.*;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.RetryConfig;
import jsanca.katabasis.core.internal.execution.DownloadExecutionContext;
import jsanca.katabasis.core.internal.execution.DownloadExecutor;
import jsanca.katabasis.core.internal.execution.DownloadExecutors;
import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import jsanca.katabasis.core.internal.model.DownloadTask;
import jsanca.katabasis.core.internal.strategy.DefaultDownloadStrategyResolver;
import jsanca.katabasis.core.internal.strategy.DownloadStrategy;
import jsanca.katabasis.core.internal.strategy.DownloadStrategyResolver;
import jsanca.katabasis.core.internal.util.MapperExceptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Factory for creating {@link DownloadManager} instances.
 *
 * @author jsanca + elo
 */
public final class DownloadManagers {

    private DownloadManagers() {
        throw new UnsupportedOperationException("Utility class should not be instantiated");
    }

    /**
     * Creates a default {@link DownloadManager} with sensible defaults.
     *
     * @return a default download manager
     */
    public static DownloadManager createDefault() {
        return createDefault(DownloadConfig.defaultConfig());
    }

    /**
     * Creates a default {@link DownloadManager} using the provided configuration.
     *
     * @param config the downloader configuration
     * @return a configured download manager
     */
    public static DownloadManager createDefault(final DownloadConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new DefaultDownloadManager(config,
                DownloadExecutors.createVirtualThreadExecutor(config),
                (Consumer<DownloadSnapshot>) null);
    }

    /**
     * Creates a default {@link DownloadManager} with a shutdown snapshot consumer.
     *
     * <p>When the manager is closed, it cancels all active downloads, waits for workers to
     * reach quiescence, and then calls {@code shutdownSnapshotConsumer} once per download
     * that had a registered {@link CheckpointSource}. Snapshots are built after workers stop,
     * so the confirmed-bytes values are final and consistent.
     *
     * @param config                   the downloader configuration
     * @param shutdownSnapshotConsumer called with each active download's post-quiescence snapshot
     * @return a configured download manager that also implements {@link CheckpointSourceProvider}
     */
    public static DownloadManager createDefault(final DownloadConfig config,
                                                 final Consumer<DownloadSnapshot> shutdownSnapshotConsumer) {
        Objects.requireNonNull(config,                   "config must not be null");
        Objects.requireNonNull(shutdownSnapshotConsumer, "shutdownSnapshotConsumer must not be null");
        return new DefaultDownloadManager(config,
                DownloadExecutors.createVirtualThreadExecutor(config),
                shutdownSnapshotConsumer);
    }

    /**
     * Default implementation kept hidden inside the factory.
     * Also implements {@link CheckpointSourceProvider} so that external coordinator
     * components can look up per-download sources without depending on internal types.
     */
    static final class DefaultDownloadManager implements DownloadManager, CheckpointSourceProvider {

        private static final Logger log = LoggerFactory.getLogger(DefaultDownloadManager.class);

        private enum ManagerLifecycle { OPEN, SHUTTING_DOWN, CLOSED }

        private final DownloadConfig config;
        private final DownloadExecutor executor;
        private final List<Consumer<DownloadEvent>> listeners = new CopyOnWriteArrayList<>();
        private final DownloadStrategyResolver strategyResolver;
        private final Consumer<DownloadSnapshot> shutdownSnapshotConsumer;

        private final DownloadRegistry downloadRegistry = new DownloadRegistry();

        private final PauseCoordinator pauseCoordinator = new PauseCoordinator();

        private volatile ManagerLifecycle lifecycle = ManagerLifecycle.OPEN;
        private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private final Set<String> shutdownCancelledIds = ConcurrentHashMap.newKeySet();
        // IDs that completed or failed naturally during the executor drain window; these must
        // not receive a post-quiescence notifyShutdownCheckpoint call (coordinator already handled them).
        private final Set<String> naturallyTerminatedDuringShutdown = ConcurrentHashMap.newKeySet();

        DefaultDownloadManager(final DownloadConfig config,
                               final DownloadExecutor executor,
                               final Consumer<DownloadSnapshot> shutdownSnapshotConsumer) {
            this(config, executor, new DefaultDownloadStrategyResolver(config), shutdownSnapshotConsumer);
        }

        DefaultDownloadManager(final DownloadConfig config,
                               final DownloadExecutor executor,
                               final DownloadStrategyResolver strategyResolver) {
            this(config, executor, strategyResolver, null);
        }

        DefaultDownloadManager(final DownloadConfig config,
                               final DownloadExecutor executor,
                               final DownloadStrategyResolver strategyResolver,
                               final Consumer<DownloadSnapshot> shutdownSnapshotConsumer) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            this.strategyResolver = Objects.requireNonNull(strategyResolver, "strategyResolver must not be null");
            this.shutdownSnapshotConsumer = shutdownSnapshotConsumer; // nullable
        }

        // ── CheckpointSourceProvider ──────────────────────────────────────────

        @Override
        public Optional<CheckpointSource> findSource(final String downloadId) {
            Objects.requireNonNull(downloadId, "downloadId must not be null");
            return downloadRegistry.findTask(downloadId)
                    .map(DownloadTask::checkpointSource);
        }

        // ── DownloadManager ───────────────────────────────────────────────────

        @Override
        public DownloadSubmissionResult download(final DownloadRequest request) {

            Objects.requireNonNull(request, "request must not be null");

            final String downloadId = UUID.randomUUID().toString();
            final DownloadTask task = new DownloadTask(
                    new DownloadInfo(downloadId, request.sourceUri(), request.targetPath()));

            log.debug("Submitting download: {}", request);

            final RetryConfig effectiveRetry = request.retryConfig()
                    .orElse(config.retryConfig());

            final Instant createdAt = Instant.now();

            lifecycleLock.readLock().lock();
            try {
                if (lifecycle != ManagerLifecycle.OPEN) {
                    throw new DownloadRejectedException(
                            "DownloadManager is " + lifecycle + "; new submissions are not accepted");
                }
                this.downloadRegistry.register(task);
                this.executor.execute(() -> runDownload(task, effectiveRetry));
            } finally {
                lifecycleLock.readLock().unlock();
            }

            return new DownloadSubmissionResult(downloadId, DownloadStatus.PENDING, createdAt);
        }

        @Override
        public void addListener(final Consumer<DownloadEvent> listener) {

            Objects.requireNonNull(listener, "listener must not be null");
            this.listeners.add(listener);
        }

        @Override
        public void removeListener(final Consumer<DownloadEvent> listener) {

            this.listeners.remove(listener);
        }

        private void runDownload(final DownloadTask task, final RetryConfig retryConfig) {

            Objects.requireNonNull(task, "task must not be null");
            log.debug("Starting download: {}", task);
            final Instant occurredAt = Instant.now();
            final DownloadInfo info = task.info();
            this.emit(new DownloadStartedEvent(info, occurredAt));
            log.debug("Download started: {}", info.downloadId());

            try {

                final DownloadStrategy strategy = this.strategyResolver.resolve(info);
                log.debug("Resolved strategy {} for {}", strategy.getClass().getSimpleName(), info.downloadId());

                strategy.download(task,
                        new DownloadExecutionContext(this.pauseCoordinator, retryConfig),
                        this::emit);
            } catch (Throwable e) {

                final Duration duration = Duration.between(occurredAt, Instant.now());
                log.error("Download failed: {}", info.downloadId(), e);
                final DownloadErrorCode errorCode = MapperExceptionUtil.mapExceptionToErrorCode(e);

                this.emit(new DownloadFailedEvent(
                        info,
                        e.getMessage(),
                        e,
                        duration,
                        Instant.now(),
                        errorCode
                ));
            } finally {
                this.downloadRegistry.remove(info.downloadId());
                this.pauseCoordinator.clear(info.downloadId());
            }
        }

        private void emit(final DownloadEvent event) {

            Objects.requireNonNull(event, "event must not be null");
            if (event instanceof DownloadCancelledEvent cancelled
                    && shutdownCancelledIds.contains(cancelled.downloadInfo().downloadId())) {
                return;
            }
            // Track downloads that terminate for reasons other than shutdown cancellation.
            // The post-quiescence loop skips these IDs because the coordinator already handled
            // the terminal event (deleted the manifest for Completed; saved FAILED snapshot for Failed).
            // Always track Completed. Track Failed only when the ID is not in shutdownCancelledIds
            // (i.e., the failure was not caused by shutdown cancellation — those still need a
            // post-quiescence checkpoint).
            if (event instanceof DownloadCompletedEvent completed) {
                naturallyTerminatedDuringShutdown.add(completed.info().downloadId());
            } else if (event instanceof DownloadFailedEvent failed
                    && !shutdownCancelledIds.contains(failed.info().downloadId())) {
                naturallyTerminatedDuringShutdown.add(failed.info().downloadId());
            }
            for (final Consumer<DownloadEvent> listener : listeners) {
                listener.accept(event);
            }
        }

        @Override
        public DownloadControlResult cancel(final String downloadId) {

            Objects.requireNonNull(downloadId, "downloadId must not be null");
            final Optional<DownloadTask>  maybeTask = this.downloadRegistry.findTask(downloadId);

            log.debug("Download Id: {}", downloadId);
            if (maybeTask.isPresent()) {

                final DownloadTask task = maybeTask.get();
                final DownloadControlAction action = DownloadControlAction.CANCEL;
                final DownloadControlStatus status = DownloadControlStatus.REQUESTED;
                final String message =  "Cancellation requested for download " + downloadId;
                task.requestCancellation();
                final DownloadTaskSnapshot snapshot  = task.snapshot();

                return new DownloadControlResult(downloadId, action, status, message, snapshot);
            }

            return new DownloadControlResult(
                    downloadId,
                    DownloadControlAction.CANCEL,
                    DownloadControlStatus.NOT_FOUND,
                    "Download not found: " + downloadId,
                    null
            );
        }

        @Override
        public DownloadControlResult pause(final String downloadId) {
            Objects.requireNonNull(downloadId, "downloadId must not be null");
            log.debug("Pause requested for downloadId={}", downloadId);
            final Optional<DownloadTask> downloadTaskOpt = this.downloadRegistry.findTask(downloadId);

            if (downloadTaskOpt.isPresent()) {
                log.debug("Download found. Forwarding pause request for downloadId={}", downloadId);
                this.pauseCoordinator.requestPause(downloadId);

                return new DownloadControlResult(
                        downloadId,
                        DownloadControlAction.PAUSE,
                        DownloadControlStatus.REQUESTED,
                        "Pause requested for download " + downloadId,
                        downloadTaskOpt.get().snapshot()
                );
            }

            log.debug("Pause request ignored because download was not found: {}", downloadId);
            return new DownloadControlResult(
                    downloadId,
                    DownloadControlAction.PAUSE,
                    DownloadControlStatus.NOT_FOUND,
                    "Could not invoke pause because task was not found: " + downloadId,
                    null
            );
        }

        @Override
        public DownloadControlResult resume(final String downloadId) {
            Objects.requireNonNull(downloadId, "downloadId must not be null");
            log.debug("Resume requested for downloadId={}", downloadId);
            final Optional<DownloadTask> downloadTaskOpt = this.downloadRegistry.findTask(downloadId);

            if (downloadTaskOpt.isPresent()) {
                log.debug("Download found. Forwarding resume request for downloadId={}", downloadId);
                this.pauseCoordinator.requestResume(downloadId);

                return new DownloadControlResult(
                        downloadId,
                        DownloadControlAction.RESUME,
                        DownloadControlStatus.REQUESTED,
                        "Resume requested for download " + downloadId,
                        downloadTaskOpt.get().snapshot()
                );
            }

            log.debug("Resume request ignored because download was not found: {}", downloadId);
            return new DownloadControlResult(
                    downloadId,
                    DownloadControlAction.RESUME,
                    DownloadControlStatus.NOT_FOUND,
                    "Could not invoke resume because task was not found: " + downloadId,
                    null
            );
        }

        @Override
        public void close() {
            lifecycleLock.writeLock().lock();
            final boolean isFirstCaller;
            final List<DownloadTask> activeTasks;
            try {
                if (lifecycle == ManagerLifecycle.OPEN) {
                    lifecycle = ManagerLifecycle.SHUTTING_DOWN;
                    isFirstCaller = true;
                    // Capture task references before cancellation; allTasks() returns a
                    // defensive copy so iteration is safe outside the lock.
                    activeTasks = new ArrayList<>(downloadRegistry.allTasks());
                    for (final DownloadTask task : activeTasks) {
                        final String id = task.info().downloadId();
                        shutdownCancelledIds.add(id);
                        pauseCoordinator.clear(id);
                        task.requestCancellation();
                    }
                } else {
                    isFirstCaller = false;
                    activeTasks = List.of();
                }
            } finally {
                lifecycleLock.writeLock().unlock();
            }

            if (!isFirstCaller) {
                try {
                    closeLatch.await();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return;
            }

            try {
                executor.close(); // blocks until workers reach quiescence (or timeout)
            } finally {
                // Build authoritative snapshots after workers stop. CheckpointSource is
                // still accessible via the captured task reference even after registry removal.
                if (shutdownSnapshotConsumer != null) {
                    final Instant now = Instant.now();
                    for (final DownloadTask task : activeTasks) {
                        final String id = task.info().downloadId();
                        if (naturallyTerminatedDuringShutdown.contains(id)) {
                            continue; // coordinator already handled completed/failed terminal event
                        }
                        final CheckpointSource src = task.checkpointSource();
                        if (src != null) {
                            try {
                                shutdownSnapshotConsumer.accept(src.buildSnapshot(now));
                            } catch (final Exception ex) {
                                log.warn("Failed to build shutdown snapshot for {}", id, ex);
                            }
                        }
                    }
                }
                lifecycle = ManagerLifecycle.CLOSED;
                closeLatch.countDown();
            }
        }
    }
}
