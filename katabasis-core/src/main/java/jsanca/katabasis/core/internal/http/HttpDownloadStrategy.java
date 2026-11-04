package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.HttpTimeoutConfig;
import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import jsanca.katabasis.core.internal.execution.DownloadExecutionContext;
import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import jsanca.katabasis.core.internal.execution.PauseListener;
import jsanca.katabasis.core.internal.model.DownloadTask;
import jsanca.katabasis.core.internal.retry.RetryExecutor;
import jsanca.katabasis.core.internal.strategy.DownloadStrategy;
import jsanca.katabasis.core.internal.util.PathPreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * HTTP-based implementation of {@link DownloadStrategy} using the JDK {@link HttpClient}.
 *
 * <p>This strategy performs a blocking stream download and emits progress, completion,
 * and failure events through the provided emitter. It is designed to be executed by the
 * downloader execution layer, which may run it on virtual threads.
 * @author jsanca &amp; elo
 */
public final class HttpDownloadStrategy implements DownloadStrategy {

    private static final Logger log = LoggerFactory.getLogger(HttpDownloadStrategy.class);
    private static final int DEFAULT_BUFFER_SIZE = 8 * 1024;
    private final HttpClient httpClient;
    private final int bufferSize;
    private final HttpTimeoutConfig timeoutConfig;

    /**
     * Creates a strategy using a default JDK {@link HttpClient} with no timeouts.
     */
    public HttpDownloadStrategy() {
        this(HttpClients.createDefault(), DEFAULT_BUFFER_SIZE, HttpTimeoutConfig.disabled());
    }

