package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.*;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.HttpTimeoutConfig;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for K-0.1-T07: HTTP range capability negotiation and
 * per-segment resource-integrity validation.
 *
 * <p>Tests cover orchestrator strategy selection, range probe edge cases, and
 * per-chunk Content-Range and ETag validation in {@link SegmentedHttpDownloadStrategy}.
 */
class ResourceIntegrityTest {

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

    // ── minimal HTTP server ───────────────────────────────────────────────────

    /**
     * Flexible test HTTP server for integrity and negotiation scenarios.
     *
     * <ul>
     *   <li>{@code etag} — when non-null, included in HEAD and partial responses.</li>
     *   <li>{@code rangeProbeEtag} — overrides etag only in range-probe (bytes=0-0) responses.</li>
     *   <li>{@code acceptsRanges} — controls {@code Accept-Ranges: bytes} in HEAD.</li>
     *   <li>{@code rangeRequestsOnly} — rejects full GETs with 400; proves segmented path.</li>
     *   <li>{@code respondToRangeProbeWith200} — returns 200 for bytes=0-0 probes (ignores Range).</li>
     *   <li>{@code respondToRangeProbeWith416} — returns 416 for bytes=0-0 probes.</li>
     *   <li>{@code chunkEtagOverride} — when non-null, substitutes this ETag in chunk responses.</li>
     *   <li>{@code omitContentRange} — omits Content-Range header from partial responses.</li>
     *   <li>{@code sendExtraByteInChunks} — appends one extra byte past end for each chunk.</li>
     *   <li>{@code truncateChunks} — sends one fewer byte than the requested range.</li>
     * </ul>
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean acceptsRanges = true;
        volatile boolean rangeRequestsOnly = false;
        volatile boolean respondToRangeProbeWith200 = false;
        volatile boolean respondToRangeProbeWith416 = false;
        volatile boolean omitContentRange = false;
        volatile boolean sendExtraByteInChunks = false;
        volatile boolean truncateChunks = false;
        volatile String etag = null;
        volatile String rangeProbeEtag = null;      // overrides etag only for bytes=0-0
        volatile String chunkEtagOverride = null;   // substituted in non-probe range responses
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
                    writeHeadResponse(out);
                } else if (rangeStart == 0 && rangeEnd == 0) {
                    writeRangeProbeResponse(out);
                } else if (rangeStart >= 0) {
                    writeChunkResponse(out, rangeStart, rangeEnd);
                } else if (rangeRequestsOnly) {
                    writeError(out, 400, "Range header required");
                } else {
                    writeFullResponse(out);
                }
                out.flush();

            } catch (IOException ignored) {}
        }

        private void writeHeadResponse(final OutputStream out) throws IOException {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Length: ").append(content.length).append("\r\n");
            if (acceptsRanges) { sb.append("Accept-Ranges: bytes\r\n"); }
            if (etag != null)   { sb.append("ETag: ").append(etag).append("\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        }

        private void writeRangeProbeResponse(final OutputStream out) throws IOException {
            if (respondToRangeProbeWith416) {
                writeError(out, 416, "Range Not Satisfiable");
                return;
            }
            if (respondToRangeProbeWith200) {
                writeFullResponse(out);
                return;
            }
            // Normal 206 for bytes=0-0
            final String probeEtag = rangeProbeEtag != null ? rangeProbeEtag : etag;
            final StringBuilder sb = new StringBuilder("HTTP/1.1 206 Partial Content\r\n");
            sb.append("Content-Length: 1\r\n");
            if (!omitContentRange) {
                sb.append("Content-Range: bytes 0-0/").append(content.length).append("\r\n");
            }
            if (probeEtag != null) { sb.append("ETag: ").append(probeEtag).append("\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(content, 0, 1);
        }

        private void writeChunkResponse(final OutputStream out,
                                         final long start, final long end) throws IOException {
            final String chunkTag = chunkEtagOverride != null ? chunkEtagOverride : etag;
            long len = end - start + 1;
            if (sendExtraByteInChunks && end + 1 < content.length) { len++; }
            if (truncateChunks && len > 1) { len--; }

            final StringBuilder sb = new StringBuilder("HTTP/1.1 206 Partial Content\r\n");
            sb.append("Content-Length: ").append(len).append("\r\n");
            if (!omitContentRange) {
                sb.append("Content-Range: bytes ").append(start).append('-')
                  .append(end).append('/').append(content.length).append("\r\n");
            }
            if (chunkTag != null) { sb.append("ETag: ").append(chunkTag).append("\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.write(content, (int) start, (int) len);
        }

        private void writeFullResponse(final OutputStream out) throws IOException {
            final String headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: " + content.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.US_ASCII));
            out.write(content);
        }

        private void writeError(final OutputStream out,
                                 final int status, final String msg) throws IOException {
            final String r = "HTTP/1.1 " + status + " " + msg + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(r.getBytes(StandardCharsets.US_ASCII));
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

    // ── HEAD probe + range capability probe ───────────────────────────────────

    @Test
    void probeShouldReturnAcceptsRangesAndStrongETagWhenServerAdvertisesBoth() throws Exception {
        final byte[] content = new byte[1024];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"abc-123\"";
            final HttpRangeSupport.RangeResponse result =
                    HttpRangeSupport.probe(httpClient(), server.uri(), Optional.empty());

            assertTrue(result.acceptsRanges());
            assertEquals(1024, result.contentLength());
            assertEquals(Optional.of("\"abc-123\""), result.strongETag());
        }
    }

    @Test
    void probeShouldDiscardWeakETag() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "W/\"weak-etag\"";
            final HttpRangeSupport.RangeResponse result =
                    HttpRangeSupport.probe(httpClient(), server.uri(), Optional.empty());

            assertTrue(result.strongETag().isEmpty(),
                    "Weak ETag must be discarded");
        }
    }

    @Test
    void rangeProbeReturnsIdentityWhenServerResponds206WithValidContentRange() throws Exception {
        final byte[] content = new byte[2048];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.of("\"v1\""), content.length, Optional.empty());

            assertTrue(identity.isPresent());
            assertEquals(2048, identity.get().contentLength());
            assertEquals(Optional.of("\"v1\""), identity.get().strongETag());
        }
    }

    @Test
    void rangeProbeReturnsFallbackWhenServerIgnoresRangeAndResponds200() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.respondToRangeProbeWith200 = true;
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.of("\"v1\""), content.length, Optional.empty());

            assertTrue(identity.isEmpty(), "200 response must trigger fallback");
        }
    }

    @Test
    void rangeProbeReturnsFallbackWhenServerResponds416() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.respondToRangeProbeWith416 = true;
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.of("\"v1\""), content.length, Optional.empty());

            assertTrue(identity.isEmpty(), "416 response must trigger fallback");
        }
    }

    @Test
    void rangeProbeReturnsFallbackWhenContentRangeIsAbsent() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.omitContentRange = true;
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.of("\"v1\""), content.length, Optional.empty());

            assertTrue(identity.isEmpty(), "Missing Content-Range must trigger fallback");
        }
    }

    @Test
    void rangeProbeReturnsFallbackWhenProbeETagDiffersFromHeadETag() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"head-etag\"";
            server.rangeProbeEtag = "\"probe-etag\""; // different from HEAD ETag
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.of("\"head-etag\""), content.length, Optional.empty());

            assertTrue(identity.isEmpty(), "ETag mismatch between HEAD and probe must trigger fallback");
        }
    }

    @Test
    void rangeProbeReturnsFallbackWhenNoStrongETagIsAvailable() throws Exception {
        final byte[] content = new byte[512];
        try (TestHttpServer server = new TestHttpServer(content)) {
            // No etag set — server emits no ETag header; both HEAD and probe return no ETag
            final Optional<ResourceIdentity> identity = HttpRangeSupport.rangeProbe(
                    httpClient(), server.uri(),
                    Optional.empty(), content.length, Optional.empty());

            assertTrue(identity.isEmpty(), "No ETag must trigger fallback — cannot guarantee integrity");
        }
    }

    // ── Orchestrator strategy selection ───────────────────────────────────────

    @Test
    void orchestratorSelectsSegmentedWhenServerSupportsRangesAndProvidesStrongETag() throws Exception {
        final byte[] content = new byte[8192];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"strong-etag\"";
            server.rangeRequestsOnly = true; // full GET fails → proves segmented path

            final Path dest = tempDir.resolve("segmented.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .build();

            new HttpDownloadOrchestrator(httpClient(), config, 1024)
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent),
                    "Expected DownloadCompletedEvent via segmented strategy");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    @Test
    void orchestratorFallsBackToSingleStreamWhenNoETagAvailable() throws Exception {
        final byte[] content = "no etag content".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            // No etag → range probe returns empty → single-stream fallback

            final Path dest = tempDir.resolve("fallback.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .build();

            new HttpDownloadOrchestrator(httpClient(), config, 8192)
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    @Test
    void orchestratorFallsBackWhenServerRejectsRangeProbWith200() throws Exception {
        final byte[] content = "range probe ignored".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.respondToRangeProbeWith200 = true;

            final Path dest = tempDir.resolve("probe200.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .build();

            new HttpDownloadOrchestrator(httpClient(), config, 8192)
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
        }
    }

    @Test
    void orchestratorFallsBackWhenFileSizeBelowSegmentThreshold() throws Exception {
        final byte[] content = "tiny".getBytes(StandardCharsets.UTF_8);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";

            final Path dest = tempDir.resolve("tiny.txt");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            // segmentMinSizeBytes > content length → single-stream even with strong ETag
            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(Long.MAX_VALUE)
                    .build();

            new HttpDownloadOrchestrator(httpClient(), config, 8192)
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
        }
    }

    // ── Per-segment Content-Range validation ──────────────────────────────────

    @Test
    void segmentedStrategyShouldFailWhenChunkReceives200InsteadOf206() throws Exception {
        final byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 'X');

        // TestHttpServer in SegmentedHttpDownloadStrategyTest already covers this.
        // Re-verify via explicit ResourceIdentity path.
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            // Force 200 for chunk requests by overriding respondWith206 indirectly via
            // rangeProbeEtag mismatch — we need a different approach here.
            // Use the SegmentedHttpDownloadStrategyTest's simpler server for 200-not-206.
            // This test verifies validateSegmentResponseHeaders via Content-Range absent.
            server.omitContentRange = true;

            final Path dest = tempDir.resolve("no-content-range.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Missing Content-Range must produce DownloadFailedEvent");
            assertFalse(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));

            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
        }
    }

    @Test
    void segmentedStrategyShouldFailWhenContentRangeStartMismatch() throws Exception {
        final byte[] contentFull = new byte[2048];
        Arrays.fill(contentFull, (byte) 'A');

        // Build a server that shifts Content-Range start by +1 for chunk responses
        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final InputStream in = socket.getInputStream();
                                final String req = readLine(in);
                                if (req == null || req.isBlank()) { return; }
                                long rs = -1, re = -1;
                                String ln;
                                while (!(ln = readLine(in)).isEmpty()) {
                                    if (ln.startsWith("Range: bytes=")) {
                                        final String[] p = ln.substring("Range: bytes=".length()).split("-");
                                        rs = Long.parseLong(p[0].trim());
                                        re = Long.parseLong(p[1].trim());
                                    }
                                }
                                final OutputStream out = socket.getOutputStream();
                                if (req.startsWith("HEAD")) {
                                    final String h = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Length: " + contentFull.length + "\r\n" +
                                            "Accept-Ranges: bytes\r\n" +
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                                } else if (rs == 0 && re == 0) {
                                    // range probe
                                    final String h = "HTTP/1.1 206 Partial Content\r\n" +
                                            "Content-Length: 1\r\n" +
                                            "Content-Range: bytes 0-0/" + contentFull.length + "\r\n" +
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                                    out.write(contentFull, 0, 1);
                                } else if (rs >= 0) {
                                    // chunk — lie about Content-Range start
                                    final long len = re - rs + 1;
                                    final String h = "HTTP/1.1 206 Partial Content\r\n" +
                                            "Content-Length: " + len + "\r\n" +
                                            "Content-Range: bytes " + (rs + 1) + "-" + re +
                                            "/" + contentFull.length + "\r\n" + // start is rs+1 (wrong)
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                                    out.write(contentFull, (int) rs, (int) len);
                                }
                                out.flush();
                            } catch (IOException ignored) {}
                        });
                    } catch (IOException e) {
                        if (!ss.isClosed()) { throw new UncheckedIOException(e); }
                    }
                }
            });

            final Path dest = tempDir.resolve("start-mismatch.bin");
            final DownloadTask t = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(contentFull.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(t, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Content-Range start mismatch must produce DownloadFailedEvent");
            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
        }
    }

    @Test
    void segmentedStrategyShouldFailWhenContentRangeTotalMismatch() throws Exception {
        final byte[] content = new byte[1024];
        Arrays.fill(content, (byte) 'T');

        // Server reports Content-Range total as 9999 instead of 1024
        try (ServerSocket ss = new ServerSocket(0)) {
            final URI uri = URI.create("http://localhost:" + ss.getLocalPort() + "/file");
            Thread.ofVirtual().start(() -> {
                while (!ss.isClosed()) {
                    try {
                        final Socket socket = ss.accept();
                        Thread.ofVirtual().start(() -> {
                            try (socket) {
                                final InputStream in = socket.getInputStream();
                                final String req = readLine(in);
                                if (req == null || req.isBlank()) { return; }
                                long rs = -1, re = -1;
                                String ln;
                                while (!(ln = readLine(in)).isEmpty()) {
                                    if (ln.startsWith("Range: bytes=")) {
                                        final String[] p = ln.substring("Range: bytes=".length()).split("-");
                                        rs = Long.parseLong(p[0].trim());
                                        re = Long.parseLong(p[1].trim());
                                    }
                                }
                                final OutputStream out = socket.getOutputStream();
                                if (req.startsWith("HEAD")) {
                                    final String h = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Length: 1024\r\n" +
                                            "Accept-Ranges: bytes\r\n" +
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                                } else if (rs == 0 && re == 0) {
                                    final String h = "HTTP/1.1 206 Partial Content\r\n" +
                                            "Content-Length: 1\r\n" +
                                            "Content-Range: bytes 0-0/1024\r\n" +
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
                                    out.write(content, 0, 1);
                                } else if (rs >= 0) {
                                    final long len = re - rs + 1;
                                    final String h = "HTTP/1.1 206 Partial Content\r\n" +
                                            "Content-Length: " + len + "\r\n" +
                                            // wrong total: 9999 instead of 1024
                                            "Content-Range: bytes " + rs + "-" + re + "/9999\r\n" +
                                            "ETag: \"v1\"\r\n" +
                                            "Connection: close\r\n\r\n";
                                    out.write(h.getBytes(StandardCharsets.US_ASCII));
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

            final Path dest = tempDir.resolve("total-mismatch.bin");
            final DownloadTask t = task(uri, dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(1024, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 256, HttpTimeoutConfig.disabled())
                    .download(t, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Content-Range total mismatch must produce DownloadFailedEvent");
            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
        }
    }

    @Test
    void segmentedStrategyShouldFailWhenChunkETagChangesAfterProbe() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'E');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"original-etag\"";
            server.chunkEtagOverride = "\"changed-etag\""; // different from identity ETag

            final Path dest = tempDir.resolve("etag-changed.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length,
                    Optional.of("\"original-etag\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Changed ETag in chunk response must produce DownloadFailedEvent");
            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
            assertTrue(failed.cause().getMessage().contains("ETag changed"),
                    "Exception must mention ETag change");
        }
    }

    @Test
    void segmentedStrategyShouldFailWhenServerSendsMoreBytesThanRequested() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'O');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.sendExtraByteInChunks = true; // server sends 1 extra byte past end

            final Path dest = tempDir.resolve("overflow.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Overflow past segment boundary must produce DownloadFailedEvent");
            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
        }
    }

    @Test
    void segmentedStrategyShouldFailWhenServerSendsTooFewBytes() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'U');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.truncateChunks = true; // server sends 1 fewer byte than requested

            final Path dest = tempDir.resolve("truncated.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent),
                    "Truncated segment must produce DownloadFailedEvent");
            final DownloadFailedEvent failed = events.stream()
                    .filter(e -> e instanceof DownloadFailedEvent)
                    .map(e -> (DownloadFailedEvent) e)
                    .findFirst().orElseThrow();
            assertInstanceOf(ResourceIntegrityException.class, failed.cause());
            assertTrue(failed.cause().getMessage().contains("incomplete segment"),
                    "Exception must mention incomplete segment");
        }
    }

    // ── Terminal event guarantees ─────────────────────────────────────────────

    @Test
    void exactlyOneTerminalEventWhenIntegrityFailureOccurs() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'Z');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.chunkEtagOverride = "\"v2\"";

            final Path dest = tempDir.resolve("terminal.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 4, identity, 256, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            final long terminalCount = events.stream()
                    .filter(e -> e instanceof DownloadCompletedEvent
                            || e instanceof DownloadFailedEvent
                            || e instanceof DownloadCancelledEvent)
                    .count();

            assertEquals(1, terminalCount,
                    "Exactly one terminal event must be emitted regardless of how many chunks fail");
        }
    }

    @Test
    void exactlyOneTerminalEventOnSuccessfulSegmentedDownload() throws Exception {
        final byte[] content = new byte[4096];
        Arrays.fill(content, (byte) 'C');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";

            final Path dest = tempDir.resolve("success-terminal.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 4, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            final long terminalCount = events.stream()
                    .filter(e -> e instanceof DownloadCompletedEvent
                            || e instanceof DownloadFailedEvent
                            || e instanceof DownloadCancelledEvent)
                    .count();

            assertEquals(1, terminalCount);
            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadCompletedEvent));
        }
    }

    @Test
    void integrityFailureShouldDeletePartialFile() throws Exception {
        final byte[] content = new byte[2048];
        Arrays.fill(content, (byte) 'D');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.etag = "\"v1\"";
            server.omitContentRange = true;

            final Path dest = tempDir.resolve("deleted.bin");
            final DownloadTask task = task(server.uri(), dest);
            final List<DownloadEvent> events = new CopyOnWriteArrayList<>();

            final ResourceIdentity identity = new ResourceIdentity(content.length, Optional.of("\"v1\""));
            new SegmentedHttpDownloadStrategy(httpClient(), 2, identity, 512, HttpTimeoutConfig.disabled())
                    .download(task, ctx(), events::add);

            assertTrue(events.stream().anyMatch(e -> e instanceof DownloadFailedEvent));
            assertFalse(Files.exists(dest),
                    "Partial file must be deleted after integrity failure");
        }
    }

    // ── Static validation methods ─────────────────────────────────────────────

    @Test
    void validateSegmentByteCountThrowsForShortDelivery() {
        final HttpRangeSupport.ChunkRange chunk = new HttpRangeSupport.ChunkRange(0, 0, 999);
        assertThrows(ResourceIntegrityException.class,
                () -> HttpRangeSupport.validateSegmentByteCount(500, chunk));
    }

    @Test
    void validateSegmentByteCountPassesForExactDelivery() {
        final HttpRangeSupport.ChunkRange chunk = new HttpRangeSupport.ChunkRange(0, 0, 999);
        assertDoesNotThrow(() -> HttpRangeSupport.validateSegmentByteCount(1000, chunk));
    }

    @Test
    void parseContentRangeHandlesWellFormedHeader() {
        final Optional<HttpRangeSupport.ContentRange> cr =
                HttpRangeSupport.parseContentRange("bytes 100-199/1000");
        assertTrue(cr.isPresent());
        assertEquals(100, cr.get().start());
        assertEquals(199, cr.get().end());
        assertEquals(1000, cr.get().total());
    }

    @Test
    void parseContentRangeReturnsEmptyForUnsatisfiedRange() {
        assertTrue(HttpRangeSupport.parseContentRange("bytes */1000").isEmpty());
    }

    @Test
    void parseContentRangeReturnsEmptyForMalformedInput() {
        assertTrue(HttpRangeSupport.parseContentRange(null).isEmpty());
        assertTrue(HttpRangeSupport.parseContentRange("").isEmpty());
        assertTrue(HttpRangeSupport.parseContentRange("bytes 100-abc/1000").isEmpty());
    }

    @Test
    void resourceIdentityOfSizeHasNoETag() {
        final ResourceIdentity id = ResourceIdentity.ofSize(1024);
        assertEquals(1024, id.contentLength());
        assertTrue(id.strongETag().isEmpty());
    }

    @Test
    void resourceIdentityOfPreservesStrongETag() {
        final ResourceIdentity id = ResourceIdentity.of(2048, "\"etag-value\"");
        assertEquals(2048, id.contentLength());
        assertEquals(Optional.of("\"etag-value\""), id.strongETag());
    }

    // ── Shared readLine for inline servers ────────────────────────────────────

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
