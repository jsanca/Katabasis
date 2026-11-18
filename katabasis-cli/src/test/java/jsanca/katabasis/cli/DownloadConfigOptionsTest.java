package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.2-T06 — CLI configuration option tests: TC-CFG-001 through TC-CFG-012.
 *
 * <p>All tests use a {@link CapturingFactory} + {@link FakeDownloadManager} instead of a live
 * HTTP server. The fake manager writes a one-byte file and fires {@link DownloadCompletedEvent}
 * synchronously so each command exits cleanly without real network I/O.
 */
class DownloadConfigOptionsTest {

    @TempDir
    Path tempDir;

    // ── test infrastructure ──────────────────────────────────────────────────

    /**
     * Captures the {@link DownloadConfig} passed to the factory and returns a {@link FakeDownloadManager}.
     */
    static final class CapturingFactory implements Function<DownloadConfig, DownloadManager> {
        volatile DownloadConfig capturedConfig;

        @Override
        public DownloadManager apply(DownloadConfig config) {
            this.capturedConfig = config;
            return new FakeDownloadManager();
        }
    }

    /**
     * Writes a one-byte file to the target path and fires a {@link DownloadCompletedEvent}
     * synchronously in {@link #download(DownloadRequest)}.
     */
    static final class FakeDownloadManager implements DownloadManager {
        private volatile Consumer<DownloadEvent> listener;

        @Override
        public DownloadSubmissionResult download(DownloadRequest request) {
            final String id = UUID.randomUUID().toString();
            try {
                Files.write(request.targetPath(), new byte[]{42});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            final DownloadInfo info = new DownloadInfo(id, request.sourceUri(), request.targetPath());
            final DownloadCompletedEvent event = new DownloadCompletedEvent(
                    info, request.targetPath(), 1L, null, Duration.ZERO, Instant.now());
            if (listener != null) {
                listener.accept(event);
            }
            return new DownloadSubmissionResult(id, DownloadStatus.COMPLETED, Instant.now());
        }

        @Override
        public void addListener(Consumer<DownloadEvent> l) { this.listener = l; }

        @Override
        public void removeListener(Consumer<DownloadEvent> l) { this.listener = null; }

        @Override
        public void close() {}

        @Override
        public DownloadControlResult cancel(String downloadId) { return null; }

        @Override
        public DownloadControlResult pause(String downloadId) { return null; }

        @Override
        public DownloadControlResult resume(String downloadId) { return null; }
    }

    // ── download command helpers ─────────────────────────────────────────────

    private int runDownload(CapturingFactory factory, String... extraArgs) throws IOException {
        final PrintStream out = new PrintStream(OutputStream.nullOutputStream());
        final PrintStream err = new PrintStream(OutputStream.nullOutputStream());
        final Path output = tempDir.resolve(UUID.randomUUID() + ".bin");
        final DownloadCommand cmd = new DownloadCommand(factory, out, err);
        final String[] baseArgs = {"http://example.com/file.bin", "--output", output.toString()};
        final String[] args = concat(baseArgs, extraArgs);
        return new CommandLine(cmd).execute(args);
    }

    private int runDownloadExpectingError(String... extraArgs) throws IOException {
        final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        final PrintStream err = new PrintStream(errBytes);
        final PrintStream out = new PrintStream(OutputStream.nullOutputStream());
        final Path output = tempDir.resolve(UUID.randomUUID() + ".bin");
        final CapturingFactory factory = new CapturingFactory();
        final DownloadCommand cmd = new DownloadCommand(factory, out, err);
        final String[] baseArgs = {"http://example.com/file.bin", "--output", output.toString()};
        return new CommandLine(cmd).execute(concat(baseArgs, extraArgs));
    }

    // ── download-list command helpers ────────────────────────────────────────

    private Path writeUrlList(String... urls) throws IOException {
        final Path file = tempDir.resolve("urls.txt");
        Files.writeString(file, String.join("\n", urls) + "\n");
        return file;
    }

    private int runDownloadList(CapturingFactory factory, Path urlList, String... extraArgs) throws IOException {
        final PrintStream out = new PrintStream(OutputStream.nullOutputStream());
        final PrintStream err = new PrintStream(OutputStream.nullOutputStream());
        final Path outDir = tempDir.resolve("out-" + UUID.randomUUID());
        Files.createDirectories(outDir);
        final DownloadListCommand cmd = new DownloadListCommand(factory, out, err);
        final String[] baseArgs = {urlList.toString(), "--output-dir", outDir.toString()};
        return new CommandLine(cmd).execute(concat(baseArgs, extraArgs));
    }

    // ── TC-CFG-001: --segments propagated to segmentCount ───────────────────

    @Test
    void segmentsOptionPropagatedToConfig() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--segments", "8");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(8, factory.capturedConfig.segmentCount());
    }

