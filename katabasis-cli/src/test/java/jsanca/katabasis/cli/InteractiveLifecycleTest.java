package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadControlAction;
import jsanca.katabasis.core.api.model.DownloadControlResult;
import jsanca.katabasis.core.api.model.DownloadControlStatus;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * K-0.2-T07 — Interactive lifecycle and graceful-shutdown integration tests.
 *
 * <p>All tests use {@link SuspendableDownloadManager}, which holds downloads in-flight
 * until the test explicitly fires a terminal event. Input is simulated via a
 * {@link BlockingQueue} injected as the {@code lineReader}.
 */
class InteractiveLifecycleTest {

    @TempDir
    Path tempDir;

    // ── SuspendableDownloadManager ────────────────────────────────────────────

    /**
     * Test-double {@link DownloadManager} that holds submitted downloads in-flight
     * and allows the test to fire terminal events on demand.
     *
     * <p>Tracks how many times {@code pause}, {@code resume}, and {@code cancel}
     * have been called. {@code cancel()} fires a {@link DownloadCancelledEvent} synchronously.
     */
    private static final class SuspendableDownloadManager implements DownloadManager {

        private static final String DOWNLOAD_ID = "test-id-001";

        private volatile Consumer<DownloadEvent> listener;
        private volatile DownloadInfo lastInfo;
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        final AtomicInteger pauseCount = new AtomicInteger(0);
        final AtomicInteger resumeCount = new AtomicInteger(0);
        final AtomicInteger cancelCount = new AtomicInteger(0);
        volatile boolean closed = false;

        @Override
        public DownloadSubmissionResult download(DownloadRequest request) {
            lastInfo = new DownloadInfo(DOWNLOAD_ID, request.sourceUri(), request.targetPath());
            startedLatch.countDown();
            return new DownloadSubmissionResult(DOWNLOAD_ID, DownloadStatus.PENDING, Instant.now());
        }

        @Override
        public DownloadControlResult cancel(String downloadId) {
            cancelCount.incrementAndGet();
            if (listener != null && lastInfo != null) {
                listener.accept(new DownloadCancelledEvent(lastInfo, Instant.now(), 0L, 0L, 0.0));
            }
            return new DownloadControlResult(downloadId, DownloadControlAction.CANCEL,
                    DownloadControlStatus.REQUESTED, null, null);
        }

        @Override
        public DownloadControlResult pause(String downloadId) {
            pauseCount.incrementAndGet();
            return new DownloadControlResult(downloadId, DownloadControlAction.PAUSE,
                    DownloadControlStatus.REQUESTED, null, null);
        }

        @Override
        public DownloadControlResult resume(String downloadId) {
            resumeCount.incrementAndGet();
            return new DownloadControlResult(downloadId, DownloadControlAction.RESUME,
                    DownloadControlStatus.REQUESTED, null, null);
        }

        @Override
        public void addListener(Consumer<DownloadEvent> l) {
            this.listener = l;
        }

        @Override
        public void removeListener(Consumer<DownloadEvent> l) {
            if (this.listener == l) {
                this.listener = null;
            }
        }

        @Override
        public void close() {
            this.closed = true;
        }

        /** Blocks until {@link #download} has been called (download is in-flight). */
        void awaitStarted() throws InterruptedException {
            assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "download() was not called in time");
        }

        /** Fires a completed terminal event synchronously. */
        void fireCompleted(Path targetPath) throws IOException {
            Files.write(targetPath, new byte[]{42});
            if (listener != null && lastInfo != null) {
                listener.accept(new DownloadCompletedEvent(
                        lastInfo, targetPath, 1L, null, Duration.ZERO, Instant.now()));
            }
        }

        /** Fires a failed terminal event synchronously. */
        void fireFailed() {
            if (listener != null && lastInfo != null) {
                listener.accept(new DownloadFailedEvent(
                        lastInfo, "simulated failure", null, Duration.ZERO, Instant.now(), null));
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static final URI DUMMY_URI = URI.create("http://localhost:1/test.bin");

    /** Creates a {@link BlockingQueue}-backed line reader. Put lines to simulate user input. */
    private static BlockingQueue<String> newInputQueue() {
        return new LinkedBlockingQueue<>();
    }

    private static java.util.function.Supplier<String> queueReader(BlockingQueue<String> q) {
        return () -> {
            try {
                return q.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        };
    }

    /** Runs {@code cmd.execute()} on a virtual thread and returns a latch that fires when done. */
    private CountDownLatch startCommandAsync(CommandLine cmd, Path output, AtomicInteger exitRef) {
        final CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            exitRef.set(cmd.execute(DUMMY_URI.toString(), "--output", output.toString()));
            done.countDown();
        });
        return done;
    }

    private CommandLine buildCmd(SuspendableDownloadManager manager, PrintStream out,
                                  java.util.function.Supplier<String> reader,
                                  Consumer<Runnable> hookCapture) {
        return new CommandLine(new DownloadCommand(manager, out, System.err, reader, hookCapture));
    }

    // ── download command tests ─────────────────────────────────────────────────

    /**
     * TC-LC-001: typing {@code p} calls {@code manager.pause()}.
     */
    @Test
    void pauseCommandCallsPauseOnManager() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("p");
        Thread.sleep(200); // allow input worker to process
        assertEquals(1, manager.pauseCount.get(), "pause should have been called once");

        manager.fireCompleted(output);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
    }

