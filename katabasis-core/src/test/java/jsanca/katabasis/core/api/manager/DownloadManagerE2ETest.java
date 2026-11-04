package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.1-T09 — HTTP end-to-end integration tests through the DownloadManager public API.
 *
 * <p>All tests drive downloads through {@link DownloadManagers#createDefault(DownloadConfig)}
 * against a real in-process HTTP server. Neither strategies, the orchestrator, nor the retry
 * executor are mocked or bypassed.
 *
 * <p>Coverage matrix row for each test method maps it to a T09 scenario category.
 */
class DownloadManagerE2ETest {

    @TempDir
    Path tempDir;

    // ── in-process HTTP server ────────────────────────────────────────────────

    /**
     * HTTP/1.1 test server for DownloadManager end-to-end tests.
     *
     * <p>Handles the full probe + download sequence:
     * <ul>
     *   <li>HEAD — returns Content-Length plus optional Accept-Ranges and ETag.</li>
     *   <li>Range probe (bytes=0-0) — 206 with Content-Range + ETag (when {@code acceptsRanges}
     *       and {@code etag} are set).</li>
     *   <li>Chunk GET — 206 with correct Content-Range and optional throttle.</li>
     *   <li>Full GET — 200 with body, optional throttle, optional body stall after first byte.</li>
     * </ul>
     *
     * <p>{@code failFirstNNonHeadRequests} injects error responses (the configured status code)
     * for the first N non-HEAD requests. Use this to simulate transient server failures.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean acceptsRanges = false;
        volatile String etag = null;
        volatile int perWriteDelayMs = 0;
        volatile boolean stallBodyAfterFirstByte = false;
        volatile int failFirstNNonHeadRequests = 0;
        volatile int failStatusCode = 503;
        private final AtomicInteger nonHeadRequestCount = new AtomicInteger(0);
        private volatile boolean closed = false;

        TestHttpServer(final byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file"); }

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

                // Count and optionally fail non-HEAD requests
                final int nonHeadReq = nonHeadRequestCount.getAndIncrement();
                if (nonHeadReq < failFirstNNonHeadRequests) {
                    writeError(out, failStatusCode, "Temporary Error");
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

            } catch (IOException ignored) {
                // connection closed by client — normal during cancellation and timeout tests
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
                out.write(content, (int) start, (int) len);
            } else {
                final int piece = 512;
                for (int offset = (int) start; offset <= (int) end; offset += piece) {
                    final int toWrite = (int) Math.min(piece, end - offset + 1);
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
            if (stallBodyAfterFirstByte) {
                out.write(content[0]);
                out.flush();
                // Block until the connection is closed by the watchdog, allowing the test to continue.
                try { Thread.sleep(60_000); } catch (InterruptedException ignored) {}
                return;
            }
            if (perWriteDelayMs <= 0) {
                out.write(content);
            } else {
                final int piece = 512;
                for (int offset = 0; offset < content.length; offset += piece) {
                    final int toWrite = Math.min(piece, content.length - offset);
                    out.write(content, offset, toWrite);
                    out.flush();
                    try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
                }
            }
        }

        private static void writeError(final OutputStream out, final int status,
                                        final String msg) throws IOException {
            final String r = "HTTP/1.1 " + status + " " + msg + "\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(r.getBytes(StandardCharsets.US_ASCII));
            out.flush();
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

    // ── coordination helpers ──────────────────────────────────────────────────

    private static void awaitCondition(final BooleanSupplier cond, final long timeoutMs,
                                        final String description) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline,
                    "Timed out waiting for: " + description);
            Thread.sleep(20);
        }
    }

    // ── Scenario A — single-stream success (covered in DownloadManagerTest) ──

    // ── Scenario B — segmented success ───────────────────────────────────────

    /**
     * T09-B: Segmented download via the DownloadManager public API.
     *
     * <p>Proves the full stack: DownloadManager → orchestrator probe → range capability →
     * SegmentedHttpDownloadStrategy → correct output assembly.
     */
    @Test
    void downloadManagerShouldCompleteSegmentedDownload() throws Exception {
        final byte[] content = new byte[32 * 1024];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"e2e-etag-v1\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("segmented-dm.bin");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(completedLatch.await(15, TimeUnit.SECONDS),
                    "Timed out waiting for segmented download to complete");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count(),
                    "Exactly one CompletedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "No FailedEvent");
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "Segmented output must match source bytes exactly");
        }
    }

    // ── Scenario C — range fallback ───────────────────────────────────────────

    /**
     * T09-C: Content below segmentMinSizeBytes falls back to single-stream via DownloadManager.
     *
     * <p>Server advertises ranges and a strong ETag but content is below the threshold,
     * so the orchestrator selects single-stream. Verifies fallback completes correctly.
     */
    @Test
    void downloadManagerShouldFallBackToSingleStreamWhenContentBelowSegmentThreshold() throws Exception {
        final byte[] content = "tiny payload below threshold".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"tiny-etag\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(Long.MAX_VALUE)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("fallback-threshold-dm.txt");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS));

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count());
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    /**
     * T09-C: Server without Accept-Ranges forces single-stream via DownloadManager.
     *
     * <p>Verifies that the orchestrator correctly skips range probing when HEAD does not
     * include Accept-Ranges, and that the single-stream path completes correctly.
     */
    @Test
    void downloadManagerShouldUseSingleStreamWhenServerDoesNotAdvertiseRanges() throws Exception {
        final byte[] content = "no ranges advertised".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            // acceptsRanges = false (default) — no Accept-Ranges header

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)  // would trigger segmented if server supported ranges
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("no-ranges-dm.txt");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS));

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count());
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Scenario D — transient failure and retry ──────────────────────────────

    /**
     * T09-D: Transient 503 failure followed by success via DownloadManager.
     *
     * <p>Per-request retryConfig overrides the global no-retry default.
     * Verifies that the retry path produces exactly one CompletedEvent and correct output.
     */
    @Test
    void downloadManagerShouldRetryAndCompleteAfterTransientFailure() throws Exception {
        final byte[] content = "retry content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.failFirstNNonHeadRequests = 1;  // first GET → 503; second → 200
            server.failStatusCode = 503;

            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("retry-success-dm.txt");
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri())
                    .targetPath(dest)
                    .retryConfig(RetryConfig.fixedDelay(1, Duration.ofMillis(50)))
                    .build());

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS),
                    "Download must complete after one retry");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count());
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Scenario E — retry exhaustion ────────────────────────────────────────

    /**
     * T09-E: All retry attempts exhausted → exactly one DownloadFailedEvent via DownloadManager.
     */
    @Test
    void downloadManagerShouldEmitExactlyOneFailedEventAfterRetryExhaustion() throws Exception {
        try (TestHttpServer server = new TestHttpServer(new byte[0])) {
            server.failFirstNNonHeadRequests = 100;  // always fail
            server.failStatusCode = 503;

            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch failedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadFailedEvent) { failedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("exhaustion-dm.bin");
            // 2 retries = 3 total attempts, all fail
            manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri())
                    .targetPath(dest)
                    .retryConfig(RetryConfig.fixedDelay(2, Duration.ofMillis(10)))
                    .build());

            assertTrue(failedLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadFailedEvent after retry exhaustion");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadFailedEvent).count(),
                    "Exactly one DownloadFailedEvent on retry exhaustion");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    // ── Scenario F — HTTP timeout ─────────────────────────────────────────────

    /**
     * T09-F: Body stall triggers read inactivity timeout via DownloadManager.
     *
     * <p>Server sends headers and one byte then blocks indefinitely.
     * With a 300 ms inactivity timeout, the watchdog fires, closes the stream,
     * and the download fails within a bounded window.
     * Proves that {@link HttpTimeoutConfig#readInactivityTimeout()} propagates from
     * {@link DownloadConfig} through the full stack.
     */
    @Test
    void downloadManagerShouldFailWhenBodyStallsAndReadInactivityTimeoutExpires() throws Exception {
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'T');

        try (TestHttpServer server = new TestHttpServer(content)) {
            // No Accept-Ranges: single-stream only; stall after first byte
            server.stallBodyAfterFirstByte = true;

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .httpTimeoutConfig(HttpTimeoutConfig.builder()
                            .readInactivityTimeout(Duration.ofMillis(300))
                            .build())
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch failedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadFailedEvent) { failedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("timeout-dm.bin");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(failedLatch.await(8, TimeUnit.SECONDS),
                    "Download must fail within 8 s when body stalls with 300 ms inactivity timeout");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadFailedEvent).count(),
                    "Exactly one DownloadFailedEvent on timeout");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    // ── Scenario H — cancellation ────────────────────────────────────────────

    /**
     * T09-H: Cancel a segmented download via the DownloadManager public API.
     *
     * <p>Proves that {@link DownloadManager#cancel(String)} propagates to the task,
     * the DownloadCancelledEvent is emitted, and no CompletedEvent follows.
     */
    @Test
    void downloadManagerShouldCancelSegmentedDownloadAndEmitCancelledEvent() throws Exception {
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
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);
            final CountDownLatch cancelledLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
                if (e instanceof DownloadCancelledEvent) { cancelledLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("cancel-seg-dm.bin");
            final DownloadSubmissionResult result = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for first progress event");
            manager.cancel(result.downloadId());

            assertTrue(cancelledLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadCancelledEvent");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCancelledEvent).count(),
                    "Exactly one CancelledEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "No CompletedEvent after cancellation");
            // Segmented partial-file policy: file is deleted on cancellation
            assertFalse(Files.exists(dest),
                    "Segmented strategy must delete partial file on cancellation");
        }
    }

    // ── Scenario I — pause/resume ─────────────────────────────────────────────

    /**
     * T09-I: Pause and resume a segmented download via the DownloadManager public API.
     *
     * <p>Proves that {@link DownloadManager#pause(String)} and {@link DownloadManager#resume(String)}
     * are forwarded through the PauseCoordinator, the download completes, and output is correct.
     */
    @Test
    void downloadManagerShouldPauseAndResumeSegmentedDownloadToCompletion() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'P');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"pause-etag\"";
            server.perWriteDelayMs = 5;

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final AtomicInteger progressCount = new AtomicInteger(0);
            final CountDownLatch pausedLatch  = new CountDownLatch(1);
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressCount.incrementAndGet(); }
                if (e instanceof DownloadPausedEvent)   { pausedLatch.countDown(); }
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("pause-seg-dm.bin");
            final DownloadSubmissionResult result = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            awaitCondition(() -> progressCount.get() >= 1, 10_000, "first progress event");
            manager.pause(result.downloadId());

            assertTrue(pausedLatch.await(5, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadPausedEvent");
            manager.resume(result.downloadId());

            assertTrue(completedLatch.await(20, TimeUnit.SECONDS),
                    "Timed out waiting for completion after resume");

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadPausedEvent).count(),
                    "Exactly one PausedEvent");
            assertEquals(1, events.stream().filter(e -> e instanceof DownloadResumedEvent).count(),
                    "Exactly one ResumedEvent");
            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count(),
                    "Exactly one CompletedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "File must be complete and correct after pause/resume");
        }
    }

    // ── Scenario J — terminal event uniqueness ────────────────────────────────

    /**
     * T09-J: Segmented integrity failure via DownloadManager produces exactly one terminal event.
     *
     * <p>Server returns a changed ETag in chunk responses after the probe phase completes
     * with the original ETag. The ResourceIntegrityException propagates as a single
     * DownloadFailedEvent with no CompletedEvent.
     */
    @Test
    void downloadManagerShouldEmitExactlyOneTerminalEventOnSegmentedIntegrityFailure() throws Exception {
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'Z');

        // Server: probe uses ETag "v1"; chunk responses return ETag "v2" → ResourceIntegrityException
        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");

            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final InputStream in = socket.getInputStream();
                                final String line = readLine(in);
                                if (line == null || line.isBlank()) { return; }
                                final boolean isHead = line.startsWith("HEAD");
                                long rs = -1, re = -1;
                                String h;
                                while (!(h = readLine(in)).isEmpty()) {
                                    if (h.startsWith("Range: bytes=")) {
                                        final String[] p = h.substring("Range: bytes=".length()).split("-");
                                        rs = Long.parseLong(p[0].trim());
                                        re = Long.parseLong(p[1].trim());
                                    }
                                }
                                final OutputStream out = socket.getOutputStream();
                                if (isHead) {
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                } else if (rs == 0 && re == 0) {
                                    writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\n"
                                            + "Content-Range: bytes 0-0/" + content.length
                                            + "\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                    out.write(content, 0, 1);
                                } else if (rs >= 0) {
                                    final long len = re - rs + 1;
                                    writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                                            + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + content.length
                                            + "\r\nETag: \"v2\"\r\nConnection: close\r\n\r\n");
                                    out.write(content, (int) rs, (int) len);
                                }
                                out.flush();
                            } catch (IOException ignored) {}
                        });
                    } catch (IOException e) {
                        if (!ss.isClosed()) { throw new UncheckedIOException(e); }
                    }
                }
            });

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch failedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                events.add(e);
                if (e instanceof DownloadFailedEvent) { failedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("integrity-dm.bin");
            manager.download(DownloadRequest.builder().sourceUri(uri).targetPath(dest).build());

            assertTrue(failedLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadFailedEvent on integrity failure");

            final long terminalCount = events.stream()
                    .filter(e -> e instanceof DownloadCompletedEvent
                            || e instanceof DownloadFailedEvent
                            || e instanceof DownloadCancelledEvent)
                    .count();

            assertEquals(1, terminalCount, "Exactly one terminal event on integrity failure");
            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    // ── shared server utilities ───────────────────────────────────────────────

    private static void writeRaw(final OutputStream out, final String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.US_ASCII));
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
}
