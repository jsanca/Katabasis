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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DownloadManagerTest {

    @TempDir
    Path tempDir;

    // ── minimal in-process HTTP/1.1 server ────────────────────────────────────

    /**
     * Handles HEAD (capability probe) and GET requests.
     * Does not advertise Accept-Ranges, so the orchestrator always selects single-stream.
     * Set {@code getStatusCode} to a non-200 value to simulate server errors.
     * Set {@code perWriteDelayMs > 0} to throttle delivery for cancel/pause tests.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile int getStatusCode = 200;
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

                while (!readLine(in).isEmpty()) { /* consume headers */ }

                final OutputStream out = socket.getOutputStream();

                if (requestLine.startsWith("HEAD")) {
                    final String headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }

                if (getStatusCode != 200) {
                    final String headers = "HTTP/1.1 " + getStatusCode + " Error\r\n" +
                            "Content-Length: 0\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }

                final String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII));

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
                out.flush();
            } catch (IOException ignored) {
                // normal on client disconnect during cancellation tests
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

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void downloadManagerShouldEmitStartedProgressAndCompletedEventsOnSuccess() throws Exception {
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'A');

        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(event -> {
                events.add(event);
                if (event instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("output.bin");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for completion");

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadStartedEvent),
                    "Expected DownloadStartedEvent");
            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadProgressEvent),
                    "Expected at least one DownloadProgressEvent");
            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected no DownloadFailedEvent");

            assertTrue(Files.exists(dest), "Output file must exist");
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "File content must match source");
        }
    }

    @Test
    void downloadManagerShouldEmitFailedEventWhenServerReturnsNonSuccessStatus() throws Exception {
        final byte[] content = new byte[1024];

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.getStatusCode = 500;

            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch failedLatch = new CountDownLatch(1);

            manager.addListener(event -> {
                events.add(event);
                if (event instanceof DownloadFailedEvent) { failedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("output.bin");
            manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(failedLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for failure");

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected DownloadFailedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected no DownloadCompletedEvent");
        }
    }

    @Test
    void downloadManagerShouldEmitFailedEventForUnsupportedUriScheme() throws Exception {
        final DownloadManager manager = DownloadManagers.createDefault();
        final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch failedLatch = new CountDownLatch(1);

        manager.addListener(event -> {
            events.add(event);
            if (event instanceof DownloadFailedEvent) { failedLatch.countDown(); }
        });

        final Path dest = tempDir.resolve("output.bin");
        manager.download(DownloadRequest.builder().sourceUri(URI.create("ftp://example.com/file.txt")).targetPath(dest).build());

        assertTrue(failedLatch.await(5, TimeUnit.SECONDS),
                "Timed out waiting for failure on unsupported scheme");

        // IllegalArgumentException from resolver maps to UNKNOWN until MapperExceptionUtil is extended
        final DownloadFailedEvent failed = events.stream()
                .filter(e -> e instanceof DownloadFailedEvent)
                .map(e -> (DownloadFailedEvent) e)
                .findFirst().orElseThrow();

        assertEquals(DownloadErrorCode.UNKNOWN, failed.errorCode());
    }

    @Test
    void downloadManagerShouldEmitCancelledEventWhenCancelledMidDownload() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'C');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch firstProgressLatch = new CountDownLatch(1);
            final CountDownLatch cancelledLatch = new CountDownLatch(1);

            manager.addListener(event -> {
                events.add(event);
                if (event instanceof DownloadProgressEvent) { firstProgressLatch.countDown(); }
                if (event instanceof DownloadCancelledEvent) { cancelledLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("cancelled.bin");
            final DownloadSubmissionResult result =
                    manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(firstProgressLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for first progress event");
            manager.cancel(result.downloadId());

            assertTrue(cancelledLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for cancellation");

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCancelledEvent),
                    "Expected DownloadCancelledEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected no DownloadCompletedEvent after cancellation");
        }
    }

    @Test
    void downloadManagerShouldEmitPausedAndResumedOnceAndCompleteAfterResume() throws Exception {
        final byte[] content = new byte[256 * 1024];
        Arrays.fill(content, (byte) 'R');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();
            final CountDownLatch firstProgressLatch = new CountDownLatch(1);
            final CountDownLatch pausedLatch = new CountDownLatch(1);
            final CountDownLatch completedLatch = new CountDownLatch(1);

            manager.addListener(event -> {
                events.add(event);
                if (event instanceof DownloadProgressEvent) { firstProgressLatch.countDown(); }
                if (event instanceof DownloadPausedEvent)   { pausedLatch.countDown(); }
                if (event instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path dest = tempDir.resolve("resumed.bin");
            final DownloadSubmissionResult result =
                    manager.download(DownloadRequest.builder().sourceUri(server.uri()).targetPath(dest).build());

            assertTrue(firstProgressLatch.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for first progress event");
            manager.pause(result.downloadId());

            assertTrue(pausedLatch.await(5, TimeUnit.SECONDS),
                    "Timed out waiting for DownloadPausedEvent");
            manager.resume(result.downloadId());

            assertTrue(completedLatch.await(15, TimeUnit.SECONDS),
                    "Timed out waiting for completion after resume");

            final long pausedCount = events.stream()
                    .filter(e -> e instanceof DownloadPausedEvent).count();
            final long resumedCount = events.stream()
                    .filter(e -> e instanceof DownloadResumedEvent).count();

            assertEquals(1, pausedCount, "Expected exactly one DownloadPausedEvent");
            assertEquals(1, resumedCount, "Expected exactly one DownloadResumedEvent");
            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent after resume");

            assertTrue(Files.exists(dest), "Output file must exist after resume");
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "File content must be complete and correct after pause/resume");
        }
    }
}