    /**
     * TC-LC-002: typing {@code r} calls {@code manager.resume()}.
     */
    @Test
    void resumeCommandCallsResumeOnManager() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("r");
        Thread.sleep(200);
        assertEquals(1, manager.resumeCount.get(), "resume should have been called once");

        manager.fireCompleted(output);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
    }

    /**
     * TC-LC-003: typing {@code c} then {@code y} cancels the download; exit code is 1.
     */
    @Test
    void cancelConfirmedWithYCancelsAndExitsOne() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("c");
        Thread.sleep(100);
        input.put("y");

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, manager.cancelCount.get(), "cancel should have been called once");
        assertEquals(1, exit.get(), "exit code should be 1 after cancellation");
    }

    /**
     * TC-LC-004: typing {@code c} then {@code n} aborts the cancel; download completes normally.
     */
    @Test
    void cancelAbortedWithNDoesNotCancel() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("c");
        Thread.sleep(100);
        input.put("n");
        Thread.sleep(100);
        assertEquals(0, manager.cancelCount.get(), "cancel should not have been called");

        manager.fireCompleted(output);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get(), "exit code should be 0 after normal completion");
    }

    /**
     * TC-LC-005: typing {@code q} triggers graceful shutdown; exit code is 1.
     */
    @Test
    void quitCommandTriggersShutdownAndExitsOne() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("q");

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, exit.get(), "exit code should be 1 after quit");
    }

    /**
     * TC-LC-006: an unrecognised command prints the help line without crashing.
     */
    @Test
    void unknownCommandPrintsHelp() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                        queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("x");
        Thread.sleep(200);
        final String printed = outBuf.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("[p]ause"), "unknown command should print help");

        manager.fireCompleted(output);
        assertTrue(done.await(5, TimeUnit.SECONDS));
    }

    /**
     * TC-LC-007: simulated SIGINT (via shutdown hook capture) exits with code 1.
     */
    @Test
    void simulatedSigintTriggersGracefulShutdown() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final AtomicReference<Runnable> shutdownRef = new AtomicReference<>();
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                        queueReader(input), shutdownRef::set),
                output, exit);

        manager.awaitStarted();
        // shutdownHookCapture was called before download() — reference is guaranteed set
        assertNotNull(shutdownRef.get(), "shutdown hook must have been registered");
        shutdownRef.get().run(); // simulate JVM SIGINT hook firing

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, exit.get(), "exit code should be 1 after SIGINT");
    }

    /**
     * TC-LC-008: download completes in interactive mode; exit code is 0.
     */
    @Test
    void downloadCompletesInInteractiveModeExitsZero() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        manager.fireCompleted(output);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
    }

    /**
     * TC-LC-009: download fails in interactive mode; exit code is 1.
     */
    @Test
    void downloadFailsInInteractiveModeExitsOne() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        manager.fireFailed();

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, exit.get());
    }

    /**
     * TC-LC-012: multiple pause/resume cycles all reach the manager.
     */
    @Test
    void multiplePauseResumeCyclesReachManager() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final BlockingQueue<String> input = newInputQueue();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = startCommandAsync(
                buildCmd(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), queueReader(input), null),
                output, exit);

        manager.awaitStarted();
        input.put("p");
        Thread.sleep(100);
        input.put("r");
        Thread.sleep(100);
        input.put("p");
        Thread.sleep(100);
        input.put("r");
        Thread.sleep(100);

        assertEquals(2, manager.pauseCount.get(), "pause should have been called twice");
        assertEquals(2, manager.resumeCount.get(), "resume should have been called twice");

        manager.fireCompleted(output);
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
    }

    /**
     * TC-LC-013: non-interactive download completes without input worker; exit code is 0.
     */
    @Test
    void nonInteractiveDownloadCompletesWithoutHanging() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);

        final CommandLine cmd = new CommandLine(
                new DownloadCommand(manager, new PrintStream(ByteArrayOutputStream.nullOutputStream()), System.err));

        Thread.ofVirtual().start(() -> {
            exit.set(cmd.execute(DUMMY_URI.toString(), "--output", output.toString()));
            done.countDown();
        });

        manager.awaitStarted();
        manager.fireCompleted(output);

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(0, exit.get());
        assertTrue(manager.closed, "manager should have been closed");
    }

    /**
     * TC-LC-014: when the download completes while the input worker is waiting for input,
     * {@code call()} returns promptly (input worker's daemon VT does not block the main thread).
     */
    @Test
    void callReturnsPromptlyWhenDownloadCompletesWhileWaitingForInput() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        // Empty queue — lineReader returns null after 5s poll timeout; simulates user not typing
        final BlockingQueue<String> input = newInputQueue();

        final Path output = tempDir.resolve("out.bin");
        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);

        final CommandLine cmd = new CommandLine(
                new DownloadCommand(manager,
                        new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                        System.err, queueReader(input), null));

        Thread.ofVirtual().start(() -> {
            exit.set(cmd.execute(DUMMY_URI.toString(), "--output", output.toString()));
            done.countDown();
        });

        manager.awaitStarted();
        manager.fireCompleted(output);

        // call() must return promptly even though the input worker is blocked on the reader
        assertTrue(done.await(5, TimeUnit.SECONDS),
                "call() should return promptly when terminal event fires");
        assertEquals(0, exit.get());
    }

    // ── download-list command tests ────────────────────────────────────────────

    /**
     * TC-LC-010: simulated SIGINT during {@code download-list} exits with code 1.
     */
    @Test
    void downloadListSigintExitsOne() throws Exception {
        final SuspendableDownloadManager manager = new SuspendableDownloadManager();
        final AtomicReference<Runnable> shutdownRef = new AtomicReference<>();
        final Path urlListFile = tempDir.resolve("urls.txt");
        final Path outputDir = tempDir.resolve("out");
        Files.createDirectories(outputDir);
        Files.writeString(urlListFile,
                "http://localhost:1/a.bin\nhttp://localhost:1/b.bin\n");

        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final CommandLine cmd = new CommandLine(
                new DownloadListCommand(manager,
                        new PrintStream(outBuf, true, StandardCharsets.UTF_8), System.err,
                        shutdownRef::set));

        final AtomicInteger exit = new AtomicInteger(-1);
        final CountDownLatch done = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            exit.set(cmd.execute(urlListFile.toString(), "--output-dir", outputDir.toString()));
            done.countDown();
        });

        // Wait until at least one download has been submitted
        manager.awaitStarted();
        assertNotNull(shutdownRef.get(), "shutdown hook must have been registered");
        shutdownRef.get().run(); // simulate SIGINT

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(1, exit.get(), "exit code should be 1 after SIGINT");
    }

    /**
     * TC-LC-011: {@code download-list} with all downloads completing returns exit code 0.
     */
    @Test
    void downloadListAllCompletedReturnsZero() throws Exception {
        final Path outputDir = tempDir.resolve("out");
        Files.createDirectories(outputDir);
        final Path urlListFile = tempDir.resolve("urls.txt");
        Files.writeString(urlListFile, "http://localhost:1/a.bin\n");

        // Use a manager that fires terminal events synchronously from download()
        final DownloadManager autoCompleteManager = new DownloadManager() {
            private volatile Consumer<DownloadEvent> listener;

            @Override
            public DownloadSubmissionResult download(DownloadRequest req) {
                final String id = "id-" + req.sourceUri().toString().hashCode();
                final DownloadInfo info = new DownloadInfo(id, req.sourceUri(), req.targetPath());
                try {
                    Files.write(req.targetPath(), new byte[]{1});
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                if (listener != null) {
                    listener.accept(new DownloadCompletedEvent(
                            info, req.targetPath(), 1L, null, Duration.ZERO, Instant.now()));
                }
                return new DownloadSubmissionResult(id, DownloadStatus.COMPLETED, Instant.now());
            }

            @Override public DownloadControlResult cancel(String id) { return null; }
            @Override public DownloadControlResult pause(String id) { return null; }
            @Override public DownloadControlResult resume(String id) { return null; }
            @Override public void addListener(Consumer<DownloadEvent> l) { listener = l; }
            @Override public void removeListener(Consumer<DownloadEvent> l) { listener = null; }
            @Override public void close() {}
        };

        final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        final CommandLine cmd = new CommandLine(
                new DownloadListCommand(autoCompleteManager,
                        new PrintStream(outBuf, true, StandardCharsets.UTF_8), System.err));

        final int exit = cmd.execute(urlListFile.toString(), "--output-dir", outputDir.toString());
        assertEquals(0, exit, "all downloads completed — expected exit 0");
    }
}