    // ── TC-CFG-002: --retries propagated as fixed-delay RetryConfig ──────────

    @Test
    void retriesOptionPropagatedAsFixedDelay() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--retries", "3");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(RetryConfig.Kind.FIXED_DELAY, factory.capturedConfig.retryConfig().kind());
        assertEquals(3, factory.capturedConfig.retryConfig().maxAttempts());
        assertEquals(Duration.ofSeconds(1), factory.capturedConfig.retryConfig().initialDelay());
    }

    // ── TC-CFG-003: --connect-timeout propagated ─────────────────────────────

    @Test
    void connectTimeoutOptionPropagated() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--connect-timeout", "30s");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(Optional.of(Duration.ofSeconds(30)),
                factory.capturedConfig.httpTimeoutConfig().connectTimeout());
    }

    // ── TC-CFG-004: --response-timeout propagated ────────────────────────────

    @Test
    void responseTimeoutOptionPropagated() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--response-timeout", "2m");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(Optional.of(Duration.ofMinutes(2)),
                factory.capturedConfig.httpTimeoutConfig().responseTimeout());
    }

    // ── TC-CFG-005: --read-timeout propagated ────────────────────────────────

    @Test
    void readTimeoutOptionPropagated() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--read-timeout", "500ms");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(Optional.of(Duration.ofMillis(500)),
                factory.capturedConfig.httpTimeoutConfig().readInactivityTimeout());
    }

    // ── TC-CFG-006: --segments 0 → exit 2 ───────────────────────────────────

    @Test
    void segmentsZeroReturnsExitTwo() throws IOException {
        final int exit = runDownloadExpectingError("--segments", "0");
        assertEquals(2, exit);
    }

    // ── TC-CFG-007: --retries -1 → exit 2 ───────────────────────────────────

    @Test
    void retriesNegativeReturnsExitTwo() throws IOException {
        final int exit = runDownloadExpectingError("--retries", "-1");
        assertEquals(2, exit);
    }

    // ── TC-CFG-008: bad duration format → exit 2 (picocli type conversion) ───

    @Test
    void badDurationFormatReturnsExitTwo() throws IOException {
        final int exit = runDownloadExpectingError("--connect-timeout", "badvalue");
        assertEquals(2, exit);
    }

    // ── TC-CFG-009: --max-concurrent propagated (download-list only) ─────────

    @Test
    void maxConcurrentOptionPropagatedForDownloadList() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final Path urlList = writeUrlList("http://example.com/a.bin");
        final int exit = runDownloadList(factory, urlList, "--max-concurrent", "2");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(2, factory.capturedConfig.maxConcurrentDownloads());
    }

    // ── TC-CFG-010: --max-concurrent 0 → exit 2 ──────────────────────────────

    @Test
    void maxConcurrentZeroReturnsExitTwo() throws IOException {
        final PrintStream out = new PrintStream(OutputStream.nullOutputStream());
        final PrintStream err = new PrintStream(OutputStream.nullOutputStream());
        final Path urlList = writeUrlList("http://example.com/a.bin");
        final Path outDir = tempDir.resolve("out-" + UUID.randomUUID());
        Files.createDirectories(outDir);
        final DownloadListCommand cmd = new DownloadListCommand(new CapturingFactory(), out, err);
        final int exit = new CommandLine(cmd).execute(
                urlList.toString(), "--output-dir", outDir.toString(), "--max-concurrent", "0");
        assertEquals(2, exit);
    }

    // ── TC-CFG-011: defaults used when no config flags are supplied ──────────

    @Test
    void defaultsAreUsedWhenNoConfigFlagsSupplied() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory);
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        final DownloadConfig defaults = DownloadConfig.defaultConfig();
        assertEquals(defaults.segmentCount(), factory.capturedConfig.segmentCount());
        assertEquals(defaults.maxConcurrentDownloads(), factory.capturedConfig.maxConcurrentDownloads());
        assertEquals(RetryConfig.Kind.NONE, factory.capturedConfig.retryConfig().kind());
        assertEquals(Optional.empty(), factory.capturedConfig.httpTimeoutConfig().connectTimeout());
    }

    // ── TC-CFG-012: --retries 0 → explicit no-retry ──────────────────────────

    @Test
    void retriesZeroExplicitlySelectsNoRetry() throws IOException {
        final CapturingFactory factory = new CapturingFactory();
        final int exit = runDownload(factory, "--retries", "0");
        assertEquals(0, exit);
        assertNotNull(factory.capturedConfig);
        assertEquals(RetryConfig.Kind.NONE, factory.capturedConfig.retryConfig().kind());
    }

    // ── utility ───────────────────────────────────────────────────────────────

    private static String[] concat(String[] a, String[] b) {
        final String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
