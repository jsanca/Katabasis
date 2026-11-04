package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
import jsanca.katabasis.core.internal.execution.DownloadExecutionContext;
import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import jsanca.katabasis.core.internal.execution.PauseListener;
import jsanca.katabasis.core.internal.model.DownloadTask;
import jsanca.katabasis.core.internal.retry.RetryExecutor;
import jsanca.katabasis.core.internal.strategy.DownloadStrategy;
import jsanca.katabasis.core.internal.util.ByteSize;
import jsanca.katabasis.core.internal.util.PathPreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.FailedException;
import java.util.concurrent.StructuredTaskScope.Joiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static jsanca.katabasis.core.internal.util.Preconditions.reject;

/**
 * HTTP download strategy that divides a file into parallel byte-range chunks.
 *
 * <p>The caller is responsible for confirming that the server supports byte-range
 * requests and that the content length meets any applicable threshold before
 * constructing this strategy. No probe or fallback is performed here.
 *
 * <p>Each chunk is assigned an exclusive byte range and written at its absolute
 * position in a shared {@link FileChannel}, so no post-download reassembly step
 * is required.
 *
 * @author jsanca &amp; elo
 */
public final class SegmentedHttpDownloadStrategy implements DownloadStrategy {

    private static final Logger log = LoggerFactory.getLogger(SegmentedHttpDownloadStrategy.class);
    private static final int DEFAULT_BUFFER_SIZE = 8 * (int) ByteSize.KB;

    private final HttpClient httpClient;
    private final int segmentCount;
    private final long contentLength;
    private final int bufferSize;