    /**
     * Creates a strategy with a custom HTTP client, buffer size, and timeout configuration.
     *
     * @param httpClient    the HTTP client to use
     * @param bufferSize    the stream buffer size in bytes
     * @param timeoutConfig timeout settings applied per-request and for stream inactivity
     */
    public HttpDownloadStrategy(final HttpClient httpClient,
                                final int bufferSize,
                                final HttpTimeoutConfig timeoutConfig) {

        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.timeoutConfig = Objects.requireNonNull(timeoutConfig, "timeoutConfig must not be null");
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be > 0");
        }
        this.bufferSize = bufferSize;
    }

    @Override
    public void download(final DownloadTask task,
                         final DownloadExecutionContext context,
                         final Consumer<DownloadEvent> emitter) {

        DownloadStrategy.validate(task, context, emitter);

        final DownloadInfo info = task.info();
        final Instant startedAt = Instant.now();
        log.debug("Starting HTTP download for {} from {}", info.downloadId(), info.sourceUri());

        final PauseCoordinator pauseCoordinator = context.pauseCoordinator();
        final RetryExecutor retryExecutor = context.retryExecutorFor(HttpFailureClassifier.INSTANCE);

        try {
            retryExecutor.execute(
                    () -> doDownloadAttempt(task, pauseCoordinator, emitter, startedAt),
                    task::isCancellationRequested);
        } catch (DownloadCancelledException e) {
            HttpDownloadSupport.onCancelled(info, emitter, e);
        } catch (Exception e) {
            HttpDownloadSupport.onError(info, emitter, e, startedAt);
        }
    }

    private void doDownloadAttempt(final DownloadTask task,
                                    final PauseCoordinator pauseCoordinator,
                                    final Consumer<DownloadEvent> emitter,
                                    final Instant startedAt) throws Exception {

        final DownloadInfo info = task.info();

        PathPreparer.prepareParentDirectory(info.targetPath());

        final HttpResponse<InputStream> response = doGet(info);
        try (InputStream inputStream = response.body()) {
            if (isInvalidResponseStatus(response.statusCode())) {
                throw new jsanca.katabasis.core.internal.exception.HttpDownloadStatusException(
                        info, response.statusCode());
            }

            final long totalBytes = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(0L);

            log.debug("HTTP response OK for {}. contentLength={}", info.downloadId(), totalBytes);

            try (OutputStream outputStream = Files.newOutputStream(info.targetPath())) {
                final long downloadedBytes = doDownload(task, inputStream, outputStream,
                        totalBytes, pauseCoordinator, emitter);
                outputStream.flush();
                HttpDownloadSupport.onComplete(info, emitter, startedAt, downloadedBytes);
            }
        }
    }

    private long doDownload(final DownloadTask task,
                            final InputStream inputStream,
                            final OutputStream outputStream,
                            final long totalBytes,
                            final PauseCoordinator pauseCoordinator,
                            final Consumer<DownloadEvent> emitter) throws IOException, InterruptedException {

        final byte[] buffer = new byte[bufferSize];
        final AtomicLong downloadedBytes = new AtomicLong(0L);
        int bytesRead;
        final DownloadInfo info = task.info();
        final String downloadId = info.downloadId();

        final InactivityWatchdog watchdog = timeoutConfig.readInactivityTimeout()
                .map(d -> new InactivityWatchdog(d, inputStream, pauseCoordinator, downloadId))
                .orElse(null);

        try {
            while ((bytesRead = inputStream.read(buffer)) != -1) {

                if (watchdog != null) {
                    watchdog.recordActivity();
                }

                if (task.isCancellationRequested()) {
                    log.info("Cancelling the current download: {}", info.downloadId());
                    HttpDownloadSupport.throwCancelled(info, downloadedBytes, totalBytes);
                }
                outputStream.write(buffer, 0, bytesRead);
                downloadedBytes.addAndGet(bytesRead);

                log.debug("HTTP progress {}: {}/{} bytes",
                        info.downloadId(), downloadedBytes, totalBytes);

                emitter.accept(new DownloadProgressEvent(
                        info,
                        downloadedBytes.get(),
                        totalBytes,
                        Instant.now()
                ));

                // if pause was requested, wait; reset inactivity timer when resumed
                handlePauseRequest(task, totalBytes, pauseCoordinator, emitter, downloadId, info, downloadedBytes);

                if (watchdog != null) {
                    watchdog.resetTimer();
                }

                if (task.isCancellationRequested()) {
                    log.info("Cancelling the current download after pause handling: {}", info.downloadId());
                    HttpDownloadSupport.throwCancelled(info, downloadedBytes, totalBytes);
                }
            }
        } catch (final IOException e) {
            if (watchdog != null && watchdog.wasTriggered()) {
                throw new ReadInactivityTimeoutException(
                        "Read stalled for download " + downloadId
                                + " beyond " + timeoutConfig.readInactivityTimeout().get());
            }
            throw e;
        } finally {
            if (watchdog != null) {
                watchdog.close();
            }
        }

        return downloadedBytes.get();
    }

    private void handlePauseRequest(final DownloadTask task,
                                    final long totalBytes,
                                    final PauseCoordinator pauseCoordinator,
                                    final Consumer<DownloadEvent> emitter,
                                    final String downloadId,
                                    final DownloadInfo info,
                                    final AtomicLong downloadedBytes) throws InterruptedException {

        pauseCoordinator.awaitIfPaused(downloadId, task.cancellationToken(),
                new PauseListener() {
                    private Instant pausedAt;

                    @Override
                    public void onPaused() {
                        this.pausedAt = Instant.now();
                        log.info("HTTP download paused for {} at {}/{} bytes",
                                info.downloadId(), downloadedBytes.get(), totalBytes);
                        emitter.accept(new DownloadPausedEvent(
                                info, downloadedBytes.get(), totalBytes,
                                this.pausedAt.truncatedTo(ChronoUnit.MILLIS)));
                    }

                    @Override
                    public void onResumed() {
                        final Instant resumedAt = Instant.now();
                        final long pauseDuration = this.pausedAt != null
                                ? java.time.Duration.between(this.pausedAt, resumedAt).toMillis()
                                : 0L;
                        log.info("HTTP download resumed for {} after {} ms pause",
                                info.downloadId(), pauseDuration);
                        emitter.accept(new DownloadResumedEvent(
                                info, downloadedBytes.get(), totalBytes,
                                resumedAt.truncatedTo(ChronoUnit.MILLIS)));
                    }
                });
    }

    private static boolean isInvalidResponseStatus(final int statusCode) {
        return statusCode < 200 || statusCode >= 300;
    }

    private HttpResponse<InputStream> doGet(final DownloadInfo info) throws IOException, InterruptedException {

        final HttpRequest.Builder builder = HttpRequest.newBuilder(info.sourceUri()).GET();
        timeoutConfig.responseTimeout().ifPresent(builder::timeout);
        return this.httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
    }
}
