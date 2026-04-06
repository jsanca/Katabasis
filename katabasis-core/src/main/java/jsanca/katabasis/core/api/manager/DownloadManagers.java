package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.event.DownloadErrorCode;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import jsanca.katabasis.core.api.model.*;
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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    public static DownloadManager createDefault(DownloadConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return new DefaultDownloadManager(config, DownloadExecutors.createVirtualThreadExecutor(config));
    }

    /**
     * Default implementation kept hidden inside the factory.
     */
    static final class DefaultDownloadManager implements DownloadManager {

        private static final Logger log = LoggerFactory.getLogger(DefaultDownloadManager.class);

        private final DownloadConfig config;
        private final DownloadExecutor executor;
        private final List<Consumer<DownloadEvent>> listeners = new CopyOnWriteArrayList<>();
        private final DownloadStrategyResolver strategyResolver;

        private final DownloadRegistry downloadRegistry = new DownloadRegistry();

        private final PauseCoordinator pauseCoordinator = new PauseCoordinator();

        DefaultDownloadManager(final DownloadConfig config, final DownloadExecutor executor) {
            this(config, executor, new DefaultDownloadStrategyResolver());
        }

        DefaultDownloadManager(final DownloadConfig config,
                               final DownloadExecutor executor,
                               final DownloadStrategyResolver strategyResolver) {
            this.config = Objects.requireNonNull(config, "config must not be null");
            this.executor = Objects.requireNonNull(executor, "executor must not be null");
            this.strategyResolver = Objects.requireNonNull(strategyResolver, "strategyResolver must not be null");
        }

        @Override
        public DownloadSubmissionResult download(final DownloadRequest request) {

            Objects.requireNonNull(request, "request must not be null");
            final String downloadId = UUID.randomUUID().toString();
            final DownloadTask task = new DownloadTask(
                    new DownloadInfo(downloadId, request.sourceUri(), request.targetPath()));

            log.debug("Submitting download: {}", request);

            final Instant createdAt = Instant.now();
            this.downloadRegistry.register(task);
            this.executor.execute(() -> runDownload(task));

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

        private void runDownload(final DownloadTask task) {

            Objects.requireNonNull(task, "task must not be null");
            log.debug("Starting download: {}", task);
            final Instant occurredAt = Instant.now();
            final DownloadInfo info = task.info();
            this.emit(new DownloadStartedEvent(info, occurredAt));
            log.debug("Download started: {}", info.downloadId());

            try {

                final DownloadStrategy strategy = this.strategyResolver.resolve(info);
                log.debug("Resolved strategy {} for {}", strategy.getClass().getSimpleName(), info.downloadId());

                strategy.download(task, new DownloadExecutionContext(this.pauseCoordinator), this::emit);
            } catch (Exception e) {

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
    }
}
