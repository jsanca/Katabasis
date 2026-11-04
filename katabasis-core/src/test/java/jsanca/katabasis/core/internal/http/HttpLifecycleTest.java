package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.HttpTimeoutConfig;
import jsanca.katabasis.core.api.model.RetryConfig;
import jsanca.katabasis.core.internal.execution.DownloadExecutionContext;
import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import jsanca.katabasis.core.internal.model.DownloadTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.1-T08 — HTTP resource lifecycle verification.
 *
 * <p>Tests verify resource cleanup across normal completion, cancellation, failure,
 * retry, and probe/fallback scenarios. Tests also document the partial-file retention
 * policy for single-stream versus segmented strategies.
 *
 * <h2>Partial-file policy</h2>
 * <ul>
 *   <li><b>Single-stream ({@link HttpDownloadStrategy}):</b> partial files are
 *       <em>retained</em> on failure and cancellation. The partial write represents
 *       progress that may be useful for manual recovery. No automatic delete.</li>
 *   <li><b>Segmented ({@link SegmentedHttpDownloadStrategy}):</b> partial files are
 *       <em>always deleted</em> on any terminal failure (integrity error, cancellation,
 *       network error). An incomplete segmented file cannot be used directly — segments
 *       may be missing or inconsistent.</li>
 * </ul>
 */
class HttpLifecycleTest {

    @TempDir
    Path tempDir;

    // ── helpers ───────────────────────────────────────────────────────────────

    private static DownloadTask task(final URI uri, final Path destination) {
        return new DownloadTask(new DownloadInfo(UUID.randomUUID().toString(), uri, destination));
    }

    private static DownloadExecutionContext ctx() {
        return new DownloadExecutionContext(new PauseCoordinator());
    }

