package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.exception.DownloadRejectedException;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.2-T04 — CLI integration tests: TC-CLI-001 through TC-CLI-010.
 *
 * <p>All HTTP-backed tests use a controlled in-process {@link TestHttpServer}.
 * No test calls {@link System#exit}; exit codes are obtained from
 * {@link CommandLine#execute(String...)}.
 */
class DownloadCommandTest {

    @TempDir
    Path tempDir;

    // ── in-process HTTP server ────────────────────────────────────────────────

    /**
     * Minimal HTTP/1.1 server for CLI integration tests.
     *
     * <p>Supports HEAD + GET, range requests (206), error injection,
     * per-write throttling, and optional omission of Content-Length.
     */
    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile boolean acceptsRanges = false;
        volatile String etag = null;
        volatile int perWriteDelayMs = 0;
        volatile int failStatusCode = 0; // 0 = no failure
        volatile boolean omitContentLengthInGet = false;
        private volatile boolean closed = false;
        private final AtomicInteger requestCount = new AtomicInteger(0);

        TestHttpServer(byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file.bin"); }

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

        private void handleConnection(Socket socket) {
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

                requestCount.incrementAndGet();

                if (failStatusCode > 0) {
                    writeError(out, failStatusCode);
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
                // connection closed by client — normal during failure tests
            }
        }

        private void writeHead(OutputStream out) throws IOException {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            sb.append("Content-Length: ").append(content.length).append("\r\n");
            if (acceptsRanges) { sb.append("Accept-Ranges: bytes\r\n"); }
            if (etag != null)   { sb.append("ETag: ").append(etag).append("\r\n"); }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private void writeRangeProbe(OutputStream out) throws IOException {
            final String h = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: 1\r\n" +
                    "Content-Range: bytes 0-0/" + content.length + "\r\n" +
                    "ETag: " + etag + "\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            out.write(content, 0, 1);
            out.flush();
        }

        private void writeChunk(OutputStream out, long start, long end) throws IOException {
            final long len = end - start + 1;
            final String h = "HTTP/1.1 206 Partial Content\r\n" +
                    "Content-Length: " + len + "\r\n" +
                    "Content-Range: bytes " + start + "-" + end + "/" + content.length + "\r\n" +
                    "ETag: " + etag + "\r\nConnection: close\r\n\r\n";
            out.write(h.getBytes(StandardCharsets.US_ASCII));
            writeWithThrottle(out, (int) start, (int) len);
        }

        private void writeFullGet(OutputStream out) throws IOException {
            final StringBuilder sb = new StringBuilder("HTTP/1.1 200 OK\r\n");
            if (!omitContentLengthInGet) {
                sb.append("Content-Length: ").append(content.length).append("\r\n");
            }
            sb.append("Connection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            writeWithThrottle(out, 0, content.length);
        }

        private void writeWithThrottle(OutputStream out, int offset, int length) throws IOException {
            if (perWriteDelayMs <= 0) {
                out.write(content, offset, length);
                return;
            }
            final int piece = 512;
            for (int i = offset; i < offset + length; i += piece) {
                final int toWrite = Math.min(piece, offset + length - i);
                out.write(content, i, toWrite);
                out.flush();
                try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
            }
        }

        private void writeError(OutputStream out, int status) throws IOException {
            final String r = "HTTP/1.1 " + status + " Error\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n";
            out.write(r.getBytes(StandardCharsets.US_ASCII));
            out.flush();
        }

        private static String readLine(InputStream in) throws IOException {
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

    // ── helpers ───────────────────────────────────────────────────────────────

    private static PrintStream capturedStream(ByteArrayOutputStream buf) {
        return new PrintStream(buf, true, StandardCharsets.UTF_8);
    }

    private static int execute(DownloadCommand cmd, String... args) {
        return new CommandLine(cmd).execute(args);
    }

    // ── TC-CLI-001 — Successful download ─────────────────────────────────────

    /**
     * TC-CLI-001: valid URI + output path produces the expected file bytes and exit 0.
     */
    @Test
    void successfulDownloadProducesFileAndExitsZero() throws Exception {
        final byte[] content = "hello from TC-CLI-001".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault();
            final Path dest = tempDir.resolve("tc001.bin");
            final int code = execute(
                    new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(0, code, "exit code must be 0 on success");
            assertTrue(Files.exists(dest), "output file must exist");
            assertArrayEquals(content, Files.readAllBytes(dest), "file bytes must match");
        }
    }

    // ── TC-CLI-002 — Progress reporting ──────────────────────────────────────

    /**
     * TC-CLI-002: a successful transfer writes at least one progress line to stdout.
     */
    @Test
    void progressEventsAreRenderedToOutput() throws Exception {
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'P');
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 5;

            final DownloadManager manager = DownloadManagers.createDefault();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final PrintStream capturedOut = capturedStream(buf);
            final Path dest = tempDir.resolve("tc002.bin");

            final int code = execute(
                    new DownloadCommand(manager, capturedOut, System.err),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(0, code);
            final String output = buf.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains("Downloading"), "stdout must contain 'Downloading'");
            assertTrue(output.contains("Completed"), "stdout must contain 'Completed'");
        }
    }

    // ── TC-CLI-003 — Download failure ────────────────────────────────────────

    /**
     * TC-CLI-003: an HTTP failure produces exit 1 and an error message on stderr.
     */
    @Test
    void httpFailureProducesNonZeroExitCodeAndErrorOnStderr() throws Exception {
        try (TestHttpServer server = new TestHttpServer(new byte[0])) {
            server.failStatusCode = 500;

            final DownloadManager manager = DownloadManagers.createDefault();
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            final PrintStream capturedErr = capturedStream(errBuf);
            final Path dest = tempDir.resolve("tc003.bin");

            final int code = execute(
                    new DownloadCommand(manager, System.out, capturedErr),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(1, code, "exit code must be 1 on HTTP error");
            final String errOutput = errBuf.toString(StandardCharsets.UTF_8);
            assertFalse(errOutput.isBlank(), "stderr must contain an error message");
        }
    }

    // ── TC-CLI-004 — Invalid arguments ───────────────────────────────────────

    /**
     * TC-CLI-004a: missing source URI produces exit 2 (picocli USAGE).
     */
    @Test
    void missingUriProducesExitTwo() {
        final DownloadManager manager = DownloadManagers.createDefault();
        final int code = execute(
                new DownloadCommand(manager, System.out, System.err),
                "--output", "/tmp/x.bin");
        assertEquals(2, code, "missing URI must exit 2");
        manager.close();
    }

    /**
     * TC-CLI-004b: missing --output option produces exit 2 (picocli USAGE).
     */
    @Test
    void missingOutputOptionProducesExitTwo() {
        final DownloadManager manager = DownloadManagers.createDefault();
        final int code = execute(
                new DownloadCommand(manager, System.out, System.err),
                "https://example.com/file");
        assertEquals(2, code, "missing --output must exit 2");
        manager.close();
    }

    /**
     * TC-CLI-004c: a syntactically malformed URI produces exit 2 (picocli type conversion error).
     */
    @Test
    void malformedUriProducesExitTwo() {
        final DownloadManager manager = DownloadManagers.createDefault();
        // "ht tp://" contains a space — invalid URI syntax
        final int code = execute(
                new DownloadCommand(manager, System.out, System.err),
                "ht tp://example.com/file", "--output", "/tmp/x.bin");
        assertEquals(2, code, "malformed URI must exit 2");
        manager.close();
    }

    // ── TC-CLI-005 — Unsupported protocol ────────────────────────────────────

    /**
     * TC-CLI-005: a valid URI with unsupported scheme is rejected by the core (not the CLI),
     * producing exit 1 via {@code DownloadFailedEvent}.
     */
    @Test
    void unsupportedProtocolProducesExitOneViaCore() throws Exception {
        final DownloadManager manager = DownloadManagers.createDefault();
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final PrintStream capturedErr = capturedStream(errBuf);
        final Path dest = tempDir.resolve("tc005.bin");

        // "xyz" scheme is syntactically valid but unsupported by DefaultDownloadStrategyResolver
        final int code = execute(
                new DownloadCommand(manager, System.out, capturedErr),
                "xyz://example.com/file.bin", "--output", dest.toString());

        assertEquals(1, code, "unsupported scheme must exit 1 via DownloadFailedEvent");
        assertFalse(errBuf.toString(StandardCharsets.UTF_8).isBlank(),
                "stderr must report the error");
    }

    // ── TC-CLI-006 — Fast completion ─────────────────────────────────────────

    /**
     * TC-CLI-006: a download that completes immediately does not cause the CLI to hang;
     * the event is not missed even though the transfer may finish before the main thread
     * reaches {@code terminalLatch.await()}.
     */
    @Test
    void fastCompletingDownloadDoesNotHang() throws Exception {
        final byte[] content = "tiny".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault();
            final Path dest = tempDir.resolve("tc006.bin");

            final int code = execute(
                    new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(0, code);
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }

    // ── TC-CLI-007 — Resource cleanup ────────────────────────────────────────

    /**
     * TC-CLI-007a: after a successful download the manager is closed;
     * submitting a new download throws {@link DownloadRejectedException}.
     */
    @Test
    void managerIsClosedAfterSuccessfulDownload() throws Exception {
        final byte[] content = "cleanup success".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault();
            final Path dest = tempDir.resolve("tc007a.bin");

            execute(new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());

            // close() was called in finally — further downloads must be rejected
            assertThrows(DownloadRejectedException.class,
                    () -> manager.download(DownloadRequest.builder()
                            .sourceUri(server.uri())
                            .targetPath(tempDir.resolve("tc007a-rejected.bin"))
                            .build()),
                    "manager must be closed after command returns");
        }
    }

    /**
     * TC-CLI-007b: after a failed download the manager is closed;
     * submitting a new download throws {@link DownloadRejectedException}.
     */
    @Test
    void managerIsClosedAfterFailedDownload() throws Exception {
        try (TestHttpServer server = new TestHttpServer(new byte[0])) {
            server.failStatusCode = 503;

            final DownloadManager manager = DownloadManagers.createDefault();
            final Path dest = tempDir.resolve("tc007b.bin");

            execute(new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());

            assertThrows(DownloadRejectedException.class,
                    () -> manager.download(DownloadRequest.builder()
                            .sourceUri(server.uri())
                            .targetPath(tempDir.resolve("tc007b-rejected.bin"))
                            .build()),
                    "manager must be closed after failed command");
        }
    }

    // ── TC-CLI-008 — Unknown content length ──────────────────────────────────

    /**
     * TC-CLI-008: when the server omits Content-Length the progress renderer
     * displays bytes downloaded without crashing or emitting NaN/Infinity.
     */
    @Test
    void unknownContentLengthRenderedSafely() throws Exception {
        final byte[] content = "unknown length payload".getBytes(StandardCharsets.UTF_8);
        try (TestHttpServer server = new TestHttpServer(content)) {
            server.omitContentLengthInGet = true;

            final DownloadManager manager = DownloadManagers.createDefault();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final PrintStream capturedOut = capturedStream(buf);
            final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            final PrintStream capturedErr = capturedStream(errBuf);
            final Path dest = tempDir.resolve("tc008.bin");

            final int code = execute(
                    new DownloadCommand(manager, capturedOut, capturedErr),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(0, code, "exit code must be 0");
            final String combined = buf.toString(StandardCharsets.UTF_8)
                    + errBuf.toString(StandardCharsets.UTF_8);
            assertFalse(combined.contains("NaN"), "output must not contain NaN");
            assertFalse(combined.contains("Infinity"), "output must not contain Infinity");
            assertTrue(Files.exists(dest));
        }
    }

    // ── TC-CLI-009 — Segmented download ──────────────────────────────────────

    /**
     * TC-CLI-009: when the server supports range requests the segmented strategy
     * is selected transparently; the same CLI command succeeds with correct output.
     *
     * <p>Root cause of previous hang (K-0.2-CLI-HANG-001): {@code katabasis-cli/pom.xml}
     * was missing {@code --enable-preview} in the Surefire argLine. The core module's
     * {@code SegmentedHttpDownloadStrategy} uses the preview {@code StructuredTaskScope}
     * API and is compiled with {@code --enable-preview}. Loading it in a JVM without
     * that flag raised {@code UnsupportedClassVersionError}, which escaped the
     * {@code catch (Exception)} in {@code DefaultDownloadManager.runDownload}, killing
     * the download virtual thread silently and leaving the CLI latch waiting forever.
     */
    @Test
    void segmentedDownloadCompletesWithSameCommand() throws Exception {
        final byte[] content = new byte[32 * 1024];
        Arrays.fill(content, (byte) 'S');

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.acceptsRanges = true;
            server.etag = "\"tc009-etag\"";

            final DownloadConfig config = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                    .segmentMinSizeBytes(1L)
                    .segmentCount(4)
                    .build();
            final DownloadManager manager = DownloadManagers.createDefault(config);
            final Path dest = tempDir.resolve("tc009.bin");

            final int code = execute(
                    new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());

            assertEquals(0, code, "segmented download must exit 0");
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest),
                    "segmented output must match source bytes exactly");
        }
    }

    // ── TC-CLI-010 — No premature exit ───────────────────────────────────────

    /**
     * TC-CLI-010: the CLI remains active while a controlled download is in progress
     * and returns only after the terminal outcome.
     *
     * <p>Verified by measuring elapsed time: a throttled server that takes at least
     * {@code MIN_EXPECTED_MS} means the command could not have returned prematurely.
     */
    @Test
    void cliWaitsForSlowDownloadToComplete() throws Exception {
        final byte[] content = new byte[8 * 1024];
        Arrays.fill(content, (byte) 'W');
        final int perWriteDelayMs = 10;
        // With 8 KB at 512 bytes/write and 10 ms/write, expect ~160 ms total
        final long minExpectedMs = 100;

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = perWriteDelayMs;

            final DownloadManager manager = DownloadManagers.createDefault();
            final Path dest = tempDir.resolve("tc010.bin");

            final long start = System.currentTimeMillis();
            final int code = execute(
                    new DownloadCommand(manager, System.out, System.err),
                    server.uri().toString(), "--output", dest.toString());
            final long elapsed = System.currentTimeMillis() - start;

            assertEquals(0, code, "exit code must be 0");
            assertTrue(elapsed >= minExpectedMs,
                    "CLI must wait for download; elapsed=" + elapsed + " ms, expected >= " + minExpectedMs);
            assertTrue(Files.exists(dest));
            assertArrayEquals(content, Files.readAllBytes(dest));
        }
    }
}
