package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
import jsanca.katabasis.core.internal.util.MapperExceptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Shared lifecycle helpers for HTTP download strategies.
 *
 * <p>Centralises event emission, error mapping, cancellation signalling, and
 * partial-file cleanup so that {@link HttpDownloadStrategy} and
 * {@link SegmentedHttpDownloadStrategy} stay consistent without duplicating logic.
 *
 * <p>All methods are stateless and thread-safe.
 * @author jsanca &amp; elo
 */
public final class HttpDownloadSupport {

    private static final Logger log = LoggerFactory.getLogger(HttpDownloadSupport.class);

    private HttpDownloadSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Emits a {@link DownloadCompletedEvent} with the elapsed duration.
     *
     * @param info       identifying information for the download
     * @param emitter    event consumer
     * @param startedAt  instant the download began
     * @param totalBytes total bytes written to the destination file
     */
    public static void onComplete(final DownloadInfo info,
                                   final Consumer<DownloadEvent> emitter,
                                   final Instant startedAt,
                                   final long totalBytes) {
        final Duration duration = Duration.between(startedAt, Instant.now());
        log.debug("HTTP download completed for {} in {} ms", info.downloadId(), duration.toMillis());
        emitter.accept(new DownloadCompletedEvent(
                info, info.targetPath(), totalBytes, null, duration, Instant.now()));
    }

    /**
     * Emits a {@link DownloadCancelledEvent} from the details held by the exception.
     *
     * @param info    identifying information for the download
     * @param emitter event consumer
     * @param e       the cancellation exception carrying progress state
     */
    public static void onCancelled(final DownloadInfo info,
                                    final Consumer<DownloadEvent> emitter,
                                    final DownloadCancelledException e) {
        log.info("HTTP download cancelled for {}", info.downloadId());
        emitter.accept(new DownloadCancelledEvent(
                info, e.cancelledAt(), e.bytesRead(), e.totalBytes(), e.progress()));
    }

    /**
     * Emits a {@link DownloadFailedEvent} with a mapped error code and elapsed duration.
     *
     * @param info      identifying information for the download
     * @param emitter   event consumer
     * @param e         the exception that caused the failure
     * @param startedAt instant the download began
     */
    public static void onError(final DownloadInfo info,
                                final Consumer<DownloadEvent> emitter,
                                final Exception e,
                                final Instant startedAt) {
        final Duration duration = Duration.between(startedAt, Instant.now());
        log.error("HTTP download failed for {}", info.downloadId(), e);
        emitter.accept(new DownloadFailedEvent(
                info,
                e.getMessage() != null ? e.getMessage() : "Download failed",
                e, duration, Instant.now(), mapErrorCode(e)));
    }

    /**
     * Throws a {@link DownloadCancelledException} capturing the current progress.
     *
     * <p>Safe to call from multiple threads: reads {@code totalDownloaded} atomically.
     * Progress is clamped to {@code [0.0, 1.0]} and zero-division is avoided when
     * {@code contentLength} is unknown (0).
     *
     * @param info           identifying information for the download
     * @param totalDownloaded atomic counter of bytes downloaded so far
     * @param contentLength   expected total file size in bytes (0 if unknown)
     * @throws DownloadCancelledException always
     */
    public static void throwCancelled(final DownloadInfo info,
                                       final AtomicLong totalDownloaded,
                                       final long contentLength) {
        final long downloaded = totalDownloaded.get();
        final double progress = contentLength > 0
                ? Math.min(1.0d, (double) downloaded / contentLength)
                : 0.0d;
        throw new DownloadCancelledException(info, downloaded, contentLength, progress, Instant.now());
    }

    /**
     * Deletes the target file, silencing any {@link IOException} with a warning log.
     *
     * @param info identifying information whose {@code targetPath} will be deleted
     */
    public static void deleteFile(final DownloadInfo info) {
        try {
            Files.deleteIfExists(info.targetPath());
        } catch (IOException ex) {
            log.warn("Failed to delete partial file: {}", info.targetPath(), ex);
        }
    }

    /**
     * Maps an exception to a {@link DownloadErrorCode}.
     *
     * <p>Checks the direct exception first, then its cause, to handle wrapped
     * checked exceptions produced by {@link SegmentedHttpDownloadStrategy}.
     *
     * @param e the exception to classify
     * @return the most specific matching error code
     */
    public static DownloadErrorCode mapErrorCode(final Exception e) {

        if (e instanceof HttpDownloadStatusException h) {
            return h.mapHttpStatusToErrorCode();
        }

        final Throwable cause = e.getCause();

        if (cause instanceof HttpDownloadStatusException h) {
            return h.mapHttpStatusToErrorCode();
        }

        if (cause instanceof Exception causeAsException) {
            return MapperExceptionUtil.mapExceptionToErrorCode(causeAsException);
        }

        return MapperExceptionUtil.mapExceptionToErrorCode(e);
    }
}