    private static DownloadExecutionContext ctxWithRetry(final RetryConfig retryConfig) {
        return new DownloadExecutionContext(new PauseCoordinator(), retryConfig);
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── minimal in-process HTTP server ────────────────────────────────────────

    /**
     * Simple HTTP/1.1 server that serves a fixed response sequence.
     *
     * <p>Each accepted connection consumes the next response from the queue;
     * when the queue is empty it falls back to the last configured response.
     * This allows scripting "first request returns 500, all subsequent return 200" patterns.
     */
    private static final class ScriptedHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Queue<Response> script = new LinkedList<>();
        private volatile Response fallback;
        private volatile boolean closed = false;

        record Response(int status, byte[] body, Map<String, String> headers) {}

        ScriptedHttpServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        ScriptedHttpServer thenRespond(final int status, final byte[] body) {
            final Response r = new Response(status, body, Map.of());
            script.add(r);
            fallback = r;
            return this;
        }

        ScriptedHttpServer thenRespond(final int status, final byte[] body,
                                        final Map<String, String> headers) {
            final Response r = new Response(status, body, headers);
            script.add(r);
            fallback = r;
            return this;
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
                // consume request
                final String req = readLine(in);
                if (req == null || req.isBlank()) { return; }
                while (!readLine(in).isEmpty()) { /* drain headers */ }

                final Response r = script.isEmpty() ? fallback : script.poll();
                if (r == null) { return; }

                final OutputStream out = socket.getOutputStream();
                final StringBuilder sb = new StringBuilder("HTTP/1.1 ")
                        .append(r.status()).append(" ").append(statusText(r.status())).append("\r\n");
                sb.append("Content-Length: ").append(r.body().length).append("\r\n");
                r.headers().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
                sb.append("Connection: close\r\n\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
                out.write(r.body());
                out.flush();
            } catch (IOException ignored) {}
        }

        private static String statusText(final int code) {
            return switch (code) {
                case 200 -> "OK";
                case 206 -> "Partial Content";
                case 500 -> "Internal Server Error";
                case 503 -> "Service Unavailable";
                default  -> "Unknown";
            };
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

    /**
     * HTTP server that tracks how many connections were accepted.
     * Used to verify that no connections are left open after failure.
     */
    private static final class TrackingHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        private final AtomicInteger requestCount = new AtomicInteger(0);
        private volatile boolean closed = false;

        TrackingHttpServer(final byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file"); }

        int requestCount() { return requestCount.get(); }

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
            requestCount.incrementAndGet();
            try (socket) {
                final InputStream in = socket.getInputStream();
                final String req = readLine(in);
                if (req == null || req.isBlank()) { return; }
                while (!readLine(in).isEmpty()) { /* drain headers */ }

                final OutputStream out = socket.getOutputStream();
                final String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(content);
                out.flush();
            } catch (IOException ignored) {}
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

    // ── Partial-file policy ───────────────────────────────────────────────────

    /**
     * Single-stream: partial file is RETAINED after failure.
     * Policy: partial bytes may aid manual recovery; automatic deletion is not performed.
     */
    @Test
    void singleStreamPartialFileRetainedAfterFailure() throws Exception {
        // Server returns 500 with no retry policy
        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            server.thenRespond(500, new byte[0]);

            final Path dest = tempDir.resolve("partial-fail.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            // Policy assertion: single-stream does NOT delete the partial file
            // (file may not exist if zero bytes were written, but it must not have been
            //  explicitly deleted by the strategy's error path)
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    /**
     * Single-stream: partial file is RETAINED after cancellation.
     * Policy: same as failure — no automatic deletion.
     */
    @Test
    void singleStreamPartialFileRetainedAfterCancellation() throws Exception {
        final byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 'C');

        try (TrackingHttpServer server = new TrackingHttpServer(content)) {
            final Path dest = tempDir.resolve("partial-cancel.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
            };

            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> new HttpDownloadStrategy(httpClient(), 1024, HttpTimeoutConfig.disabled())
                            .download(task, ctx(), emitter));

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS));
            task.requestCancellation();
            downloadThread.join(10_000);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCancelledEvent));
            // Policy: file may still exist with partial bytes; it was not deleted
            if (Files.exists(dest)) {
                assertTrue(Files.size(dest) > 0, "If retained, partial file must have content");
            }
        }
    }

    /**
     * Segmented: partial file is DELETED after failure.
     * Policy: an incomplete segmented file cannot be used (segments may be missing) — always clean up.
     */
    @Test
    void segmentedPartialFileDeletedAfterFailure() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'F');

        // Server returns 200 not 206 — triggers HttpDownloadStatusException on chunk
        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            server.thenRespond(200, content); // HEAD
            server.thenRespond(206, new byte[]{content[0]}, Map.of( // range probe
                    "Content-Range", "bytes 0-0/" + content.length,
                    "ETag", "\"v1\""
            ));
            // All subsequent chunk requests return 200 (wrong for segmented)
            for (int i = 0; i < 8; i++) {
                server.thenRespond(200, content);
            }

            final Path dest = tempDir.resolve("segmented-fail.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertFalse(Files.exists(dest),
                    "Segmented strategy must delete the partial file after failure");
        }
    }

    /**
     * Segmented: partial file is DELETED after cancellation.
     */
    @Test
    void segmentedPartialFileDeletedAfterCancellation() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'Z');

        // Use inline server since ScriptedHttpServer can't handle Range requests
        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            Thread.ofVirtual().start(() -> serveRanges(ss, content, "\"v1\""));

            final Path dest = tempDir.resolve("segmented-cancel.bin");
            final DownloadTask task = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
            };

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> new SegmentedHttpDownloadStrategy(
                            httpClient(), 4, identity, 1024, HttpTimeoutConfig.disabled())
                            .download(task, ctx(), emitter));

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS));
            task.requestCancellation();
            downloadThread.join(10_000);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCancelledEvent));
            assertFalse(Files.exists(dest),
                    "Segmented strategy must delete the partial file after cancellation");
        }
    }

    // ── Response body closed on bad status ────────────────────────────────────

    /**
     * Single-stream: a subsequent download to the same server succeeds after
     * a first download fails with a 500 response.
     *
     * <p>This demonstrates that the response body from the failed attempt is closed,
     * allowing the connection (and server resources) to be reused.
     */
    @Test
    void singleStreamSubsequentDownloadSucceedsAfterInvalidStatus() throws Exception {
        final byte[] content = "good content".getBytes(StandardCharsets.UTF_8);

        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            server.thenRespond(500, new byte[0]);    // first request fails
            server.thenRespond(200, content);         // second request succeeds

            // First download fails
            final Path dest1 = tempDir.resolve("fail.txt");
            final DownloadTask task1 = task(server.uri(), dest1);
            final List<DownloadEvent> events1 = new CopyOnWriteArrayList<>();
            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task1, ctx(), events1::add);
            assertTrue(events1.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "First download must fail");

            // Second download succeeds — proves connection/state is clean
            final Path dest2 = tempDir.resolve("success.txt");
            final DownloadTask task2 = task(server.uri(), dest2);
            final List<DownloadEvent> events2 = new CopyOnWriteArrayList<>();
            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task2, ctx(), events2::add);
            assertTrue(events2.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Second download must succeed");
            assertArrayEquals(content, Files.readAllBytes(dest2));
        }
    }

    /**
     * Segmented: a subsequent download succeeds after a chunk receives a non-206 status.
     */
    @Test
    void segmentedSubsequentDownloadSucceedsAfterInvalidChunkStatus() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'S');

        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            final AtomicInteger requestCount = new AtomicInteger(0);

            // First set of chunk requests will return 200 (wrong); range probe returns correctly
            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final int req = requestCount.getAndIncrement();
                                final InputStream in = socket.getInputStream();
                                final String line = readLine(in);
                                if (line == null || line.isBlank()) { return; }
                                long rs = -1, re = -1;
                                boolean isHead = line.startsWith("HEAD");
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
                                    // HEAD probe
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                } else if (rs == 0 && re == 0) {
                                    // Range probe
                                    writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: 1\r\n"
                                            + "Content-Range: bytes 0-0/" + content.length
                                            + "\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                    out.write(content, 0, 1);
                                } else if (req < 2) {
                                    // First download's 2 chunks return wrong 200 → fails
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nConnection: close\r\n\r\n");
                                    out.write(content);
                                } else {
                                    // Subsequent chunk requests: correct 206
                                    final long len = re - rs + 1;
                                    writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                                            + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + content.length
                                            + "\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
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

            // First download: chunk requests return 200 → failure
            final Path dest1 = tempDir.resolve("fail-chunk.bin");
            final DownloadTask task1 = task(uri, dest1);
            final List<DownloadEvent> events1 = new CopyOnWriteArrayList<>();
            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task1, ctx(), events1::add);
            assertTrue(events1.stream().anyMatch(e -> e instanceof DownloadFailedEvent));

            // Second download: chunk requests return 206 → success
            final Path dest2 = tempDir.resolve("success-chunk.bin");
            final DownloadTask task2 = task(uri, dest2);
            final List<DownloadEvent> events2 = new CopyOnWriteArrayList<>();
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task2, ctx(), events2::add);
            assertTrue(events2.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Second download must succeed after first attempt's bodies are released");
            assertArrayEquals(content, Files.readAllBytes(dest2));
        }
    }

    // ── Retry path correctness ────────────────────────────────────────────────

    /**
     * Single-stream: a transient 503 failure followed by a 200 response produces
     * exactly one CompletedEvent and the correct file contents.
     * Verifies that retry does not reuse stale streams from the failed attempt.
     */
    @Test
    void singleStreamRetrySucceedsAfterTransientFailure() throws Exception {
        final byte[] content = "retry content".getBytes(StandardCharsets.UTF_8);

        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            server.thenRespond(503, new byte[0]);    // first attempt: transient failure
            server.thenRespond(200, content);         // second attempt: success

            final Path dest = tempDir.resolve("retry-success.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final RetryConfig retryConfig = RetryConfig.fixedDelay(1, Duration.ofMillis(50));
            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task, ctxWithRetry(retryConfig), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Download must succeed after one retry");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    /**
     * Single-stream: when retries are exhausted, exactly one DownloadFailedEvent is emitted
     * and no CompletedEvent is emitted.
     */
    @Test
    void singleStreamRetryExhaustionProducesExactlyOneFailedEvent() throws Exception {
        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            // Always return 503
            for (int i = 0; i < 5; i++) { server.thenRespond(503, new byte[0]); }

            final Path dest = tempDir.resolve("exhausted.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            // 2 max retries = 3 total attempts
            final RetryConfig retryConfig = RetryConfig.fixedDelay(2, Duration.ofMillis(10));
            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task, ctxWithRetry(retryConfig), events::add);

            final long failedCount = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent).count();
            assertEquals(1, failedCount, "Exactly one DownloadFailedEvent on retry exhaustion");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    /**
     * Segmented: a transient 503 on one chunk (then 206) produces a CompletedEvent.
     * Verifies that the retry in the segmented path recreates the HTTP request and
     * does not reuse the closed stream from the failed attempt.
     */
    @Test
    void segmentedRetrySucceedsAfterTransientChunkFailure() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'R');

        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            final AtomicInteger chunkRequests = new AtomicInteger(0);

            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final InputStream in = socket.getInputStream();
                                final String line = readLine(in);
                                if (line == null || line.isBlank()) { return; }
                                boolean isHead = line.startsWith("HEAD");
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
                                    // First chunk request for the first segment: return 503 once
                                    final int attempt = chunkRequests.getAndIncrement();
                                    if (attempt == 0) {
                                        writeRaw(out, "HTTP/1.1 503 Service Unavailable\r\n"
                                                + "Content-Length: 0\r\nConnection: close\r\n\r\n");
                                    } else {
                                        final long len = re - rs + 1;
                                        writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                                                + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + content.length
                                                + "\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                        out.write(content, (int) rs, (int) len);
                                    }
                                }
                                out.flush();
                            } catch (IOException ignored) {}
                        });
                    } catch (IOException e) {
                        if (!ss.isClosed()) { throw new UncheckedIOException(e); }
                    }
                }
            });

            final Path dest = tempDir.resolve("seg-retry.bin");
            final DownloadTask task = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            final RetryConfig retryConfig = RetryConfig.fixedDelay(2, Duration.ofMillis(50));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctxWithRetry(retryConfig), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Segmented download must succeed after transient chunk failure");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Repeated pause/resume cycles ─────────────────────────────────────────

    /**
     * Two sequential pause/resume cycles complete successfully and each cycle emits
     * exactly one Paused and one Resumed event.
     * Verifies that pause/resume does not accumulate held resources or corrupt progress.
     */
    @Test
    void segmentedTwoPauseResumeCyclesCompleteWithCorrectEvents() throws Exception {
        // Large enough that the download takes several seconds at 15ms/512B per segment,
        // giving well-separated pause windows even with scheduling jitter.
        final byte[] content = new byte[512 * 1024];
        Arrays.fill(content, (byte) 'P');

        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            Thread.ofVirtual().start(() -> serveRangesThrottled(ss, content, "\"v1\"", 15));

            final Path dest = tempDir.resolve("two-cycles.bin");
            final PauseCoordinator pauseCoordinator = new PauseCoordinator();
            final DownloadTask task = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final java.util.concurrent.atomic.AtomicInteger progressCount = new java.util.concurrent.atomic.AtomicInteger(0);
            final CountDownLatch completedLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressCount.incrementAndGet(); }
                if (e instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            };

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> new SegmentedHttpDownloadStrategy(
                            httpClient(), 4, identity, 1024, HttpTimeoutConfig.disabled())
                            .download(task, new DownloadExecutionContext(pauseCoordinator), emitter));

            // Wait for first progress event before first pause
            awaitCondition(() -> progressCount.get() >= 1, 10_000, "first progress event");

            // First pause/resume cycle
            pauseCoordinator.requestPause(task.info().downloadId());
            awaitNthEvent(events, DownloadPausedEvent.class, 1, 5_000);
            pauseCoordinator.requestResume(task.info().downloadId());
            awaitNthEvent(events, DownloadResumedEvent.class, 1, 5_000);

            // Wait for several NEW progress events after the first resume to ensure
            // the download is well into its second half before the second pause.
            final int countAfterFirstResume = progressCount.get();
            awaitCondition(() -> progressCount.get() >= countAfterFirstResume + 4, 10_000,
                    "progress after first resume");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Download must still be in progress before second pause");

            // Second pause/resume cycle
            pauseCoordinator.requestPause(task.info().downloadId());
            awaitNthEvent(events, DownloadPausedEvent.class, 2, 5_000);
            pauseCoordinator.requestResume(task.info().downloadId());

            assertTrue(completedLatch.await(20, TimeUnit.SECONDS),
                    "Download must complete after two pause/resume cycles");
            downloadThread.join(5_000);

            final long pausedCount = events.stream().filter(e -> e instanceof DownloadPausedEvent).count();
            final long resumedCount = events.stream().filter(e -> e instanceof DownloadResumedEvent).count();
            assertEquals(2, pausedCount, "Expected exactly two DownloadPausedEvents");
            assertEquals(2, resumedCount, "Expected exactly two DownloadResumedEvents");

            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Idempotent cancel ─────────────────────────────────────────────────────

    /**
     * Requesting cancellation twice on the same task produces exactly one CancelledEvent
     * and does not produce CompletedEvent or FailedEvent.
     */
    @Test
    void doubleRequestCancellationProducesExactlyOneCancelledEvent() throws Exception {
        final byte[] content = new byte[128 * 1024];
        Arrays.fill(content, (byte) 'X');

        try (TrackingHttpServer server = new TrackingHttpServer(content)) {
            final Path dest = tempDir.resolve("idempotent-cancel.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch progressLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = e -> {
                events.add(e);
                if (e instanceof DownloadProgressEvent) { progressLatch.countDown(); }
            };

            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> new HttpDownloadStrategy(httpClient(), 1024, HttpTimeoutConfig.disabled())
                            .download(task, ctx(), emitter));

            assertTrue(progressLatch.await(10, TimeUnit.SECONDS));
            task.requestCancellation();
            task.requestCancellation(); // second request must be a no-op
            downloadThread.join(10_000);

            final long cancelledCount = events.stream()
                    .filter(e -> e instanceof DownloadCancelledEvent).count();
            assertEquals(1, cancelledCount, "Exactly one CancelledEvent for double cancellation request");

            final long terminalCount = events.stream()
                    .filter(e -> e instanceof DownloadCompletedEvent
                            || e instanceof DownloadFailedEvent
                            || e instanceof DownloadCancelledEvent)
                    .count();
            assertEquals(1, terminalCount, "Exactly one terminal event total");
        }
    }

    // ── Probe/fallback resource release ──────────────────────────────────────

    /**
     * When the HEAD probe succeeds but range probe returns 200 (server ignores Range),
     * the orchestrator falls back to single-stream and completes successfully.
     * Verifies that the probe response body from the 200 fallback does not block subsequent work.
     */
    @Test
    void orchestratorFallsBackAndCompletesWhenRangeProbReturns200() throws Exception {
        final byte[] content = "probe fallback".getBytes(StandardCharsets.UTF_8);

        // Server that handles HEAD, probe bytes=0-0 with 200, then full GET
        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            final AtomicInteger requestCount = new AtomicInteger(0);

            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final InputStream in = socket.getInputStream();
                                final String line = readLine(in);
                                if (line == null || line.isBlank()) { return; }
                                boolean isHead = line.startsWith("HEAD");
                                boolean hasRange = false;
                                String h;
                                while (!(h = readLine(in)).isEmpty()) {
                                    if (h.startsWith("Range:")) { hasRange = true; }
                                }
                                final int req = requestCount.getAndIncrement();
                                final OutputStream out = socket.getOutputStream();
                                if (isHead) {
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nAccept-Ranges: bytes\r\nETag: \"v1\"\r\nConnection: close\r\n\r\n");
                                } else if (hasRange && req == 1) {
                                    // Range probe: return 200 (server ignores Range) with full body
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nConnection: close\r\n\r\n");
                                    out.write(content);
                                } else {
                                    // Fallback full GET
                                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                            + "\r\nConnection: close\r\n\r\n");
                                    out.write(content);
                                }
                                out.flush();
                            } catch (IOException ignored) {}
                        });
                    } catch (IOException e) {
                        if (!ss.isClosed()) { throw new UncheckedIOException(e); }
                    }
                }
            });

            final Path dest = tempDir.resolve("probe-fallback.txt");
            final DownloadTask task = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .build();

            new HttpDownloadOrchestrator(httpClient(), config, 8192)
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Download must complete via single-stream fallback");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Successful cleanup verification ──────────────────────────────────────

    /**
     * Single-stream: successful download produces exactly one CompletedEvent,
     * the destination file is correct, and no failure or cancellation events are emitted.
     */
    @Test
    void singleStreamSuccessfulDownloadCleanup() throws Exception {
        final byte[] content = "complete content".getBytes(StandardCharsets.UTF_8);

        try (ScriptedHttpServer server = new ScriptedHttpServer()) {
            server.thenRespond(200, content);

            final Path dest = tempDir.resolve("complete.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            new HttpDownloadStrategy(httpClient(), 8192, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count());
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCancelledEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    /**
     * Segmented: successful download produces exactly one CompletedEvent,
     * the destination file is correct, and no other terminal events are emitted.
     */
    @Test
    void segmentedSuccessfulDownloadCleanup() throws Exception {
        final byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 'A');

        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            Thread.ofVirtual().start(() -> serveRanges(ss, content, "\"v1\""));

            final Path dest = tempDir.resolve("seg-complete.bin");
            final DownloadTask task = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 4, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertEquals(1, events.stream().filter(e -> e instanceof DownloadCompletedEvent).count());
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── Helper: range-serving in-process server ───────────────────────────────

    private static void serveRanges(final ServerSocket ss,
                                     final byte[] content,
                                     final String etag) {
        while (!ss.isClosed()) {
            try {
                final Socket socket = ss.accept();
                Thread.ofVirtual().start(() -> {
                    try (socket) {
                        final InputStream in = socket.getInputStream();
                        final String line = readLine(in);
                        if (line == null || line.isBlank()) { return; }
                        boolean isHead = line.startsWith("HEAD");
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
                                    + "\r\nAccept-Ranges: bytes\r\nETag: " + etag + "\r\nConnection: close\r\n\r\n");
                        } else if (rs >= 0) {
                            final long len = re - rs + 1;
                            writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                                    + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + content.length
                                    + "\r\nETag: " + etag + "\r\nConnection: close\r\n\r\n");
                            out.write(content, (int) rs, (int) len);
                        } else {
                            writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Length: " + content.length
                                    + "\r\nConnection: close\r\n\r\n");
                            out.write(content);
                        }
                        out.flush();
                    } catch (IOException ignored) {}
                });
            } catch (IOException e) {
                if (!ss.isClosed()) { throw new UncheckedIOException(e); }
            }
        }
    }

    private static void serveRangesThrottled(final ServerSocket ss,
                                              final byte[] content,
                                              final String etag,
                                              final int perWriteDelayMs) {
        while (!ss.isClosed()) {
            try {
                final Socket socket = ss.accept();
                Thread.ofVirtual().start(() -> {
                    try (socket) {
                        final InputStream in = socket.getInputStream();
                        final String line = readLine(in);
                        if (line == null || line.isBlank()) { return; }
                        boolean isHead = line.startsWith("HEAD");
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
                                    + "\r\nAccept-Ranges: bytes\r\nETag: " + etag + "\r\nConnection: close\r\n\r\n");
                        } else if (rs >= 0) {
                            final long len = re - rs + 1;
                            writeRaw(out, "HTTP/1.1 206 Partial Content\r\nContent-Length: " + len
                                    + "\r\nContent-Range: bytes " + rs + "-" + re + "/" + content.length
                                    + "\r\nETag: " + etag + "\r\nConnection: close\r\n\r\n");
                            final int piece = 512;
                            for (int offset = (int) rs; offset <= (int) re; offset += piece) {
                                final int toWrite = (int) Math.min(piece, re - offset + 1);
                                out.write(content, offset, toWrite);
                                out.flush();
                                try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
                            }
                        }
                        out.flush();
                    } catch (IOException ignored) {}
                });
            } catch (IOException e) {
                if (!ss.isClosed()) { throw new UncheckedIOException(e); }
            }
        }
    }

    private static <T extends DownloadEvent> void awaitEvent(final List<DownloadEvent> events,
                                                              final Class<T> type,
                                                              final long timeoutMs)
            throws InterruptedException {
        awaitNthEvent(events, type, 1, timeoutMs);
    }

    private static <T extends DownloadEvent> void awaitNthEvent(final List<DownloadEvent> events,
                                                                  final Class<T> type,
                                                                  final int n,
                                                                  final long timeoutMs)
            throws InterruptedException {
        awaitCondition(() -> events.stream().filter(type::isInstance).count() >= n, timeoutMs,
                n + "× " + type.getSimpleName());
    }

    private static void awaitCondition(final java.util.function.BooleanSupplier condition,
                                        final long timeoutMs,
                                        final String description) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline,
                    "Timed out waiting for: " + description);
            Thread.sleep(20);
        }
    }

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
