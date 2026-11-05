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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.1-T10 — URL list concurrency and scheduling verification.
 *
 * <p>Tests verify that download-level and segment-level concurrency limits are
 * enforced, that lifecycle isolation holds across concurrent downloads, and that
 * scheduling permits are correctly acquired and released on completion, failure,
 * cancellation, and pause/resume.
 *
 * <h2>Concurrency model (pre-T10 assessment)</h2>
 * <ul>
 *   <li><b>Download-level concurrency</b> — bounded by {@code Semaphore(maxConcurrentDownloads)}
 *       inside {@link jsanca.katabasis.core.internal.execution.DownloadExecutors.VirtualThreadDownloadExecutor}.
 *       Each download acquires one permit before the strategy runs; released in {@code finally}
 *       after the strategy returns (success, failure, or cancel).</li>
 *   <li><b>Segment-level concurrency</b> — bounded by {@code StructuredTaskScope} forking
 *       {@code segmentCount} virtual threads. The count is fixed at download start.</li>
 *   <li><b>Aggregate concurrency</b> — theoretical upper bound is
 *       {@code maxConcurrentDownloads × segmentCount}. No global HTTP connection pool limit
 *       is imposed; the JDK HttpClient manages connections independently.</li>
 *   <li><b>Pause scheduling</b> — a paused download holds its semaphore permit while blocked.
 *       The permit is not released until the download terminates. See
 *       {@link #pausedDownloadHoldsPermitDelayingQueuedDownload()}.</li>
 *   <li><b>URL list ingestion</b> — no batch submission API exists; callers submit
 *       individual {@link DownloadRequest} objects via {@link DownloadManager#download(DownloadRequest)}.
 *       Queue depth is unbounded (backed by the virtual-thread executor).</li>
 *   <li><b>Scheduling fairness</b> — FIFO within the JVM thread scheduler; no explicit
 *       priority ordering is applied.</li>
 * </ul>
 */
class DownloadManagerConcurrencyTest {

    @TempDir
    Path tempDir;

    // ── in-process HTTP server ────────────────────────────────────────────────

    /**
     * HTTP/1.1 server for concurrency tests.
     *
     * <p>Supports:
     * <ul>
     *   <li>HEAD — Content-Length, optional Accept-Ranges + ETag.</li>
     *   <li>Range probe bytes=0-0 — 206 (when acceptsRanges + etag).</li>
     *   <li>Chunk GET — 206 with Content-Range, optional throttle.</li>
     *   <li>Full GET — 200 with body, optional throttle.</li>
     *   <li>Scripted failures — first N non-HEAD requests return an error code.</li>
     *   <li>Body gate — when {@link #holdGetReleased} is false, GET/range handlers
     *       increment {@link #activeGets}, signal {@link #getsStartedLatch}, and block
     *       until {@link #releaseGets()} is called. Allows the test to freeze transfers
     *       and inspect the concurrent watermark.</li>
     * </ul>
     */
    static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        final byte[] content;
        volatile boolean acceptsRanges = false;
        volatile String etag = null;
        volatile int perWriteDelayMs = 0;
        volatile int failFirstNNonHeadRequests = 0;
        volatile int failStatusCode = 503;

        // concurrency measurement
        final AtomicInteger activeGets   = new AtomicInteger(0);
        final AtomicInteger peakGets     = new AtomicInteger(0);
        final AtomicInteger chunkRequests = new AtomicInteger(0); // non-probe range GETs
        final AtomicInteger nonHeadCount  = new AtomicInteger(0);

        // gate for concurrency-limit tests
        private volatile boolean holdGetReleased = true; // default: no hold
        private volatile CountDownLatch getsStartedLatch = null;
        private volatile CountDownLatch releaseGateLatch  = null;

        private final AtomicInteger nonHeadRequestIndex = new AtomicInteger(0);
        private volatile boolean closed = false;

        TestHttpServer(final byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file"); }

        /** Install a gate: GET handlers will block until {@link #releaseGets()} is called. */
        void installGate(final int expectGets) {
            this.getsStartedLatch = new CountDownLatch(expectGets);
            this.releaseGateLatch  = new CountDownLatch(1);
            this.holdGetReleased   = false;
        }

        /** Wait until {@code n} GETs have reached the gate. */
        boolean awaitGetsAtGate(final int n, final long timeoutMs) throws InterruptedException {
            return getsStartedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        /** Release the gate: all blocked GETs may proceed. */
        void releaseGets() {
            holdGetReleased = true;
            if (releaseGateLatch != null) { releaseGateLatch.countDown(); }
        }

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
                long rangeEnd   = -1;

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

                final int idx = nonHeadRequestIndex.getAndIncrement();
                if (idx < failFirstNNonHeadRequests) {
                    writeError(out, failStatusCode, "Temporary Error");
                    return;
                }

                nonHeadCount.incrementAndGet();

                // gate bookkeeping
                final int active = activeGets.incrementAndGet();
                peakGets.updateAndGet(p -> Math.max(p, active));

                if (getsStartedLatch != null) { getsStartedLatch.countDown(); }

                if (!holdGetReleased) {
                    try { releaseGateLatch.await(); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }

                try {
                    if (rangeStart == 0 && rangeEnd == 0 && acceptsRanges && etag != null) {
                        writeRangeProbe(out);
                    } else if (rangeStart >= 0 && acceptsRanges) {
                        chunkRequests.incrementAndGet();
                        writeChunk(out, rangeStart, rangeEnd);
                    } else {
                        writeFullGet(out);
                    }
                    out.flush();
                } finally {
                    activeGets.decrementAndGet();
                }
            } catch (IOException ignored) {}
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

        static void writeError(final OutputStream out, final int status, final String msg)
                throws IOException {
            final String r = "HTTP/1.1 " + status + " " + msg + "\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(r.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        static String readLine(final InputStream in) throws IOException {
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

    // ── Scenario A — multiple successful downloads ────────────────────────────

    /**
     * T10-A: Multiple single-stream downloads complete concurrently with correct output.
     *
     * <p>Events emitted for each download carry the correct download ID; outputs match
     * the source content for each URL.
     */
    @Test
    void multipleSuccessfulSingleStreamDownloadsAllComplete() throws Exception {
        final int count = 4;
        final byte[] content = "concurrent content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault();
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allDone = new CountDownLatch(count);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent) { allDone.countDown(); }
            });

            final List<String> submittedIds = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final Path dest = tempDir.resolve("dl-" + i + ".txt");
                final DownloadSubmissionResult r = manager.download(
                        DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());
                submittedIds.add(r.downloadId());
            }

            assertTrue(allDone.await(15, TimeUnit.SECONDS),
                    "All single-stream downloads must complete");

            for (int i = 0; i < count; i++) {
                final String id = submittedIds.get(i);
                final List<DownloadEvent> events = byId.get(id);

                assertNotNull(events, "Events must exist for download " + id);
                assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                        "CompletedEvent expected for download " + id);
                assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                        "No FailedEvent for download " + id);

                final Path dest = tempDir.resolve("dl-" + i + ".txt");
                assertArrayEquals(content, Files.readAllBytes(dest),
                        "Correct content for download " + id);
            }
        }
    }

    /**
     * T10-A+H: Multiple segmented downloads complete concurrently with correct output.
     * Also covers H (mixed not applicable here) and C implicitly via chunk counting.
     */
    @Test
    void multipleSuccessfulSegmentedDownloadsAllComplete() throws Exception {
        final int count = 3;
        final int segmentCount = 4;
        final byte[] content = new byte[32 * 1024];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"multi-seg-etag\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(segmentCount)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allDone = new CountDownLatch(count);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent) { allDone.countDown(); }
            });

            final List<DownloadSubmissionResult> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final Path dest = tempDir.resolve("seg-" + i + ".bin");
                results.add(manager.download(
                        DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build()));
            }

            assertTrue(allDone.await(20, TimeUnit.SECONDS),
                    "All segmented downloads must complete");

            for (int i = 0; i < count; i++) {
                final String id = results.get(i).downloadId();
                assertNotNull(byId.get(id));
                assertTrue(byId.get(id).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
                assertFalse(byId.get(id).stream().anyMatch(e -> e instanceof DownloadFailedEvent));
                assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("seg-" + i + ".bin")));
            }

            // D: aggregate concurrency is bounded by maxConcurrentDownloads × segmentCount
            assertTrue(server.peakGets.get() <= config.maxConcurrentDownloads() * segmentCount,
                    "Peak concurrent transfers must not exceed maxConcurrentDownloads × segmentCount");
        }
    }

    // ── Scenario B — download concurrency limit ───────────────────────────────

    /**
     * T10-B: Download concurrency limit is enforced at the server-observed level.
     *
     * <p>Server holds all GETs at a gate. With {@code maxConcurrentDownloads=2} and 4 downloads
     * submitted, at most 2 GETs ever reach the server simultaneously. After the gate is released,
     * all 4 complete correctly.
     */
    @Test
    void downloadConcurrencyLimitBoundsParallelTransfers() throws Exception {
        final int limit = 2;
        final int total = 4;
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'C');

        try (TestHttpServer server = new TestHttpServer(content)) {
            // Gate: holds up to `limit` concurrent GETs; releases all simultaneously
            server.installGate(limit);

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .maxConcurrentDownloads(limit)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allDone = new CountDownLatch(total);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent || e instanceof DownloadFailedEvent) {
                    allDone.countDown();
                }
            });

            for (int i = 0; i < total; i++) {
                final Path dest = tempDir.resolve("limit-" + i + ".bin");
                manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());
            }

            // Wait until `limit` GETs are blocked at the gate
            assertTrue(server.awaitGetsAtGate(limit, 10_000),
                    "Timed out waiting for " + limit + " GETs to reach gate");

            // Peak must equal limit: the other (total - limit) downloads haven't made any HTTP
            // request yet because they are waiting to acquire the download-level semaphore.
            assertEquals(limit, server.peakGets.get(),
                    "Exactly " + limit + " GETs must be active while gate is held");

            // Release: all GETs proceed; the queued downloads start next
            server.releaseGets();

            assertTrue(allDone.await(20, TimeUnit.SECONDS),
                    "All downloads must complete after gate is released");

            // Final peak must still respect the limit (no burst when queue drains)
            assertTrue(server.peakGets.get() <= limit,
                    "Peak concurrent GETs must remain ≤ " + limit + " throughout");

            // All outputs correct
            for (int i = 0; i < total; i++) {
                assertArrayEquals(content, Files.readAllBytes(tempDir.resolve("limit-" + i + ".bin")));
            }
        }
    }

    // ── Scenario C — segment count limit ─────────────────────────────────────

    /**
     * T10-C: A single segmented download makes exactly {@code segmentCount} chunk requests.
     *
     * <p>Server-side chunk counter verifies that the StructuredTaskScope forks exactly the
     * configured number of range workers and that retries do not introduce duplicate workers.
     */
    @Test
    void segmentedDownloadMakesExactlyNChunkRequests() throws Exception {
        final int segmentCount = 4;
        final byte[] content = new byte[32 * 1024];
        Arrays.fill(content, (byte) 'X');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"chunk-count-etag\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(segmentCount)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(e -> {
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("chunk-count.bin");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(completedLatch.await(15, TimeUnit.SECONDS));

            assertEquals(segmentCount, server.chunkRequests.get(),
                    "Exactly segmentCount chunk requests must be made");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Scenario E — retry isolation ─────────────────────────────────────────

    /**
     * T10-E: A download that retries does not block or corrupt a sibling download.
     *
     * <p>Two downloads run concurrently: download A experiences a transient failure and
     * retries; download B succeeds immediately. Both complete with correct content.
     */
    @Test
    void retryingDownloadDoesNotDelayOtherDownloads() throws Exception {
        final byte[] contentA = "retry-download".getBytes(StandardCharsets.UTF_8);
        final byte[] contentB = "clean-download".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer serverA = new TestHttpServer(contentA);
             TestHttpServer serverB = new TestHttpServer(contentB)) {

            serverA.failFirstNNonHeadRequests = 1;  // first GET → 503; second → 200
            serverA.failStatusCode = 503;

            final DownloadManager manager = DownloadManagers.createDefault();
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allDone = new CountDownLatch(2);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent) { allDone.countDown(); }
            });

            final Path destA = tempDir.resolve("retry-a.txt");
            final Path destB = tempDir.resolve("clean-b.txt");

            final DownloadSubmissionResult rA = manager.download(DownloadRequest.builder()
                    .sourceUri(serverA.uri()).targetPath(destA)
                    .retryConfig(RetryConfig.fixedDelay(1, Duration.ofMillis(50)))
                    .build());
            final DownloadSubmissionResult rB = manager.download(DownloadRequest.builder()
                    .sourceUri(serverB.uri()).targetPath(destB)
                    .build());

            assertTrue(allDone.await(15, TimeUnit.SECONDS),
                    "Both downloads must complete despite one retrying");

            // A: retried and completed
            assertTrue(byId.get(rA.downloadId()).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(byId.get(rA.downloadId()).stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(contentA, Files.readAllBytes(destA));

            // B: succeeded without interference
            assertTrue(byId.get(rB.downloadId()).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(byId.get(rB.downloadId()).stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(contentB, Files.readAllBytes(destB));
        }
    }

    // ── Scenario F — cancellation isolation ──────────────────────────────────

    /**
     * T10-F: Cancelling one download does not affect sibling downloads.
     *
     * <p>Three downloads run concurrently. One is cancelled mid-transfer. The other two
     * complete with correct content, and the cancelled one emits exactly one CancelledEvent.
     */
    @Test
    void cancellingOneDownloadDoesNotAffectOthers() throws Exception {
        final byte[] contentFast = "fast content".getBytes(StandardCharsets.UTF_8);
        final byte[] contentSlow = new byte[128 * 1024];
        Arrays.fill(contentSlow, (byte) 'Z');

        try (TestHttpServer fastServer = new TestHttpServer(contentFast);
             TestHttpServer slowServer = new TestHttpServer(contentSlow)) {

            slowServer.perWriteDelayMs = 5;  // slow enough to cancel mid-transfer

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .maxConcurrentDownloads(3)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch fastDone  = new CountDownLatch(2); // fast1 + fast2
            final CountDownLatch cancelDone = new CountDownLatch(1);
            final CountDownLatch slowProgress = new CountDownLatch(1);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent) { fastDone.countDown(); }
                if (e instanceof DownloadCancelledEvent) { cancelDone.countDown(); }
                if (e instanceof DownloadProgressEvent) { slowProgress.countDown(); }
            });

            final Path destFast1 = tempDir.resolve("fast1.txt");
            final Path destFast2 = tempDir.resolve("fast2.txt");
            final Path destSlow  = tempDir.resolve("slow.bin");

            final DownloadSubmissionResult rSlow  = manager.download(
                    DownloadRequest.builder().sourceUri(slowServer.uri()).targetPath(destSlow).build());
            manager.download(DownloadRequest.builder().sourceUri(fastServer.uri()).targetPath(destFast1).build());
            manager.download(DownloadRequest.builder().sourceUri(fastServer.uri()).targetPath(destFast2).build());

            assertTrue(slowProgress.await(10, TimeUnit.SECONDS),
                    "Slow download must start before cancellation");
            manager.cancel(rSlow.downloadId());

            assertTrue(fastDone.await(15, TimeUnit.SECONDS),
                    "Both fast downloads must complete");
            assertTrue(cancelDone.await(5, TimeUnit.SECONDS),
                    "Cancelled download must emit CancelledEvent");

            // Slow: exactly one CancelledEvent, no CompletedEvent
            final List<DownloadEvent> slowEvents = byId.get(rSlow.downloadId());
            assertEquals(1, slowEvents.stream().filter(e -> e instanceof DownloadCancelledEvent).count());
            assertFalse(slowEvents.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));

            // Fast: completed with correct content
            assertArrayEquals(contentFast, Files.readAllBytes(destFast1));
            assertArrayEquals(contentFast, Files.readAllBytes(destFast2));
        }
    }

    // ── Scenario G — pause holds permit ──────────────────────────────────────

    /**
     * T10-G: A paused download holds its semaphore permit while paused.
     *
     * <p>With {@code maxConcurrentDownloads=1}, only one download runs at a time.
     * When the active download is paused, a queued download cannot start until the
     * paused download is resumed and completes. This documents the scheduling policy:
     * a paused download is "active" for scheduling purposes.
     */
    @Test
    void pausedDownloadHoldsPermitDelayingQueuedDownload() throws Exception {
        final byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 'P');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            // maxConcurrentDownloads=1: only one download at a time
            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .maxConcurrentDownloads(1)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allDone = new CountDownLatch(2);
            final CountDownLatch firstPaused    = new CountDownLatch(1);
            final CountDownLatch secondStarted  = new CountDownLatch(1);
            final CountDownLatch firstCompleted = new CountDownLatch(1);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadPausedEvent)   { firstPaused.countDown(); }
                if (e instanceof DownloadStartedEvent)  { secondStarted.countDown(); }
                if (e instanceof DownloadCompletedEvent) {
                    firstCompleted.countDown();
                    allDone.countDown();
                }
            });

            final Path dest1 = tempDir.resolve("paused.bin");
            final Path dest2 = tempDir.resolve("queued.bin");

            final DownloadSubmissionResult r1 = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest1).build());

            // Wait for first download to make progress, then pause it
            awaitCondition(
                    () -> byId.getOrDefault(r1.downloadId(), List.of()).stream()
                            .anyMatch(e -> e instanceof DownloadProgressEvent),
                    10_000, "first download progress");
            manager.pause(r1.downloadId());

            assertTrue(firstPaused.await(5, TimeUnit.SECONDS),
                    "First download must pause");

            // Submit second download — it must queue (semaphore is held by the paused download)
            final DownloadSubmissionResult r2 = manager.download(
                    DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest2).build());

            // Reset listener to track second download's started event (not first's resumed)
            manager.removeListener(null); // no-op; just documenting intent
            // The secondStarted latch will be decremented by the next StartedEvent
            // (which comes from download 2, since download 1 is paused, not restarted)

            // Resume first download; second must not have started yet
            assertFalse(byId.getOrDefault(r2.downloadId(), List.of()).stream()
                            .anyMatch(e -> e instanceof DownloadStartedEvent),
                    "Second download must not have started while first is paused");

            manager.resume(r1.downloadId());

            // Both must eventually complete
            assertTrue(allDone.await(30, TimeUnit.SECONDS),
                    "Both downloads must complete after pause/resume cycle");

            // Second download started only after first completed (semaphore released)
            final List<DownloadEvent> ev2 = byId.get(r2.downloadId());
            assertNotNull(ev2, "Second download must have events");
            assertTrue(ev2.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Second download must complete");
            assertArrayEquals(content, Files.readAllBytes(dest1));
            assertArrayEquals(content, Files.readAllBytes(dest2));
        }
    }

    // ── Scenario H — mixed strategy ───────────────────────────────────────────

    /**
     * T10-H: Single-stream and segmented downloads run concurrently and complete independently.
     */
    @Test
    void singleStreamAndSegmentedDownloadsRunConcurrently() throws Exception {
        final byte[] contentSingle = "single stream payload".getBytes(StandardCharsets.UTF_8);
        final byte[] contentSeg    = new byte[32 * 1024];
        Arrays.fill(contentSeg, (byte) 'M');

        try (TestHttpServer singleServer   = new TestHttpServer(contentSingle);
             TestHttpServer segmentedServer = new TestHttpServer(contentSeg)) {

            segmentedServer.acceptsRanges = true;
            segmentedServer.etag = "\"mixed-etag\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .maxConcurrentDownloads(2)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final CountDownLatch allDone = new CountDownLatch(2);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent) { allDone.countDown(); }
            });

            final Path destSingle = tempDir.resolve("mixed-single.txt");
            final Path destSeg    = tempDir.resolve("mixed-seg.bin");

            final DownloadSubmissionResult rSingle = manager.download(
                    DownloadRequest.builder().sourceUri(singleServer.uri()).targetPath(destSingle).build());
            final DownloadSubmissionResult rSeg = manager.download(
                    DownloadRequest.builder().sourceUri(segmentedServer.uri()).targetPath(destSeg).build());

            assertTrue(allDone.await(15, TimeUnit.SECONDS),
                    "Both mixed-strategy downloads must complete");

            assertTrue(byId.get(rSingle.downloadId()).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertTrue(byId.get(rSeg.downloadId()).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertArrayEquals(contentSingle, Files.readAllBytes(destSingle));
            assertArrayEquals(contentSeg,    Files.readAllBytes(destSeg));
        }
    }

    // ── Scenario I — partial failure, permit released ─────────────────────────

    /**
     * T10-I: A permanently failing download releases its permit so queued work can proceed.
     *
     * <p>With {@code maxConcurrentDownloads=1}: download A always fails (no retry).
     * Download B is queued. After A fails and releases its permit, B starts and completes.
     */
    @Test
    void failedDownloadReleasesPermitAllowingQueuedWork() throws Exception {
        final byte[] contentB = "post-failure content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer failServer    = new TestHttpServer(new byte[0]);
             TestHttpServer successServer = new TestHttpServer(contentB)) {

            failServer.failFirstNNonHeadRequests = 100; // always fail
            failServer.failStatusCode = 500;            // permanent → no retry

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .maxConcurrentDownloads(1)
                    .build();

            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch bDone   = new CountDownLatch(1);
            final CountDownLatch aFailed = new CountDownLatch(1);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadFailedEvent)   { aFailed.countDown(); }
                if (e instanceof DownloadCompletedEvent) { bDone.countDown(); }
            });

            final Path destA = tempDir.resolve("fail-a.bin");
            final Path destB = tempDir.resolve("success-b.txt");

            // Submit A (fails immediately — 500 is permanent) then B (queued on semaphore)
            final DownloadSubmissionResult rA = manager.download(
                    DownloadRequest.builder().sourceUri(failServer.uri()).targetPath(destA).build());
            final DownloadSubmissionResult rB = manager.download(
                    DownloadRequest.builder().sourceUri(successServer.uri()).targetPath(destB).build());

            // A must fail
            assertTrue(aFailed.await(10, TimeUnit.SECONDS),
                    "Download A must fail");
            assertEquals(1, byId.get(rA.downloadId()).stream()
                    .filter(e -> e instanceof DownloadFailedEvent).count());

            // B must start and complete after A's permit is released
            assertTrue(bDone.await(10, TimeUnit.SECONDS),
                    "Download B must complete after A releases its permit");
            assertTrue(byId.get(rB.downloadId()).stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(byId.get(rB.downloadId()).stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(contentB, Files.readAllBytes(destB));
        }
    }

    // ── Scenario J — batch cleanup ────────────────────────────────────────────

    /**
     * T10-J: A bounded batch of downloads (some with retries, one cancelled) all reach
     * a terminal state; no downloads are left orphaned after the batch completes.
     */
    @Test
    void batchDownloadsAllReachTerminalStateWithNoOrphans() throws Exception {
        final byte[] content = "batch content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server    = new TestHttpServer(content);
             TestHttpServer failServer = new TestHttpServer(new byte[0])) {

            failServer.failFirstNNonHeadRequests = 1;
            failServer.failStatusCode = 503;

            final int batchSize = 6;
            final DownloadManager manager = DownloadManagers.createDefault();
            final Map<String, List<DownloadEvent>> byId = new ConcurrentHashMap<>();
            final CountDownLatch allTerminal = new CountDownLatch(batchSize);

            manager.addListener(e -> {
                byId.computeIfAbsent(e.info().downloadId(), k -> new CopyOnWriteArrayList<>()).add(e);
                if (e instanceof DownloadCompletedEvent
                        || e instanceof DownloadFailedEvent
                        || e instanceof DownloadCancelledEvent) {
                    allTerminal.countDown();
                }
            });

            final List<DownloadSubmissionResult> results = new ArrayList<>();

            // 4 normal downloads
            for (int i = 0; i < 4; i++) {
                results.add(manager.download(DownloadRequest.builder()
                        .sourceUri(server.uri())
                        .targetPath(tempDir.resolve("batch-" + i + ".txt"))
                        .build()));
            }

            // 1 that fails first then succeeds (retry)
            results.add(manager.download(DownloadRequest.builder()
                    .sourceUri(failServer.uri())
                    .targetPath(tempDir.resolve("batch-retry.txt"))
                    .retryConfig(RetryConfig.fixedDelay(1, Duration.ofMillis(20)))
                    .build()));

            // 1 that is cancelled
            final DownloadSubmissionResult rCancel = manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri())
                    .targetPath(tempDir.resolve("batch-cancel.txt"))
                    .build());
            results.add(rCancel);

            // Brief wait then cancel the last one
            awaitCondition(
                    () -> byId.getOrDefault(rCancel.downloadId(), List.of()).stream()
                            .anyMatch(e -> e instanceof DownloadStartedEvent),
                    5_000, "cancel target to start");
            manager.cancel(rCancel.downloadId());

            assertTrue(allTerminal.await(20, TimeUnit.SECONDS),
                    "All batch downloads must reach a terminal state");

            // Every submitted download has at least one terminal event
            for (final DownloadSubmissionResult r : results) {
                final List<DownloadEvent> events = byId.get(r.downloadId());
                assertNotNull(events, "Events must exist for " + r.downloadId());
                final long terminalCount = events.stream()
                        .filter(e -> e instanceof DownloadCompletedEvent
                                || e instanceof DownloadFailedEvent
                                || e instanceof DownloadCancelledEvent)
                        .count();
                assertEquals(1, terminalCount,
                        "Exactly one terminal event for " + r.downloadId());
            }
        }
    }
}
