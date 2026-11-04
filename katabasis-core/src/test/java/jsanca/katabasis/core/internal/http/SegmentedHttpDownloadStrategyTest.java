package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SegmentedHttpDownloadStrategyTest {

    @TempDir
    Path tempDir;

    // ── helpers ────────────────────────────────────────────────────────────────

    private static DownloadTask task(final URI uri, final Path destination) {
        return new DownloadTask(new DownloadInfo(UUID.randomUUID().toString(), uri, destination));
    }

    private static DownloadExecutionContext ctx() {
        return new DownloadExecutionContext(new PauseCoordinator());
    }

    private static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── minimal HTTP server ────────────────────────────────────────────────────

    /**
     * Single-use HTTP/1.1 server backed by a {@link ServerSocket}.
     * Handles GET requests with a {@code Range} header.
     * Set {@code respondWith206 = false} to simulate a server that ignores ranges.
     * Set {@code perWriteDelayMs > 0} to throttle delivery and allow mid-stream pause.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean respondWith206 = true;
        volatile int perWriteDelayMs = 0;
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

                long rangeStart = 0;
                long rangeEnd = content.length - 1;
                boolean hasRange = false;

                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    if (line.startsWith("Range: bytes=")) {
                        hasRange = true;
                        final String[] parts = line.substring("Range: bytes=".length()).split("-");
                        rangeStart = Long.parseLong(parts[0].trim());
                        rangeEnd = Long.parseLong(parts[1].trim());
                    }
                }

                final OutputStream out = socket.getOutputStream();
                if (hasRange && respondWith206) {
                    writePartial(out, rangeStart, rangeEnd);
                } else {
                    writeFull(out, rangeStart, rangeEnd, hasRange);
                }
                out.flush();

            } catch (IOException ignored) {
                // socket closed or client disconnected — normal during cancellation tests
            }
        }

        private void writePartial(final OutputStream out,
                                   final long start, final long end) throws IOException {
            final long len = end - start + 1;
            final String headers = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
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

        private void writeFull(final OutputStream out,
                                final long start, final long end,
                                final boolean rangeRequested) throws IOException {
            final long len = rangeRequested ? (end - start + 1) : content.length;
            final int offset = rangeRequested ? (int) start : 0;
            final String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(content, offset, (int) len);
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

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void downloadShouldWriteAllBytesCorrectlyAcrossMultipleChunks() throws Exception {
        final byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 'A');

        try (TestHttpServer server = new TestHttpServer(content)) {
            final Path dest = tempDir.resolve("out.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final SegmentedHttpDownloadStrategy strategy =
                    new SegmentedHttpDownloadStrategy(httpClient(), 4, content.length, 512);
            strategy.download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected no DownloadFailedEvent");
            assertTrue(Files.exists(dest), "Output file must exist");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    @Test
    void downloadShouldEmitFailedEventWhenChunkReceives200InsteadOf206() throws Exception {
        final byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 'X');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.respondWith206 = false;

            final Path dest = tempDir.resolve("out.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final SegmentedHttpDownloadStrategy strategy =
                    new SegmentedHttpDownloadStrategy(httpClient(), 4, content.length, 8192);
            strategy.download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected DownloadFailedEvent");

            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();

            // FailedException is unwrapped in download() — cause is HttpDownloadStatusException directly
            assertInstanceOf(HttpDownloadStatusException.class, failed.cause(),
                    "Cause must be HttpDownloadStatusException");
            assertEquals(200, ((HttpDownloadStatusException) failed.cause()).statusCode());
        }
    }

    @Test
    void downloadShouldEmitCancelledEventAndDeleteFileWhenCancelled() throws Exception {
        final byte[] content = new byte[256 * 1024]; // 256 KB — large enough to cancel mid-stream
        Arrays.fill(content, (byte) 'Z');

        try (TestHttpServer server = new TestHttpServer(content)) {
            final Path dest = tempDir.resolve("out.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch firstProgressLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = event -> {
                events.add(event);
                if (event instanceof DownloadProgressEvent) { firstProgressLatch.countDown(); }
            };

            final SegmentedHttpDownloadStrategy strategy =
                    new SegmentedHttpDownloadStrategy(httpClient(), 4, content.length, 1024);

            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> strategy.download(task, ctx(), emitter));

            assertTrue(firstProgressLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for first progress event");
            task.requestCancellation();

            downloadThread.join(10_000);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCancelledEvent),
                    "Expected DownloadCancelledEvent");
            assertFalse(Files.exists(dest),
                    "Partial file must be deleted after cancellation");
        }
    }

    @Test
    void pauseAndResumeShouldEmitExactlyOneEventEachAcrossAllChunks() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'P');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5; // throttle so pause can be requested mid-stream

            final Path dest = tempDir.resolve("paused.bin");
            final PauseCoordinator pauseCoordinator = new PauseCoordinator();
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch firstProgressLatch = new CountDownLatch(1);
            final CountDownLatch completedLatch = new CountDownLatch(1);

            final Consumer<DownloadEvent> emitter = event -> {
                events.add(event);
                if (event instanceof DownloadProgressEvent) { firstProgressLatch.countDown(); }
                if (event instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            };

            final SegmentedHttpDownloadStrategy strategy =
                    new SegmentedHttpDownloadStrategy(httpClient(), 4, content.length, 1024);

            final Thread downloadThread = Thread.ofVirtual()
                    .start(() -> strategy.download(task,
                            new DownloadExecutionContext(pauseCoordinator), emitter));

            assertTrue(firstProgressLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for first progress event");

            pauseCoordinator.requestPause(task.info().downloadId());

            final long pauseDeadline = System.currentTimeMillis() + 5_000;
            while (events.stream().noneMatch(e -> e instanceof DownloadPausedEvent)) {
                assertTrue(System.currentTimeMillis() < pauseDeadline,
                        "Timed out waiting for DownloadPausedEvent");
                Thread.sleep(20);
            }

            pauseCoordinator.requestResume(task.info().downloadId());

            assertTrue(completedLatch.await(15, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadCompletedEvent");
            downloadThread.join(5_000);

            final long pausedCount = events.stream()
                    .filter(e -> e instanceof DownloadPausedEvent).count();
            final long resumedCount = events.stream()
                    .filter(e -> e instanceof DownloadResumedEvent).count();

            assertEquals(1, pausedCount,
                    "Expected exactly one DownloadPausedEvent across all chunk threads");
            assertEquals(1, resumedCount,
                    "Expected exactly one DownloadResumedEvent across all chunk threads");

            assertTrue(Files.exists(dest), "Output file must exist after resume");
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "File content must be complete and correct after pause/resume");
        }
    }
}
