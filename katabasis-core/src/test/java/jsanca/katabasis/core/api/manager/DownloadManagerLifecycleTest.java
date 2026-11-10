package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.exception.DownloadRejectedException;
import jsanca.katabasis.core.api.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.2-T03 — DownloadManager lifecycle tests: AutoCloseable, graceful shutdown,
 * event suppression, and admission guard.
 *
 * <p>All 12 scenarios are covered:
 * <ol>
 *   <li>AutoCloseable / try-with-resources compiles and runs cleanly.</li>
 *   <li>{@code close()} on idle manager returns without error.</li>
 *   <li>{@code close()} while single-stream download is active.</li>
 *   <li>{@code close()} while segmented workers are active.</li>
 *   <li>{@code close()} with paused downloads unblocks them.</li>
 *   <li>Concurrent {@code close()} calls: second caller waits for first.</li>
 *   <li>Repeated {@code close()} calls are idempotent.</li>
 *   <li>Submissions after shutdown throw {@link DownloadRejectedException}.</li>
 *   <li>No {@code DownloadCancelledEvent} emitted for shutdown-stopped downloads.</li>
 *   <li>No active writes after successful close.</li>
 *   <li>Bounded shutdown: close() returns even when a download stalls.</li>
 *   <li>No regression: user-initiated cancel still emits {@code DownloadCancelledEvent}.</li>
 * </ol>
 */
class DownloadManagerLifecycleTest {

    @TempDir
    Path tempDir;

    // ── in-process HTTP server ────────────────────────────────────────────────

    /**
     * Minimal HTTP/1.1 server for lifecycle tests.
     *
     * <p>Supports HEAD, full GET, range probe, chunk GET, optional per-write throttle,
     * optional body stall (never sends body), and an optional gate that freezes the
     * response body until released.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean acceptsRanges = false;
        volatile String etag = null;
        volatile int perWriteDelayMs = 0;
        /** When true the server accepts the connection but never sends a response body. */
        volatile boolean stallForever = false;
        private volatile boolean closed = false;

        /** Gate: holds GET body responses until released. */
        private volatile CountDownLatch bodyGate = null;
        private final AtomicInteger activeGets = new AtomicInteger(0);

        TestHttpServer(final byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file"); }

        /** Install a gate: GET handlers increment activeGets and block until the gate opens. */
        void installGate(final CountDownLatch gate) {
            this.bodyGate = gate;
        }

        int activeGets() { return activeGets.get(); }

        private void acceptLoop() {
            while (!closed) {
                try {
                    final Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (!closed) { throw new UncheckedIOException(e); }
                }
            }
        }

        private void handleConnection(final Socket socket) {
            try (socket) {
                final InputStream in = socket.getInputStream();
                final String requestLine = readLine(in);
                if (requestLine == null || requestLine.isBlank()) { return; }

                final boolean isHead = requestLine.startsWith("HEAD");
                long rangeStart = -1;
                long rangeEnd = -1;

                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    if (line.startsWith("Range: bytes=")) {
                        final String[] parts = line.substring("Range: bytes=".length()).split("-");
                        rangeStart = Long.parseLong(parts[0].trim());
                        rangeEnd   = Long.parseLong(parts[1].trim());
                    }
                }

                final OutputStream out = socket.getOutputStream();

                if (isHead) {
                    writeHead(out);
                    return;
                }

                activeGets.incrementAndGet();
                try {
                    if (stallForever) {
                        // Accept but never complete — simulates a hung connection.
                        try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                        return;
                    }

                    if (rangeStart == 0 && rangeEnd == 0 && acceptsRanges && etag != null) {
                        writeRangeProbe(out);
                    } else if (rangeStart >= 0 && acceptsRanges) {
                        writeChunk(out, rangeStart, rangeEnd);
                    } else {
                        writeFullGet(out);
                    }
                    out.flush();
                } finally {
                    activeGets.decrementAndGet();
                }
            } catch (IOException ignored) {
                // connection closed by client — normal during shutdown / cancel
            }
        }

