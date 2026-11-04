package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadInfo;
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

import static org.junit.jupiter.api.Assertions.*;

class HttpDownloadOrchestratorTest {

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
     * HTTP/1.1 server that handles both HEAD (probe) and GET (download) requests.
     *
     * <ul>
     *   <li>{@code acceptsRanges} — controls the {@code Accept-Ranges} header in HEAD responses.</li>
     *   <li>{@code rangeRequestsOnly} — when {@code true}, rejects full GET requests with 400;
     *       only Range GETs succeed. Use this to verify that the segmented path was taken.</li>
     *   <li>{@code failHeadRequest} — when {@code true}, closes the connection without a response
     *       on HEAD; the {@code HttpClient} will throw an {@code IOException}.</li>
     * </ul>
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean acceptsRanges = true;
        volatile boolean rangeRequestsOnly = false;
        volatile boolean failHeadRequest = false;
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
                        rangeEnd = Long.parseLong(parts[1].trim());
                    }
                }

                if (isHead && failHeadRequest) {
                    return; // close socket → IOException in probe → fallback triggered
                }

                final OutputStream out = socket.getOutputStream();

                if (isHead) {
                    writeHeadResponse(out);
                } else if (rangeStart >= 0) {
                    writePartialResponse(out, rangeStart, rangeEnd);
                } else if (rangeRequestsOnly) {
                    writeErrorResponse(out, 400, "Range header required");
                } else {
                    writeFullResponse(out);
                }
                out.flush();

            } catch (IOException ignored) {
                // socket closed — normal
            }
        }

        private void writeHeadResponse(final OutputStream out) throws IOException {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Length: ").append(content.length).append("\r\n");
            if (acceptsRanges) { sb.append("Accept-Ranges: bytes\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }

        private void writePartialResponse(final OutputStream out,
                                           final long start, final long end) throws IOException {
            final long len = end - start + 1;
            final String headers = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(content, (int) start, (int) len);
        }

        private void writeFullResponse(final OutputStream out) throws IOException {
            final String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(content);
        }

        private void writeErrorResponse(final OutputStream out,
                                         final int status,
                                         final String msg) throws IOException {
            final String response = "HTTP/1.1 " + status + " " + msg + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(response.getBytes(StandardCharsets.US_ASCII));
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
    void orchestratorShouldUseSegmentedStrategyWhenServerSupportsRanges() throws Exception {
        final byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            // rangeRequestsOnly ensures a full GET would fail → proves segmented path was taken
            server.rangeRequestsOnly = true;

            final Path dest = tempDir.resolve("out.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)  // 1 byte → any file triggers segmented
                    .build();

            final HttpDownloadOrchestrator orchestrator =
                    new HttpDownloadOrchestrator(httpClient(), config, 512);
            orchestrator.download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent via segmented strategy");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected no DownloadFailedEvent");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    @Test
    void orchestratorShouldUseSingleStreamWhenServerDoesNotAcceptRanges() throws Exception {
        final byte[] content = "hello single stream".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = false;

            final Path dest = tempDir.resolve("out.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final HttpDownloadOrchestrator orchestrator =
                    new HttpDownloadOrchestrator(httpClient(), DownloadConfig.defaultConfig(), 8192);
            orchestrator.download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent via single-stream strategy");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected no DownloadFailedEvent");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    @Test
    void orchestratorShouldFallBackToSingleStreamWhenProbeFails() throws Exception {
        final byte[] content = "fallback content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.failHeadRequest = true; // probe throws IOException → fallback to single-stream

            final Path dest = tempDir.resolve("out.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .build();

            final HttpDownloadOrchestrator orchestrator =
                    new HttpDownloadOrchestrator(httpClient(), config, 8192);
            orchestrator.download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent via fallback single-stream strategy");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Expected no DownloadFailedEvent");
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }
}
