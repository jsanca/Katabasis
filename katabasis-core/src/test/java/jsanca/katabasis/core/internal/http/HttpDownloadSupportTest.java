package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.DownloadErrorCode;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class HttpDownloadSupportTest {

    @TempDir
    Path tempDir;

    private static DownloadInfo info(final Path dest) {
        return new DownloadInfo("test-id", URI.create("https://example.com/file"), dest);
    }

    // ── throwCancelled ────────────────────────────────────────────────────────

    @Test
    void throwCancelledShouldAlwaysThrowDownloadCancelledException() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(500L);

        assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 1000L));
    }

    @Test
    void throwCancelledShouldReportCorrectBytesReadAndTotalBytes() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(300L);

        final DownloadCancelledException ex = assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 1000L));

        assertEquals(300L, ex.bytesRead());
        assertEquals(1000L, ex.totalBytes());
    }

    @Test
    void throwCancelledShouldCalculateProgressAsRatio() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(250L);

        final DownloadCancelledException ex = assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 1000L));

        assertEquals(0.25d, ex.progress(), 1e-9);
    }

    @Test
    void throwCancelledShouldClampProgressToOneWhenDownloadedExceedsTotal() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(1200L); // more than total

        final DownloadCancelledException ex = assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 1000L));

        assertEquals(1.0d, ex.progress(), 1e-9);
    }

    @Test
    void throwCancelledShouldReturnZeroProgressWhenContentLengthIsUnknown() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(500L);

        final DownloadCancelledException ex = assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 0L));

        assertEquals(0.0d, ex.progress(), 1e-9);
    }

    @Test
    void throwCancelledShouldCarryCancelledAtTimestamp() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final AtomicLong downloaded = new AtomicLong(0L);

        final DownloadCancelledException ex = assertThrows(DownloadCancelledException.class,
                () -> HttpDownloadSupport.throwCancelled(info, downloaded, 100L));

        assertNotNull(ex.cancelledAt());
    }

    // ── mapErrorCode ──────────────────────────────────────────────────────────

    @Test
    void mapErrorCodeShouldReturn404ForHttpNotFound() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final HttpDownloadStatusException e = new HttpDownloadStatusException(info, 404);

        assertEquals(DownloadErrorCode.HTTP_NOT_FOUND, HttpDownloadSupport.mapErrorCode(e));
    }

    @Test
    void mapErrorCodeShouldReturn401ForHttpUnauthorized() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final HttpDownloadStatusException e = new HttpDownloadStatusException(info, 401);

        assertEquals(DownloadErrorCode.HTTP_UNAUTHORIZED, HttpDownloadSupport.mapErrorCode(e));
    }

    @Test
    void mapErrorCodeShouldReturn403ForHttpForbidden() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final HttpDownloadStatusException e = new HttpDownloadStatusException(info, 403);

        assertEquals(DownloadErrorCode.HTTP_FORBIDDEN, HttpDownloadSupport.mapErrorCode(e));
    }

    @Test
    void mapErrorCodeShouldReturnServerErrorFor5xx() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final HttpDownloadStatusException e = new HttpDownloadStatusException(info, 500);

        assertEquals(DownloadErrorCode.HTTP_SERVER_ERROR, HttpDownloadSupport.mapErrorCode(e));
    }

    @Test
    void mapErrorCodeShouldReturnIoErrorForIOException() {
        final IOException e = new IOException("disk full");

        assertEquals(DownloadErrorCode.IO_ERROR, HttpDownloadSupport.mapErrorCode(e));
    }

    @Test
    void mapErrorCodeShouldReturnInterruptedForInterruptedException() {
        final RuntimeException wrapped = new RuntimeException(new InterruptedException());

        assertEquals(DownloadErrorCode.INTERRUPTED, HttpDownloadSupport.mapErrorCode(wrapped));
    }

    @Test
    void mapErrorCodeShouldUnwrapHttpStatusExceptionFromRuntimeExceptionCause() {
        final DownloadInfo info = info(tempDir.resolve("out"));
        final HttpDownloadStatusException cause = new HttpDownloadStatusException(info, 403);
        final RuntimeException wrapped = new RuntimeException(cause);

        assertEquals(DownloadErrorCode.HTTP_FORBIDDEN, HttpDownloadSupport.mapErrorCode(wrapped));
    }

    @Test
    void mapErrorCodeShouldReturnUnknownForUnrecognisedException() {
        final RuntimeException e = new RuntimeException("something unexpected");

        assertEquals(DownloadErrorCode.UNKNOWN, HttpDownloadSupport.mapErrorCode(e));
    }
}
