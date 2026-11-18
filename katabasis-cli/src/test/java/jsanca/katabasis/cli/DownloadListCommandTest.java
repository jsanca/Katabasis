package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadControlResult;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * K-0.2-T05 — CLI integration tests: TC-CLI-LIST-001 through TC-CLI-LIST-014.
 *
 * <p>All HTTP-backed tests use a controlled in-process {@link TestHttpServer}.
 * No test calls {@link System#exit}; exit codes are obtained from
 * {@link CommandLine#execute(String...)}.
 */
class DownloadListCommandTest {

    @TempDir
    Path tempDir;

    // ── in-process HTTP server ────────────────────────────────────────────────

    /**
     * Minimal HTTP/1.1 server for URL-list CLI integration tests.
     *
     * <p>Routes are registered per path: {@link #serve} maps a path to fixed content;
     * {@link #failWith} maps a path to an HTTP error status. Paths not matching either
     * map return 404. Query strings in incoming requests are stripped before lookup.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final Map<String, byte[]> routes = new ConcurrentHashMap<>();
        private final Map<String, Integer> errorRoutes = new ConcurrentHashMap<>();
        private final Map<String, byte[]> rangeRoutes = new ConcurrentHashMap<>();
        private final Map<String, String> etagMap = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> pathCounts = new ConcurrentHashMap<>();
        private final Map<String, byte[]> barrierRoutes = new ConcurrentHashMap<>();
        private final Map<String, CountDownLatch[]> barrierLatches = new ConcurrentHashMap<>();
        final AtomicInteger barrierInflight = new AtomicInteger(0);
        final AtomicInteger barrierPeak = new AtomicInteger(0);
        private volatile boolean closed = false;
        final AtomicInteger requestCount = new AtomicInteger(0);

        TestHttpServer() throws IOException {
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        URI uriFor(String path) {
            return URI.create("http://localhost:" + port() + path);
        }

        void serve(String path, byte[] content) {
            routes.put(path, content);
        }

        void failWith(String path, int statusCode) {
            errorRoutes.put(path, statusCode);
        }

        /**
         * Registers a path as a barrier route. The server increments {@link #barrierInflight} when a request
         * arrives, waits on {@code releaseSignal}, then decrements and responds. {@code arrivedSignal} is
         * counted down on each arriving request so the test can observe when N requests are inflight.
         */
        void serveWithBarrier(String path, byte[] content, CountDownLatch arrivedSignal, CountDownLatch releaseSignal) {
            barrierRoutes.put(path, content);
            barrierLatches.put(path, new CountDownLatch[]{arrivedSignal, releaseSignal});
        }

        /** Registers a path as range-capable; {@code etag} must be a quoted strong ETag, e.g. {@code "\"v1\""}. */
        void serveWithRanges(String path, byte[] content, String etag) {
            rangeRoutes.put(path, content);
            etagMap.put(path, etag);
            pathCounts.put(path, new AtomicInteger(0));
        }

        int pathRequestCount(String path) {
            final AtomicInteger c = pathCounts.get(path);
            return c == null ? 0 : c.get();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    final Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (!closed) {
                        throw new UncheckedIOException(e);
                    }
                }
            }
        }

        private void handleConnection(Socket socket) {
            try (socket) {
                final InputStream in = socket.getInputStream();
                final String requestLine = readLine(in);
                if (requestLine == null || requestLine.isBlank()) {
                    return;
                }
                requestCount.incrementAndGet();

                final String[] parts = requestLine.split(" ");
                final String method = parts.length > 0 ? parts[0] : "GET";
                final String rawPath = parts.length > 1 ? parts[1] : "/";
                final int qMark = rawPath.indexOf('?');
                final String pathOnly = qMark >= 0 ? rawPath.substring(0, qMark) : rawPath;

                String rangeHeader = null;
                String line;
                while (!(line = readLine(in)).isEmpty()) {
                    if (line.toLowerCase().startsWith("range:")) {
                        rangeHeader = line.substring(line.indexOf(':') + 1).trim();
                    }
                }

                final OutputStream out = socket.getOutputStream();

                if (barrierRoutes.containsKey(pathOnly)) {
                    final byte[] content = barrierRoutes.get(pathOnly);
                    final CountDownLatch[] latches = barrierLatches.get(pathOnly);
                    final int now = barrierInflight.incrementAndGet();
                    barrierPeak.updateAndGet(p -> Math.max(p, now));
                    latches[0].countDown();
                    try { latches[1].await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    barrierInflight.decrementAndGet();
                    out.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + content.length + "\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(content);
                } else if (rangeRoutes.containsKey(pathOnly)) {
                    final AtomicInteger counter = pathCounts.get(pathOnly);
                    if (counter != null) {
                        counter.incrementAndGet();
                    }
                    handleRangeRoute(out, pathOnly, method, rangeHeader);
                } else if (errorRoutes.containsKey(pathOnly)) {
                    final int code = errorRoutes.get(pathOnly);
                    out.write(("HTTP/1.1 " + code + " Error\r\n"
                            + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    final byte[] content = routes.getOrDefault(pathOnly, new byte[0]);
                    out.write(("HTTP/1.1 200 OK\r\n"
                            + "Content-Length: " + content.length + "\r\n"
                            + "Connection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(content);
                }
                out.flush();
            } catch (IOException ignored) {
                // connection closed by client — normal during failure tests
            }
        }

        private void handleRangeRoute(OutputStream out, String path, String method, String rangeHeader)
                throws IOException {
            final byte[] content = rangeRoutes.get(path);
            final String etag = etagMap.get(path);
            if ("HEAD".equals(method)) {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Length: " + content.length + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "ETag: " + etag + "\r\n"
                        + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            } else if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                final String range = rangeHeader.substring("bytes=".length());
                final String[] bounds = range.split("-");
                final int start = Integer.parseInt(bounds[0]);
                final int end = bounds.length > 1 && !bounds[1].isBlank()
                        ? Integer.parseInt(bounds[1])
                        : content.length - 1;
                final byte[] chunk = Arrays.copyOfRange(content, start, end + 1);
                out.write(("HTTP/1.1 206 Partial Content\r\n"
                        + "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n"
                        + "Content-Length: " + chunk.length + "\r\n"
                        + "ETag: " + etag + "\r\n"
                        + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(chunk);
            } else {
                out.write(("HTTP/1.1 200 OK\r\n"
                        + "Content-Length: " + content.length + "\r\n"
                        + "Accept-Ranges: bytes\r\n"
                        + "ETag: " + etag + "\r\n"
                        + "Connection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                out.write(content);
            }
        }

        private String readLine(InputStream in) throws IOException {
            final StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') {
                    in.read(); // consume LF
                    break;
                }
                if (c == '\n') {
                    break;
                }
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private static int execute(DownloadListCommand cmd, String... args) {
        return new CommandLine(cmd).execute(args);
    }

    private static DownloadManager newManager() {
        return DownloadManagers.createDefault();
    }

    private Path writeUrlList(String content) throws IOException {
        final Path file = tempDir.resolve("urls.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ── TC-CLI-LIST-001 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-001: Valid list with three URLs — all complete, all files created, exit 0.
     */
    @Test
    void allUrlsCompleteSuccessfully() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/a.bin", bytes("AAA"));
            server.serve("/b.bin", bytes("BBB"));
            server.serve("/c.bin", bytes("CCC"));

            final Path urlList = writeUrlList(
                    server.uriFor("/a.bin") + "\n"
                    + server.uriFor("/b.bin") + "\n"
                    + server.uriFor("/c.bin") + "\n");
            final Path outDir = tempDir.resolve("out");

            final PrintStream out = new PrintStream(new ByteArrayOutputStream());
            final PrintStream err = new PrintStream(new ByteArrayOutputStream());
            final int code = execute(new DownloadListCommand(newManager(), out, err),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            assertTrue(Files.exists(outDir.resolve("a.bin")));
            assertTrue(Files.exists(outDir.resolve("b.bin")));
            assertTrue(Files.exists(outDir.resolve("c.bin")));
            assertArrayEquals(bytes("AAA"), Files.readAllBytes(outDir.resolve("a.bin")));
        }
    }

    // ── TC-CLI-LIST-002 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-002: One URL returns HTTP 500 — partial success, exit 3 (PARTIAL_BATCH_FAILURE).
     *
     * <p>Exit code 3 distinguishes partial batch failure (some succeeded) from
     * all-failed batch (exit 1). This is the K-0.2-T08 compatibility decision:
     * code 3 replaces the prior code 1 for partial failure.
     */
    @Test
    void oneUrlFailsExitCodeIsThree() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/good.bin", bytes("OK"));
            server.failWith("/bad.bin", 500);

            final Path urlList = writeUrlList(
                    server.uriFor("/good.bin") + "\n"
                    + server.uriFor("/bad.bin") + "\n");
            final Path outDir = tempDir.resolve("out");

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(3, code, "partial batch failure must exit 3");
            assertTrue(Files.exists(outDir.resolve("good.bin")));
        }
    }

    // ── TC-CLI-LIST-003 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-003: All URLs fail — exit 1.
     */
    @Test
    void allUrlsFailExitCodeIsOne() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.failWith("/x.bin", 503);

            final Path urlList = writeUrlList(server.uriFor("/x.bin").toString());
            final Path outDir = tempDir.resolve("out");

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
        }
    }

    // ── TC-CLI-LIST-004 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-004: URL list contains only blank lines and comments — exit 1.
     */
    @Test
    void emptyListExitsOne() throws Exception {
        final Path urlList = writeUrlList("# comment\n\n   \n# another comment\n");
        final Path outDir = tempDir.resolve("out");
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final int code = execute(new DownloadListCommand(newManager(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuf)),
                urlList.toString(), "--output-dir", outDir.toString());

        assertEquals(1, code);
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("No URLs"));
    }

    // ── TC-CLI-LIST-005 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-005: URL list mixes valid URLs, blank lines, and comments — only valid URLs are downloaded.
     */
    @Test
    void blankLinesAndCommentsAreSkipped() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/p.bin", bytes("P"));
            server.serve("/q.bin", bytes("Q"));

            final Path urlList = writeUrlList(
                    "# first comment\n"
                    + "\n"
                    + server.uriFor("/p.bin") + "\n"
                    + "   \n"
                    + "# second comment\n"
                    + server.uriFor("/q.bin") + "\n");
            final Path outDir = tempDir.resolve("out");

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            assertTrue(Files.exists(outDir.resolve("p.bin")));
            assertTrue(Files.exists(outDir.resolve("q.bin")));
        }
    }

    // ── TC-CLI-LIST-006 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-006: URL list file does not exist — exit 1.
     */
    @Test
    void nonExistentListFileExitsOne() throws Exception {
        final Path missing = tempDir.resolve("does-not-exist.txt");
        final Path outDir = tempDir.resolve("out");
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final int code = execute(new DownloadListCommand(newManager(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuf)),
                missing.toString(), "--output-dir", outDir.toString());

        assertEquals(1, code);
        assertFalse(errBuf.toString(StandardCharsets.UTF_8).isBlank());
    }

    // ── TC-CLI-LIST-007 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-007: Missing {@code --output-dir} option — picocli returns exit 2.
     */
    @Test
    void missingOutputDirOptionExitsTwo() throws Exception {
        final Path urlList = writeUrlList("http://example.com/file.bin");

        final int code = execute(new DownloadListCommand((DownloadManager) null,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())),
                urlList.toString());

        assertEquals(2, code);
    }

    // ── TC-CLI-LIST-008 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-008: Missing url-list-file positional argument — picocli returns exit 2.
     */
    @Test
    void missingFileArgumentExitsTwo() throws Exception {
        final int code = execute(new DownloadListCommand((DownloadManager) null,
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(new ByteArrayOutputStream())),
                "--output-dir", tempDir.resolve("out").toString());

        assertEquals(2, code);
    }

    // ── TC-CLI-LIST-009 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-009: URL list contains a syntactically invalid URI — exit 1 with error details.
     */
    @Test
    void invalidUriInListExitsOne() throws Exception {
        final Path urlList = writeUrlList("http://valid.example.com/file.bin\nnot a valid uri here!@#\n");
        final Path outDir = tempDir.resolve("out");
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        final int code = execute(new DownloadListCommand(newManager(),
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errBuf)),
                urlList.toString(), "--output-dir", outDir.toString());

        assertEquals(1, code);
        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("Line 2"), "error should mention line number");
    }

    // ── TC-CLI-LIST-010 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-010: URL with no derivable filename (path is {@code /}) — exit 1.
     */
    @Test
    void urlWithNoDerivableFilenameExitsOne() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            final Path urlList = writeUrlList("http://localhost:" + server.port() + "/");
            final Path outDir = tempDir.resolve("out");
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
            assertFalse(errBuf.toString(StandardCharsets.UTF_8).isBlank());
        }
    }

    // ── TC-CLI-LIST-011 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-011: Two URLs resolve to the same output filename — exit 1 with conflict error.
     */
    @Test
    void duplicateOutputFilenamesExitsOne() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("first"));
            server.serve("/sub/file.bin", bytes("second"));

            final Path urlList = writeUrlList(
                    server.uriFor("/file.bin") + "\n"
                    + server.uriFor("/sub/file.bin") + "\n");
            final Path outDir = tempDir.resolve("out");
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
            assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("conflicts"));
        }
    }

    // ── TC-CLI-LIST-012 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-012: URL with query string — output filename is derived from path only, not the query.
     */
    @Test
    void urlWithQueryStringFilenameIsStripped() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/data.bin", bytes("DATA"));

            final Path urlList = writeUrlList(server.uriFor("/data.bin?version=2&ts=123").toString());
            final Path outDir = tempDir.resolve("out");

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            assertTrue(Files.exists(outDir.resolve("data.bin")));
            assertArrayEquals(bytes("DATA"), Files.readAllBytes(outDir.resolve("data.bin")));
        }
    }

    // ── TC-CLI-LIST-013 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-013: Output directory does not exist — command creates it and succeeds.
     */
    @Test
    void outputDirectoryCreatedIfAbsent() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("CONTENT"));

            final Path urlList = writeUrlList(server.uriFor("/file.bin").toString());
            final Path outDir = tempDir.resolve("newdir/nested");

            assertFalse(Files.exists(outDir));

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            assertTrue(Files.isDirectory(outDir));
            assertTrue(Files.exists(outDir.resolve("file.bin")));
        }
    }

    // ── TC-CLI-LIST-014 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-014: Summary line reports correct completed and failed counts.
     */
    @Test
    void summaryReportsCorrectCounts() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/ok1.bin", bytes("OK"));
            server.serve("/ok2.bin", bytes("OK"));
            server.failWith("/fail.bin", 500);

            final Path urlList = writeUrlList(
                    server.uriFor("/ok1.bin") + "\n"
                    + server.uriFor("/ok2.bin") + "\n"
                    + server.uriFor("/fail.bin") + "\n");
            final Path outDir = tempDir.resolve("out");
            final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

            execute(new DownloadListCommand(newManager(),
                    new PrintStream(outBuf),
                    new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            final String outText = outBuf.toString(StandardCharsets.UTF_8);
            assertTrue(outText.contains("completed=2"), "stdout: " + outText);
            assertTrue(outText.contains("failed=1"), "stdout: " + outText);
        }
    }

    // ── TC-CLI-LIST-015 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-015: Output file already exists at the resolved path — exit 1 with error,
     * and no downloads are submitted (pre-validation rejects the batch before any transfer).
     */
    @Test
    void existingOutputFileIsRejected() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("NEW CONTENT"));

            final Path outDir = tempDir.resolve("out");
            Files.createDirectories(outDir);
            final Path existing = outDir.resolve("file.bin");
            Files.writeString(existing, "EXISTING", StandardCharsets.UTF_8);

            final Path urlList = writeUrlList(server.uriFor("/file.bin").toString());
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
            assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("already exists"),
                    "stderr should mention 'already exists'");
            // The existing file must not have been overwritten
            assertEquals("EXISTING", Files.readString(existing, StandardCharsets.UTF_8));
        }
    }

    // ── TC-CLI-LIST-016 ───────────────────────────────────────────────────────

    /**
     * TC-CLI-LIST-016: Batch contains one simple URL and one range-capable URL — both complete,
     * correct bytes written, and the range-capable URL triggers segmented strategy
     * (verified by per-path request count: 1 HEAD + 1 range probe + segmentCount chunk GETs).
     */
    @Test
    void mixedSingleStreamAndSegmentedDownloads() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            final byte[] simpleContent = bytes("SIMPLE");
            final byte[] segContent = bytes("SEGMENTED");
            server.serve("/simple.bin", simpleContent);
            server.serveWithRanges("/seg.bin", segContent, "\"etag-seg\"");

            final Path urlList = writeUrlList(
                    server.uriFor("/simple.bin") + "\n"
                    + server.uriFor("/seg.bin") + "\n");
            final Path outDir = tempDir.resolve("out");

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentCount(2)
                    .segmentMinSizeBytes(1L)
                    .build();
            final int code = execute(
                    new DownloadListCommand(DownloadManagers.createDefault(config),
                            new PrintStream(new ByteArrayOutputStream()),
                            new PrintStream(new ByteArrayOutputStream())),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            assertArrayEquals(simpleContent, Files.readAllBytes(outDir.resolve("simple.bin")));
            assertArrayEquals(segContent, Files.readAllBytes(outDir.resolve("seg.bin")));
            // 1 HEAD + 1 range probe + 2 chunk GETs = 4 requests for the segmented path
            final int segRequests = server.pathRequestCount("/seg.bin");
            assertTrue(segRequests >= 4,
                    "Expected ≥4 requests for segmented path (HEAD + probe + 2 chunks), got " + segRequests);
        }
    }

    // ── TC-HARDEN-A-001 ──────────────────────────────────────────────────────

    /**
     * TC-HARDEN-A-001: maxConcurrentDownloads=2 with 4 URLs — at most 2 downloads run simultaneously.
     *
     * <p>Server holds each request at a barrier until the test has observed and asserted
     * peak in-flight concurrency. The 3rd and 4th downloads are blocked on the manager
     * semaphore and cannot reach the server while the first two are held.
     */
    @Test
    void concurrencyLimitIsRespected() throws Exception {
        final int maxConcurrent = 2;
        final CountDownLatch twoArrived = new CountDownLatch(maxConcurrent);
        final CountDownLatch release = new CountDownLatch(1);

        try (TestHttpServer server = new TestHttpServer()) {
            server.serveWithBarrier("/a.bin", bytes("A"), twoArrived, release);
            server.serveWithBarrier("/b.bin", bytes("B"), twoArrived, release);
            server.serveWithBarrier("/c.bin", bytes("C"), twoArrived, release);
            server.serveWithBarrier("/d.bin", bytes("D"), twoArrived, release);

            final Path urlList = writeUrlList(
                    server.uriFor("/a.bin") + "\n"
                    + server.uriFor("/b.bin") + "\n"
                    + server.uriFor("/c.bin") + "\n"
                    + server.uriFor("/d.bin") + "\n");
            final Path outDir = tempDir.resolve("out");

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .maxConcurrentDownloads(maxConcurrent)
                    .build();

            final int[] exitCode = {-1};
            final Thread runner = Thread.ofVirtual().start(() ->
                    exitCode[0] = execute(
                            new DownloadListCommand(DownloadManagers.createDefault(config),
                                    new PrintStream(new ByteArrayOutputStream()),
                                    new PrintStream(new ByteArrayOutputStream())),
                            urlList.toString(), "--output-dir", outDir.toString()));

            // Wait until exactly maxConcurrent downloads are at the server barrier.
            // The other 2 are blocked on the manager semaphore and cannot arrive.
            assertTrue(twoArrived.await(10, TimeUnit.SECONDS),
                    "Timed out waiting for " + maxConcurrent + " inflight downloads");
            assertEquals(maxConcurrent, server.barrierInflight.get(),
                    "Expected exactly maxConcurrent inflight at barrier");
            assertTrue(server.barrierPeak.get() <= maxConcurrent,
                    "Peak inflight exceeded maxConcurrentDownloads: " + server.barrierPeak.get());

            release.countDown();
            runner.join(30_000);

            assertEquals(0, exitCode[0]);
            assertTrue(Files.exists(outDir.resolve("a.bin")));
            assertTrue(Files.exists(outDir.resolve("d.bin")));
        }
    }

    // ── TC-HARDEN-B-001 ──────────────────────────────────────────────────────

    /**
     * TC-HARDEN-B-001: Manager is closed before the command runs — synchronous rejection
     * from {@code manager.download()} is caught, latch is correctly decremented, and the
     * command exits 1 without hanging.
     */
    @Test
    void closedManagerExitsCleanlyWithExitOne() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("DATA"));

            final Path urlList = writeUrlList(server.uriFor("/file.bin").toString());
            final Path outDir = tempDir.resolve("out");
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final DownloadManager manager = newManager();
            manager.close();

            final int code = execute(new DownloadListCommand(manager,
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
            assertFalse(errBuf.toString(StandardCharsets.UTF_8).isBlank(),
                    "stderr should contain a rejection message");
        }
    }

    // ── TC-HARDEN-B-002 ──────────────────────────────────────────────────────

    /**
     * TC-HARDEN-B-002: Two terminal events fired for the same download ID — the second event
     * is silently ignored; the latch decrements exactly once per download, and the outcome
     * reflects the first event (Completed).
     */
    @Test
    void duplicateTerminalEventIsIgnored() throws Exception {
        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("DATA"));

            final Path urlList = writeUrlList(server.uriFor("/file.bin").toString());
            final Path outDir = tempDir.resolve("out");
            final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            // Fake manager fires two DownloadCompletedEvents for the same downloadId.
            final String fakeId = "fake-id-001";
            final Path targetPath = outDir.resolve("file.bin");
            final DownloadInfo fakeInfo = new DownloadInfo(fakeId,
                    server.uriFor("/file.bin"), targetPath);
            final DownloadCompletedEvent event = new DownloadCompletedEvent(
                    fakeInfo, targetPath, 4L, null, Duration.ZERO, Instant.now());

            final DownloadManager fakeManager = new DownloadManager() {
                private volatile Consumer<DownloadEvent> listener;

                @Override
                public DownloadSubmissionResult download(DownloadRequest request) {
                    // Write the file so DownloadListCommand sees content
                    try {
                        Files.createDirectories(targetPath.getParent());
                        Files.write(targetPath, bytes("DATA"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (listener != null) {
                        listener.accept(event);  // first terminal event
                        listener.accept(event);  // duplicate — must be ignored
                    }
                    return new DownloadSubmissionResult(fakeId, DownloadStatus.COMPLETED, Instant.now());
                }

                @Override public DownloadControlResult cancel(String id) { return null; }
                @Override public DownloadControlResult pause(String id)  { return null; }
                @Override public DownloadControlResult resume(String id) { return null; }
                @Override public void addListener(Consumer<DownloadEvent> l)    { this.listener = l; }
                @Override public void removeListener(Consumer<DownloadEvent> l) { this.listener = null; }
                @Override public void close() {}
            };

            final int code = execute(new DownloadListCommand(fakeManager,
                    new PrintStream(outBuf),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(0, code);
            final String outText = outBuf.toString(StandardCharsets.UTF_8);
            assertTrue(outText.contains("completed=1"), "stdout: " + outText);
            assertTrue(outText.contains("failed=0"), "stdout: " + outText);
        }
    }

    // ── TC-HARDEN-C-001 ──────────────────────────────────────────────────────

    /**
     * TC-HARDEN-C-001: Destination path is a symlink to a nonexistent target — the symlink
     * is detected during pre-validation and the command exits 1 without overwriting or
     * following the link.
     */
    @Test
    void symlinkDestinationIsRejected() throws Exception {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                "Symbolic link test skipped on Windows");

        try (TestHttpServer server = new TestHttpServer()) {
            server.serve("/file.bin", bytes("DATA"));

            final Path outDir = tempDir.resolve("out");
            Files.createDirectories(outDir);
            final Path symlink = outDir.resolve("file.bin");
            Files.createSymbolicLink(symlink, outDir.resolve("nonexistent-target.bin"));

            final Path urlList = writeUrlList(server.uriFor("/file.bin").toString());
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

            final int code = execute(new DownloadListCommand(newManager(),
                    new PrintStream(new ByteArrayOutputStream()),
                    new PrintStream(errBuf)),
                    urlList.toString(), "--output-dir", outDir.toString());

            assertEquals(1, code);
            final String errText = errBuf.toString(StandardCharsets.UTF_8);
            assertTrue(errText.contains("symbolic link"), "stderr should mention 'symbolic link': " + errText);
            // The symlink must not have been replaced or its target created
            assertTrue(Files.isSymbolicLink(symlink), "symlink should still exist and be a symlink");
            assertFalse(Files.exists(outDir.resolve("nonexistent-target.bin")),
                    "symlink target should not have been created");
        }
    }
}