    /**
     * Creates a strategy with a default JDK {@link HttpClient}.
     *
     * @param segmentCount  number of parallel chunks (must be &gt; 0)
     * @param contentLength total file size in bytes, pre-validated by the caller (must be &gt; 0)
     */
    public SegmentedHttpDownloadStrategy(final int segmentCount, final long contentLength) {
        this(HttpClients.createDefault(), segmentCount, contentLength, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates a strategy with an explicit HTTP client and buffer size.
     *
     * @param httpClient    the HTTP client to use
     * @param segmentCount  number of parallel chunks (must be &gt; 0)
     * @param contentLength total file size in bytes, pre-validated by the caller (must be &gt; 0)
     * @param bufferSize    stream buffer size in bytes (must be &gt; 0)
     */
    public SegmentedHttpDownloadStrategy(final HttpClient httpClient,
                                          final int segmentCount,
                                          final long contentLength,
                                          final int bufferSize) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        reject(segmentCount <= 0, "segmentCount must be > 0");
        reject(contentLength <= 0, "contentLength must be > 0");
        reject(bufferSize <= 0, "bufferSize must be > 0");
        this.segmentCount = segmentCount;
        this.contentLength = contentLength;
        this.bufferSize = bufferSize;
    }

    @Override
    public void download(final DownloadTask task,
                         final DownloadExecutionContext context,
                         final Consumer<DownloadEvent> emitter) {

        DownloadStrategy.validate(task, context, emitter);

        final DownloadInfo info = task.info();
        final Instant startedAt = Instant.now();
        final AtomicLong totalDownloaded = new AtomicLong(0);

        log.debug("Starting segmented HTTP download for {} from {}", info.downloadId(), info.sourceUri());

        try {
            PathPreparer.prepareParentDirectory(info.targetPath());

            final List<HttpRangeSupport.ChunkRange> chunks =
                    HttpRangeSupport.split(contentLength, segmentCount);

            log.debug("Downloading {} in {} segments (totalBytes={})",
                    info.downloadId(), chunks.size(), contentLength);

            try (FileChannel channel = FileChannel.open(info.targetPath(),
                    StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {

                channel.truncate(contentLength);

                final AtomicBoolean pausedFired  = new AtomicBoolean(false);
                final AtomicBoolean resumedFired = new AtomicBoolean(false);

                final RetryExecutor retryExecutor = context.retryExecutorFor(HttpFailureClassifier.INSTANCE);

                try (var scope = StructuredTaskScope.open(Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                    for (final HttpRangeSupport.ChunkRange chunk : chunks) {
                        scope.fork(() -> downloadChunk(chunk, channel, task,
                                totalDownloaded, context, emitter, pausedFired, resumedFired,
                                retryExecutor));
                    }
                    scope.join();
                }
            }

            HttpDownloadSupport.onComplete(info, emitter, startedAt, contentLength);

        } catch (FailedException e) {
            final Throwable cause = e.getCause();
            HttpDownloadSupport.deleteFile(info);
            if (cause instanceof DownloadCancelledException dce) {
                HttpDownloadSupport.onCancelled(info, emitter, dce);
            } else if (cause instanceof Exception ex) {
                HttpDownloadSupport.onError(info, emitter, ex, startedAt);
            } else {
                HttpDownloadSupport.onError(info, emitter, e, startedAt);
            }
        } catch (DownloadCancelledException e) {
            HttpDownloadSupport.deleteFile(info);
            HttpDownloadSupport.onCancelled(info, emitter, e);
        } catch (Exception e) {
            HttpDownloadSupport.deleteFile(info);
            HttpDownloadSupport.onError(info, emitter, e, startedAt);
        }
    }

    private Void downloadChunk(final HttpRangeSupport.ChunkRange chunk,
                                final FileChannel channel,
                                final DownloadTask task,
                                final AtomicLong totalDownloaded,
                                final DownloadExecutionContext context,
                                final Consumer<DownloadEvent> emitter,
                                final AtomicBoolean pausedFired,
                                final AtomicBoolean resumedFired,
                                final RetryExecutor retryExecutor)
            throws IOException, InterruptedException {

        try {
            retryExecutor.execute(
                    () -> doChunkAttempt(chunk, channel, task, totalDownloaded, context, emitter,
                            pausedFired, resumedFired),
                    task::isCancellationRequested);
        } catch (DownloadCancelledException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Chunk " + chunk.index() + " failed: " + e.getMessage(), e);
        }
        return null;
    }

    private void doChunkAttempt(final HttpRangeSupport.ChunkRange chunk,
                                 final FileChannel channel,
                                 final DownloadTask task,
                                 final AtomicLong totalDownloaded,
                                 final DownloadExecutionContext context,
                                 final Consumer<DownloadEvent> emitter,
                                 final AtomicBoolean pausedFired,
                                 final AtomicBoolean resumedFired) throws IOException, InterruptedException {

        final DownloadInfo info = task.info();

        final HttpResponse<InputStream> response = httpClient.send(
                HttpRangeSupport.rangeRequest(info.sourceUri(), chunk.start(), chunk.end()),
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() != 206) {
            throw new HttpDownloadStatusException(info, response.statusCode());
        }

        log.debug("Chunk {} [{}-{}] started for {}",
                chunk.index(), chunk.start(), chunk.end(), info.downloadId());

        final byte[] buffer = new byte[bufferSize];
        long position = chunk.start();
        int bytesRead;
        // Track bytes transferred in this attempt so we can rewind on failure.
        long attemptBytes = 0;

        try (InputStream inputStream = response.body()) {
            while ((bytesRead = inputStream.read(buffer)) != -1) {

                if (task.isCancellationRequested()) {
                    HttpDownloadSupport.throwCancelled(info, totalDownloaded, contentLength);
                }

                channel.write(ByteBuffer.wrap(buffer, 0, bytesRead), position);
                position += bytesRead;
                attemptBytes += bytesRead;

                final long newTotal = totalDownloaded.addAndGet(bytesRead);
                emitter.accept(new DownloadProgressEvent(info, newTotal, contentLength, Instant.now()));

                handlePauseRequest(task, context.pauseCoordinator(), emitter, info,
                                totalDownloaded, pausedFired, resumedFired);

                if (task.isCancellationRequested()) {
                    HttpDownloadSupport.throwCancelled(info, totalDownloaded, contentLength);
                }
            }
        } catch (final DownloadCancelledException e) {
            // Cancellation is terminal — do not rewind; the cancelled event uses totalDownloaded.
            throw e;
        } catch (final Exception e) {
            // Rewind phantom bytes so progress does not exceed contentLength on retry.
            totalDownloaded.addAndGet(-attemptBytes);
            throw e;
        }

        log.debug("Chunk {} [{}-{}] completed for {}",
                chunk.index(), chunk.start(), chunk.end(), info.downloadId());
    }

    private void handlePauseRequest(final DownloadTask task,
                                     final PauseCoordinator pauseCoordinator,
                                     final Consumer<DownloadEvent> emitter,
                                     final DownloadInfo info,
                                     final AtomicLong totalDownloaded,
                                     final AtomicBoolean pausedFired,
                                     final AtomicBoolean resumedFired) throws InterruptedException {

        pauseCoordinator.awaitIfPaused(info.downloadId(), task.cancellationToken(),
                new PauseListener() {
                    private Instant pausedAt;

                    @Override
                    public void onPaused() {
                        // Guard: N chunk threads call this concurrently — only the first emits.
                        if (pausedFired.compareAndSet(false, true)) {
                            this.pausedAt = Instant.now();
                            resumedFired.set(false);
                            final long downloaded = totalDownloaded.get();
                            log.info("Segmented download paused for {} at {}/{} bytes",
                                    info.downloadId(), downloaded, contentLength);
                            emitter.accept(new DownloadPausedEvent(info, downloaded, contentLength,
                                    this.pausedAt.truncatedTo(ChronoUnit.MILLIS)));
                        }
                    }

                    @Override
                    public void onResumed() {
                        // Guard: N chunk threads call this concurrently — only the first emits.
                        if (resumedFired.compareAndSet(false, true)) {
                            pausedFired.set(false);
                            final Instant resumedAt = Instant.now();
                            final long downloaded = totalDownloaded.get();
                            log.info("Segmented download resumed for {}", info.downloadId());
                            emitter.accept(new DownloadResumedEvent(info, downloaded, contentLength,
                                    resumedAt.truncatedTo(ChronoUnit.MILLIS)));
                        }
                    }
                });
    }
}