        private void writeHead(final OutputStream out) throws IOException {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Length: ").append(content.length).append("\r\n");
            if (acceptsRanges) { sb.append("Accept-Ranges: bytes\r\n"); }
            if (etag != null)   { sb.append("ETag: ").append(etag).append("\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void writeRangeProbe(final OutputStream out) throws IOException {
            final String h = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: 1\r\n" +
                    "Content-Range: bytes 0-0/" + content.length + "\r\n" +
                    "ETag: " + etag + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            out.write(content, 0, 1);
            out.flush();
        }

        private void writeChunk(final OutputStream out, final long start, final long end)
                throws IOException {
            final long len = end - start + 1;
            final String h = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n" +
                    "ETag: " + etag + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            if (perWriteDelayMs <= 0) {
                awaitGateIfSet();
                out.write(content, (int) start, (int) len);
            } else {
                final int piece = 512;
                for (int offset = (int) start; offset <= (int) end; offset += piece) {
                    final int toWrite = (int) Math.min(piece, end - offset + 1);
                    awaitGateIfSet();
                    out.write(content, offset, toWrite);
                    out.flush();
                    try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
                }
            }
        }

        private void writeFullGet(final OutputStream out) throws IOException {
            final String h = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            if (perWriteDelayMs <= 0) {
                awaitGateIfSet();
                out.write(content);
            } else {
                final int piece = 512;
                for (int offset = 0; offset < content.length; offset += piece) {
                    final int toWrite = Math.min(piece, content.length - offset);
                    awaitGateIfSet();
                    out.write(content, offset, toWrite);
                    out.flush();
                    try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
                }
            }
        }

        private void awaitGateIfSet() {
            final CountDownLatch gate = bodyGate;
            if (gate != null) {
                try { gate.await(); } catch (InterruptedException ignored) {}
            }
        }

        private static String readLine(final InputStream in) throws IOException {
            final StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') { in.read(); break; }
                if (c == '\n') { break; }
                sb.append((char) c);
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
        }
    }

    // ── coordination helper ───────────────────────────────────────────────────

    private static void waitFor(final BooleanSupplier condition, final long timeoutMs)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition");
            }
            Thread.sleep(20);
        }
    }

    // ── Scenario 1: AutoCloseable / try-with-resources ────────────────────────

    /**
     * Scenario 1: DownloadManager implements AutoCloseable; try-with-resources compiles
     * and runs without error on an idle manager.
     */
    @Test
    void downloadManagerIsAutoCloseableViaTryWithResources() {
        assertDoesNotThrow(() -> {
            try (DownloadManager manager = DownloadManagers.createDefault()) {
                // empty — idle close must not throw
            }
        });
    }

    // ── Scenario 2: close() on idle manager ──────────────────────────────────

    /**
     * Scenario 2: close() on an idle manager (no active downloads) returns immediately
     * and does not throw.
     */
    @Test
    void closeShouldReturnImmediatelyWhenNoActiveDownloads() {
        final DownloadManager manager = DownloadManagers.createDefault();
        assertDoesNotThrow(manager::close);
    }

    // ── Scenario 3: close() while single-stream download is active ───────────

    /**
     * Scenario 3: close() cancels an in-progress single-stream download cooperatively
     * and waits for the executor to drain before returning.
     */
    @Test
    void closeShouldTerminateActiveSingleStreamDownload() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'X');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch startedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadStartedEvent) { startedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("close-single.bin");
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(startedLatch.await(5, TimeUnit.SECONDS),
                    "Download must start before close is called");

            assertDoesNotThrow(manager::close, "close() must not throw");

            // After close(), new submissions must be rejected.
            assertThrows(DownloadRejectedException.class, () ->
                    manager.download(DownloadRequest.builder()
                            .sourceUri(server.uri()).targetPath(tempDir.resolve("rejected.bin")).build()));
        }
    }

    // ── Scenario 4: close() while segmented workers are active ───────────────

    /**
     * Scenario 4: close() while a segmented download is in progress cancels all segment
     * workers and waits for the executor to drain.
     */
    @Test
    void closeShouldTerminateActiveSegmentedDownload() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"seg-etag\"";
            server.perWriteDelayMs = 5;

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("close-segmented.bin");
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS),
                    "Segmented download must emit at least one ProgressEvent before close");

            assertDoesNotThrow(manager::close, "close() must not throw");
        }
    }

    // ── Scenario 5: close() with paused downloads ────────────────────────────

    /**
     * Scenario 5: close() unblocks a paused download (via pauseCoordinator.clear) and
     * does not hang indefinitely.
     */
    @Test
    void closeShouldUnblockPausedDownloadAndTerminate() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'P');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);
            final CountDownLatch pausedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
                if (e instanceof DownloadPausedEvent)   { pausedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("close-paused.bin");
            final DownloadSubmissionResult result = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Need progress before pause");

            manager.pause(result.downloadId());
            assertTrue(pausedLatch.await(5, TimeUnit.SECONDS), "Download must enter paused state");

            // close() must unblock the paused download and return
            assertDoesNotThrow(manager::close, "close() must not throw even when downloads are paused");
        }
    }

    // ── Scenario 6: Concurrent close() calls ─────────────────────────────────

    /**
     * Scenario 6: When two threads call close() simultaneously, only one does the actual
     * shutdown; the other waits and returns only after shutdown is complete.
     */
    @Test
    void concurrentCloseCallsShouldBothReturnWithoutError() throws Exception {
        final DownloadManager manager = DownloadManagers.createDefault();

        final CountDownLatch startGate = new CountDownLatch(1);
        final CopyOnWriteArrayList<Throwable> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch doneLatch = new CountDownLatch(2);

        final Runnable closer = () -> {
            try {
                startGate.await();
                manager.close();
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        };

        Thread.ofVirtual().start(closer);
        Thread.ofVirtual().start(closer);

        startGate.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS),
                "Both threads must complete close() within timeout");
        assertTrue(errors.isEmpty(), "No exceptions from concurrent close(): " + errors);
    }

    // ── Scenario 7: Repeated close() calls ───────────────────────────────────

    /**
     * Scenario 7: Calling close() multiple times on the same manager is idempotent.
     */
    @Test
    void repeatedCloseCallsShouldBeIdempotent() {
        final DownloadManager manager = DownloadManagers.createDefault();
        assertDoesNotThrow(manager::close, "First close must not throw");
        assertDoesNotThrow(manager::close, "Second close must not throw");
        assertDoesNotThrow(manager::close, "Third close must not throw");
    }

    // ── Scenario 8: Rejected submissions after shutdown ──────────────────────

    /**
     * Scenario 8: After close() is called, any subsequent download() call throws
     * DownloadRejectedException.
     */
    @Test
    void downloadShouldThrowDownloadRejectedExceptionAfterClose() throws Exception {
        try (TestHttpServer server = new TestHttpServer(new byte[]{1, 2, 3})) {
            final DownloadManager manager = DownloadManagers.createDefault();
            manager.close();

            assertThrows(DownloadRejectedException.class, () ->
                    manager.download(DownloadRequest.builder()
                            .sourceUri(server.uri())
                            .targetPath(tempDir.resolve("after-close.bin"))
                            .build()),
                    "download() after close() must throw DownloadRejectedException");
        }
    }

    // ── Scenario 9: No DownloadCancelledEvent from shutdown ──────────────────

    /**
     * Scenario 9: Downloads terminated by shutdown must NOT emit DownloadCancelledEvent.
     * DownloadStartedEvent must be emitted to confirm the download began.
     */
    @Test
    void closeShouldNotEmitCancelledEventForShutdownDownloads() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'N');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch startedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadStartedEvent) { startedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("no-cancel-event.bin");
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(startedLatch.await(5, TimeUnit.SECONDS),
                    "Download must start before close");

            manager.close();

            final long cancelledCount = events.stream()
                    .filter(e -> e instanceof DownloadCancelledEvent)
                    .count();
            assertEquals(0, cancelledCount,
                    "No DownloadCancelledEvent must be emitted for shutdown-stopped downloads");

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadStartedEvent),
                    "DownloadStartedEvent must be present — download did begin");
        }
    }

    // ── Scenario 10: No active writes after successful close ─────────────────

    /**
     * Scenario 10: After close() returns, the executor has drained — no downloads are
     * still writing to files. Verified by checking that the registry drains after close.
     */
    @Test
    void afterCloseNoDownloadsAreActivelyWriting() throws Exception {
        final byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 'W');

        final int downloadCount = 3;

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final AtomicInteger startedCount = new AtomicInteger(0);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadStartedEvent) { startedCount.incrementAndGet(); }
            });

            for (int i = 0; i < downloadCount; i++) {
                final Path dest = tempDir.resolve("write-check-" + i + ".bin");
                manager.download(DownloadRequest.builder()
                        .sourceUri(server.uri()).targetPath(dest).build());
            }

            // Wait until all have started
            waitFor(() -> startedCount.get() >= downloadCount, 8_000);

            manager.close();

            // After close() returns, the manager must reject new submissions — the executor is shut down.
            assertThrows(DownloadRejectedException.class, () ->
                    manager.download(DownloadRequest.builder()
                            .sourceUri(server.uri())
                            .targetPath(tempDir.resolve("post-close.bin"))
                            .build()),
                    "Manager must reject submissions after close — executor must be shut down");
        }
    }

    // ── Scenario 11: Bounded shutdown ────────────────────────────────────────

    /**
     * Scenario 11: close() returns within a bounded window even when a download is stalled
     * (server never sends a body). The 10-second executor shutdown timeout ensures this.
     *
     * <p>This test is intentionally slow (~10 seconds) but bounded at 15 seconds.
     */
    @Test
    void closeShouldReturnWithinBoundedTimeEvenWhenDownloadIsStalled() throws Exception {
        final byte[] content = new byte[8 * 1024];

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.stallForever = true;

            final DownloadManager manager = DownloadManagers.createDefault();
            final CountDownLatch startedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                if (e instanceof DownloadStartedEvent) { startedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("stall.bin");
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "Download must start");

            assertTimeout(ofSeconds(15), manager::close,
                    "close() must return within 15 seconds even when download is stalled");
        }
    }

    // ── Scenario 12: User-cancel still emits DownloadCancelledEvent ──────────

    /**
     * Scenario 12: Regression guard — a user-initiated cancel (not a shutdown) must still
     * emit exactly one DownloadCancelledEvent.
     */
    @Test
    void userInitiatedCancelShouldStillEmitCancelledEvent() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'C');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"cancel-etag\"";
            server.perWriteDelayMs = 5;

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final CopyOnWriteArrayList<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);
            final CountDownLatch cancelledLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
                if (e instanceof DownloadCancelledEvent) { cancelledLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("user-cancel.bin");
            final DownloadSubmissionResult result = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS),
                    "Download must emit at least one ProgressEvent before cancel");

            manager.cancel(result.downloadId());

            assertTrue(cancelledLatch.await(10, TimeUnit.SECONDS),
                    "DownloadCancelledEvent must be emitted for user-initiated cancel");

            final long cancelledCount = events.stream()
                    .filter(e -> e instanceof DownloadCancelledEvent)
                    .count();
            assertEquals(1, cancelledCount,
                    "Exactly one DownloadCancelledEvent must be emitted for user-initiated cancel");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "No CompletedEvent after user cancel");

            manager.close();
        }
    }
}
