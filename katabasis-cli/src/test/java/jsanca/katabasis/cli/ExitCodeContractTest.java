package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.2-T08 — Exit code contract and structured error output tests.
 *
 * <p>Covers scenarios not already exercised in K-0.2-T04/T05/T07 tests:
 * structured error format, exit code 3 for partial batch, credential
 * redaction, and special-character escaping.
 */
class ExitCodeContractTest {

    @TempDir
    Path tempDir;

    // ── minimal in-process HTTP server ───────────────────────────────────────

    private static final class TestHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile int failStatusCode = 0;
        private volatile boolean closed = false;

        TestHttpServer(byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }
        URI uri(String path) { return URI.create("http://localhost:" + port() + path); }

        private void acceptLoop() {
            while (!closed) {
                try {
                    final Socket s = serverSocket.accept();
                    Thread.ofVirtual().start(() -> handle(s));
                } catch (IOException e) {
                    if (!closed) { throw new UncheckedIOException(e); }
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                final InputStream in = socket.getInputStream();
                String line = readLine(in);
                while (line != null && !line.isBlank()) { line = readLine(in); }
                final OutputStream out = socket.getOutputStream();
                if (failStatusCode > 0) {
                    out.write(("HTTP/1.1 " + failStatusCode + " Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + content.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(content);
                }
                out.flush();
            } catch (IOException ignored) {}
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

        @Override public void close() throws IOException { closed = true; serverSocket.close(); }
    }

    // ── fake manager helpers ─────────────────────────────────────────────────

    /**
     * Fires a terminal event synchronously from {@code download()} so tests
     * do not need a real HTTP server.
     */
    private static DownloadManager failingManager(String failMessage) {
        return new DownloadManager() {
            private volatile Consumer<DownloadEvent> listener;

            @Override
            public DownloadSubmissionResult download(DownloadRequest request) {
                final String id = "fake-id";
                final DownloadInfo info = new DownloadInfo(id, request.sourceUri(), request.targetPath());
                if (listener != null) {
                    listener.accept(new DownloadFailedEvent(
                            info, failMessage, null, Duration.ZERO, Instant.now(), null));
                }
                return new DownloadSubmissionResult(id, DownloadStatus.FAILED, Instant.now());
            }

            @Override public DownloadControlResult cancel(String id) { return null; }
            @Override public DownloadControlResult pause(String id)  { return null; }
            @Override public DownloadControlResult resume(String id) { return null; }
            @Override public void addListener(Consumer<DownloadEvent> l)    { listener = l; }
            @Override public void removeListener(Consumer<DownloadEvent> l) { listener = null; }
            @Override public void close() {}
        };
    }

    private static DownloadManager cancellingManager() {
        return new DownloadManager() {
            private volatile Consumer<DownloadEvent> listener;

            @Override
            public DownloadSubmissionResult download(DownloadRequest request) {
                final String id = "fake-cancel-id";
                final DownloadInfo info = new DownloadInfo(id, request.sourceUri(), request.targetPath());
                if (listener != null) {
                    listener.accept(new DownloadCancelledEvent(info, Instant.now(), 0L, 0L, 0.0));
                }
                return new DownloadSubmissionResult(id, DownloadStatus.CANCELLED, Instant.now());
            }

            @Override public DownloadControlResult cancel(String id) { return null; }
            @Override public DownloadControlResult pause(String id)  { return null; }
            @Override public DownloadControlResult resume(String id) { return null; }
            @Override public void addListener(Consumer<DownloadEvent> l)    { listener = l; }
            @Override public void removeListener(Consumer<DownloadEvent> l) { listener = null; }
            @Override public void close() {}
        };
    }

    private static PrintStream nullPrint() {
        return new PrintStream(ByteArrayOutputStream.nullOutputStream());
    }

    // ── CliErrorFormatter unit tests ─────────────────────────────────────────

    /**
     * TC-T08-001: formatError produces the expected key=value line structure.
     */
    @Test
    void formatErrorProducesStructuredLine() {
        final String line = CliErrorFormatter.formatError(
                CliErrorCode.DOWNLOAD_FAILED,
                "dl-123",
                URI.create("http://example.com/file.zip"),
                "Connection timed out");
        assertTrue(line.startsWith("ERROR "), "must start with ERROR");
        assertTrue(line.contains("code=DOWNLOAD_FAILED"), line);
        assertTrue(line.contains("downloadId=dl-123"), line);
        assertTrue(line.contains("source=http://example.com/file.zip"), line);
        assertTrue(line.contains("message=\"Connection timed out\""), line);
    }

    /**
     * TC-T08-002: formatError omits optional fields when null.
     */
    @Test
    void formatErrorOmitsNullFields() {
        final String line = CliErrorFormatter.formatError(
                CliErrorCode.INTERNAL_ERROR, null, null, "Unexpected error");
        assertFalse(line.contains("downloadId="), line);
        assertFalse(line.contains("source="), line);
        assertTrue(line.contains("code=INTERNAL_ERROR"), line);
        assertTrue(line.contains("message="), line);
    }

    /**
     * TC-T08-003: quoteValue escapes backslash, double-quote, and newline correctly.
     */
    @Test
    void quoteValueEscapesSpecialCharacters() {
        assertEquals("\"hello\\\\world\"", CliErrorFormatter.quoteValue("hello\\world"),
                "backslash must become \\\\");
        assertEquals("\"say \\\"hi\\\"\"", CliErrorFormatter.quoteValue("say \"hi\""),
                "double-quote must become \\\"");
        assertEquals("\"line1\\nline2\"", CliErrorFormatter.quoteValue("line1\nline2"),
                "newline must become \\n");
        assertEquals("\"tab\\there\"", CliErrorFormatter.quoteValue("tab\there"),
                "tab must become \\t");
    }

    /**
     * TC-T08-004: sanitizeUri strips userinfo (credentials) from a URI.
     */
    @Test
    void sanitizeUriStripsCredentials() {
        final URI withCreds = URI.create("http://user:secret@example.com/path");
        final String sanitized = CliErrorFormatter.sanitizeUri(withCreds);
        assertFalse(sanitized.contains("secret"), "password must be stripped: " + sanitized);
        assertFalse(sanitized.contains("user"), "username must be stripped: " + sanitized);
        assertTrue(sanitized.contains("example.com"), "host must be preserved: " + sanitized);
        assertTrue(sanitized.contains("/path"), "path must be preserved: " + sanitized);
    }

    /**
     * TC-T08-005: sanitizeUri is a no-op when there are no credentials.
     */
    @Test
    void sanitizeUriPreservesUriWithoutCredentials() {
        final URI clean = URI.create("http://example.com/file.bin?v=2");
        assertEquals(clean.toString(), CliErrorFormatter.sanitizeUri(clean));
    }

    /**
     * TC-T08-006: formatSummary produces the expected SUMMARY line.
     */
    @Test
    void formatSummaryProducesCorrectFields() {
        final String line = CliErrorFormatter.formatSummary(5, 3, 1, 1, 0);
        assertEquals("SUMMARY total=5 completed=3 failed=1 cancelled=1 rejected=0", line);
    }

    // ── DownloadCommand integration ───────────────────────────────────────────

    /**
     * TC-T08-007: failed single download emits a structured ERROR line on stderr.
     */
    @Test
    void singleDownloadFailureEmitsStructuredErrorOnStderr() throws Exception {
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final Path dest = tempDir.resolve("out.bin");

        final int code = new CommandLine(
                new DownloadCommand(failingManager("HTTP 503 Service Unavailable"),
                        nullPrint(),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute("http://example.com/file.bin", "--output", dest.toString());

        assertEquals(CliExitCode.EXECUTION_FAILURE, code);
        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("ERROR"), "stderr must contain ERROR: " + errText);
        assertTrue(errText.contains("code=DOWNLOAD_FAILED"), errText);
        assertTrue(errText.contains("message="), errText);
    }

    /**
     * TC-T08-008: cancelled single download emits DOWNLOAD_CANCELLED on stderr and exits 1.
     */
    @Test
    void singleDownloadCancelledEmitsStructuredErrorAndExitsOne() throws Exception {
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final Path dest = tempDir.resolve("out.bin");

        final int code = new CommandLine(
                new DownloadCommand(cancellingManager(),
                        nullPrint(),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute("http://example.com/file.bin", "--output", dest.toString());

        assertEquals(CliExitCode.EXECUTION_FAILURE, code);
        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("code=DOWNLOAD_CANCELLED"), errText);
    }

    /**
     * TC-T08-009: credential in source URI is stripped from structured error output.
     */
    @Test
    void credentialsInSourceUriAreStrippedFromErrorOutput() throws Exception {
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final Path dest = tempDir.resolve("out.bin");

        final int code = new CommandLine(
                new DownloadCommand(failingManager("connection refused"),
                        nullPrint(),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute("http://admin:s3cr3t@localhost:1/file.bin", "--output", dest.toString());

        assertEquals(CliExitCode.EXECUTION_FAILURE, code);
        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertFalse(errText.contains("s3cr3t"), "password must not appear in error output: " + errText);
        assertFalse(errText.contains("admin"), "username must not appear in error output: " + errText);
    }

    /**
     * TC-T08-010: error message containing special characters is properly escaped on stderr.
     */
    @Test
    void errorMessageWithSpecialCharsIsEscaped() throws Exception {
        final String rawMsg = "Failure: \"quota exceeded\"\ncontent: 100\\200";
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final Path dest = tempDir.resolve("out.bin");

        new CommandLine(
                new DownloadCommand(failingManager(rawMsg),
                        nullPrint(),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute("http://example.com/file.bin", "--output", dest.toString());

        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("\\\"quota exceeded\\\""),
                "embedded quotes must be escaped: " + errText);
        assertTrue(errText.contains("\\n"), "newline in message must be escaped: " + errText);
        assertTrue(errText.contains("100\\\\200"), "backslash must be escaped: " + errText);
    }

    // ── DownloadListCommand integration ──────────────────────────────────────

    /**
     * TC-T08-011: batch with partial success returns exit 3.
     */
    @Test
    void partialBatchFailureReturnsExitThree() throws Exception {
        try (TestHttpServer server = new TestHttpServer("OK".getBytes(StandardCharsets.UTF_8))) {
            server.failStatusCode = 0; // succeed for /good.bin, but we need two routes
            // We need one route to succeed and one to fail.
            // The server as written uses failStatusCode globally, so we use two servers.
        }
        // Use the PartialManager that fires one Completed and one Failed.
        final Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        final Path f1 = outDir.resolve("a.bin");
        final Path f2 = outDir.resolve("b.bin");

        final DownloadManager partialManager = new DownloadManager() {
            private volatile Consumer<DownloadEvent> listener;
            private final AtomicInteger callCount = new AtomicInteger(0);

            @Override
            public DownloadSubmissionResult download(DownloadRequest request) {
                final String id = "id-" + callCount.incrementAndGet();
                final DownloadInfo info = new DownloadInfo(id, request.sourceUri(), request.targetPath());
                if (callCount.get() == 1) {
                    // first download: succeed
                    try { Files.write(request.targetPath(), new byte[]{1}); } catch (IOException e) { throw new UncheckedIOException(e); }
                    if (listener != null) {
                        listener.accept(new DownloadCompletedEvent(
                                info, request.targetPath(), 1L, null, Duration.ZERO, Instant.now()));
                    }
                } else {
                    // second download: fail
                    if (listener != null) {
                        listener.accept(new DownloadFailedEvent(
                                info, "HTTP 500", null, Duration.ZERO, Instant.now(), null));
                    }
                }
                return new DownloadSubmissionResult(id, DownloadStatus.PENDING, Instant.now());
            }

            @Override public DownloadControlResult cancel(String id) { return null; }
            @Override public DownloadControlResult pause(String id)  { return null; }
            @Override public DownloadControlResult resume(String id) { return null; }
            @Override public void addListener(Consumer<DownloadEvent> l)    { listener = l; }
            @Override public void removeListener(Consumer<DownloadEvent> l) { listener = null; }
            @Override public void close() {}
        };

        final Path urlList = tempDir.resolve("urls.txt");
        Files.writeString(urlList, "http://localhost:1/a.bin\nhttp://localhost:1/b.bin\n",
                StandardCharsets.UTF_8);

        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final int code = new CommandLine(
                new DownloadListCommand(partialManager,
                        new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute(urlList.toString(), "--output-dir", outDir.toString());

        assertEquals(CliExitCode.PARTIAL_BATCH_FAILURE, code,
                "partial batch must exit 3; stdout=" + outBuf + " err=" + errBuf);
        final String outText = outBuf.toString(StandardCharsets.UTF_8);
        assertTrue(outText.contains("completed=1"), outText);
        assertTrue(outText.contains("failed=1"), outText);
        assertTrue(Files.exists(f1), "successful file must exist");
        assertFalse(Files.exists(f2), "failed file must not exist");
    }

    /**
     * TC-T08-012: all-failed batch returns exit 1 (EXECUTION_FAILURE, not 3).
     */
    @Test
    void allFailedBatchReturnsExitOne() throws Exception {
        final Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        final Path urlList = tempDir.resolve("urls.txt");
        Files.writeString(urlList, "http://localhost:1/a.bin\n", StandardCharsets.UTF_8);

        final int code = new CommandLine(
                new DownloadListCommand(failingManager("connection refused"),
                        nullPrint(), nullPrint()))
                .execute(urlList.toString(), "--output-dir", outDir.toString());

        assertEquals(CliExitCode.EXECUTION_FAILURE, code, "all-failed batch must exit 1");
    }

    /**
     * TC-T08-013: batch failure emits structured ERROR lines on stderr.
     */
    @Test
    void batchFailureEmitsStructuredErrorOnStderr() throws Exception {
        final Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        final Path urlList = tempDir.resolve("urls.txt");
        Files.writeString(urlList, "http://localhost:1/a.bin\n", StandardCharsets.UTF_8);
        final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

        new CommandLine(
                new DownloadListCommand(failingManager("HTTP 503"),
                        nullPrint(),
                        new PrintStream(errBuf, true, StandardCharsets.UTF_8)))
                .execute(urlList.toString(), "--output-dir", outDir.toString());

        final String errText = errBuf.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("ERROR"), errText);
        assertTrue(errText.contains("code=DOWNLOAD_FAILED"), errText);
        assertTrue(errText.contains("message="), errText);
    }

    /**
     * TC-T08-014: batch SUMMARY line appears on stdout with correct field names.
     */
    @Test
    void batchSummaryLineAppearsOnStdout() throws Exception {
        final Path outDir = tempDir.resolve("out");
        Files.createDirectories(outDir);
        final Path urlList = tempDir.resolve("urls.txt");
        Files.writeString(urlList, "http://localhost:1/a.bin\n", StandardCharsets.UTF_8);
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();

        new CommandLine(
                new DownloadListCommand(failingManager("error"),
                        new PrintStream(outBuf, true, StandardCharsets.UTF_8), nullPrint()))
                .execute(urlList.toString(), "--output-dir", outDir.toString());

        final String outText = outBuf.toString(StandardCharsets.UTF_8);
        assertTrue(outText.contains("SUMMARY"), outText);
        assertTrue(outText.contains("total="), outText);
        assertTrue(outText.contains("completed="), outText);
        assertTrue(outText.contains("failed="), outText);
        assertTrue(outText.contains("cancelled="), outText);
    }

    /**
     * TC-T08-015: invalid --segments value exits 2 (USAGE_ERROR).
     */
    @Test
    void invalidSegmentsValueExitsTwo() throws Exception {
        final Path outDir = tempDir.resolve("out");
        final Path urlList = tempDir.resolve("urls.txt");
        Files.writeString(urlList, "http://example.com/file.bin\n", StandardCharsets.UTF_8);

        final int code = new CommandLine(
                new DownloadListCommand(DownloadManagers.createDefault(),
                        nullPrint(), nullPrint()))
                .execute(urlList.toString(), "--output-dir", outDir.toString(), "--segments", "0");

        assertEquals(CliExitCode.USAGE_ERROR, code, "--segments 0 must exit 2");
    }
}
