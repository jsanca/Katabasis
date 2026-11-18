package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import jsanca.katabasis.core.api.manager.CheckpointSourceProvider;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.storage.json.api.CheckpointCoordinator;
import jsanca.katabasis.storage.json.api.DownloadStateStores;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * CLI subcommand that downloads a single URI to a local file.
 *
 * <p>Usage: {@code katabasis download <source-uri> --output <target-path>}
 *
 * <p>When the terminal is interactive ({@code System.console() != null}), the user
 * may type commands while the download is in progress:
 * <ul>
 *   <li>{@code p} — pause</li>
 *   <li>{@code r} — resume</li>
 *   <li>{@code c} — cancel (requires confirmation)</li>
 *   <li>{@code q} — quit (graceful shutdown)</li>
 * </ul>
 *
 * <p>SIGINT (Ctrl+C) triggers the same graceful-shutdown path as {@code q}.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 — download completed successfully</li>
 *   <li>1 — download failed, was cancelled, or was interrupted by shutdown</li>
 *   <li>2 — invalid arguments or usage error (handled by picocli)</li>
 * </ul>
 */
@Command(
        name = "download",
        description = "Download a file from the given URI.",
        mixinStandardHelpOptions = true
)
public class DownloadCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source URI to download from.")
    private URI sourceUri;

    @Option(names = {"--output", "-o"}, required = true, description = "Output file path.")
    private Path outputPath;

    @Mixin
    private DownloadConfigMixin configMixin;

    private final Function<DownloadConfig, DownloadManager> managerFactory;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean interactive;
    private final Supplier<String> lineReader;
    private final Consumer<Runnable> shutdownHookCapture;
    private CheckpointCoordinator checkpointCoordinator = null;

    /** Production constructor — called by picocli via reflection. */
    public DownloadCommand() {
        this.managerFactory = config -> {
            final Path checkpointDir = resolveCheckpointDir();
            try {
                Files.createDirectories(checkpointDir);
            } catch (IOException ex) {
                System.err.println("Warning: could not create checkpoint directory: " + ex.getMessage());
            }
            final DownloadStateStore store = DownloadStateStores.fileStore(checkpointDir);
            final DownloadManager real = DownloadManagers.createDefault(config,
                    snap -> { try { store.save(snap); } catch (Exception ignored) {} });
            this.checkpointCoordinator = CheckpointCoordinator.create(
                    real, (CheckpointSourceProvider) real, store,
                    Duration.ofSeconds(5), ev -> {});
            return real;
        };
        this.out = System.out;
        this.err = System.err;
        this.interactive = System.console() != null;
        this.lineReader = buildStdinReader();
        this.shutdownHookCapture = null;
    }

    private static Path resolveCheckpointDir() {
        return Path.of(System.getProperty("user.home"), ".katabasis", "checkpoints");
    }

    /** Test constructor — injects a pre-built manager and output streams (non-interactive). */
    DownloadCommand(DownloadManager manager, PrintStream out, PrintStream err) {
        this.managerFactory = config -> manager;
        this.out = out;
        this.err = err;
        this.interactive = false;
        this.lineReader = () -> null;
        this.shutdownHookCapture = null;
    }

    /** Test constructor — injects a factory and output streams for config-capture tests. */
    DownloadCommand(Function<DownloadConfig, DownloadManager> factory, PrintStream out, PrintStream err) {
        this.managerFactory = factory;
        this.out = out;
        this.err = err;
        this.interactive = false;
        this.lineReader = () -> null;
        this.shutdownHookCapture = null;
    }

    /**
     * Test constructor — interactive lifecycle tests.
     *
     * <p>{@code lineReader} simulates user input line-by-line; returning {@code null}
     * signals EOF (input worker exits without triggering shutdown).
     * {@code shutdownHookCapture} receives the {@code requestShutdown} {@link Runnable}
     * registered as the JVM shutdown hook, allowing tests to simulate SIGINT.
     */
    DownloadCommand(DownloadManager manager, PrintStream out, PrintStream err,
                    Supplier<String> lineReader, Consumer<Runnable> shutdownHookCapture) {
        this.managerFactory = config -> manager;
        this.out = out;
        this.err = err;
        this.interactive = true;
        this.lineReader = lineReader;
        this.shutdownHookCapture = shutdownHookCapture;
    }

    @Override
    public Integer call() {
        final DownloadConfig config;
        try {
            config = configMixin.buildConfig(DownloadConfig.defaultConfig());
        } catch (IllegalArgumentException e) {
            err.println("Invalid configuration: " + e.getMessage());
            return CliExitCode.USAGE_ERROR;
        }

        final String displayName = deriveDisplayName(sourceUri);
        final ProgressRenderer renderer = new ProgressRenderer(out, err, interactive);
        renderer.start(displayName);

        final DownloadManager manager = managerFactory.apply(config);
        final CountDownLatch sessionLatch = new CountDownLatch(1);
        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        final AtomicInteger exitCode = new AtomicInteger(0);
        final AtomicReference<DownloadEvent> terminalEvent = new AtomicReference<>();
        final AtomicReference<String> pendingCancelConfirm = new AtomicReference<>(null);
        final AtomicReference<String> downloadIdRef = new AtomicReference<>();

        final Runnable requestShutdown = () -> {
            if (shutdownRequested.compareAndSet(false, true)) {
                sessionLatch.countDown();
            }
        };

        if (shutdownHookCapture != null) {
            shutdownHookCapture.accept(requestShutdown);
        }

        final Thread shutdownHook = new Thread(requestShutdown, "katabasis-download-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        final Consumer<DownloadEvent> listener = event -> {
            if (event instanceof DownloadProgressEvent progress) {
                renderer.onProgress(progress);
            } else if (event instanceof DownloadCompletedEvent
                    || event instanceof DownloadFailedEvent
                    || event instanceof DownloadCancelledEvent) {
                terminalEvent.set(event);
                exitCode.set(event instanceof DownloadCompletedEvent
                        ? CliExitCode.SUCCESS : CliExitCode.EXECUTION_FAILURE);
                sessionLatch.countDown();
            }
        };

        manager.addListener(listener);
        try {
            final var submission = manager.download(DownloadRequest.builder()
                    .sourceUri(sourceUri)
                    .targetPath(outputPath)
                    .build());
            downloadIdRef.set(submission.downloadId());

            if (interactive) {
                out.println("Commands: [p]ause  [r]esume  [c]ancel  [q]uit");
                Thread.ofVirtual().start(
                        () -> runInputWorker(manager, downloadIdRef, sessionLatch,
                                pendingCancelConfirm, requestShutdown));
            }

            if (!sessionLatch.await(10, TimeUnit.MINUTES)) {
                err.println(CliErrorFormatter.formatError(
                        CliErrorCode.INTERNAL_ERROR,
                        downloadIdRef.get(),
                        sourceUri,
                        "Download timed out waiting for terminal result"));
                return CliExitCode.EXECUTION_FAILURE;
            }

            if (shutdownRequested.get()) {
                out.println("Shutting down...");
                return CliExitCode.EXECUTION_FAILURE;
            }

            final DownloadEvent terminal = terminalEvent.get();
            if (terminal instanceof DownloadCompletedEvent completed) {
                renderer.onCompleted(completed);
            } else if (terminal instanceof DownloadFailedEvent failed) {
                renderer.onFailed(failed);
            } else if (terminal instanceof DownloadCancelledEvent cancelled) {
                renderer.onCancelled(cancelled);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println(CliErrorFormatter.formatError(
                    CliErrorCode.INTERNAL_ERROR,
                    downloadIdRef.get(),
                    sourceUri,
                    "Download interrupted"));
            return CliExitCode.EXECUTION_FAILURE;
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM is already shutting down — hook cannot be deregistered
            }
            manager.removeListener(listener);
            if (checkpointCoordinator != null) {
                try { checkpointCoordinator.close(); } catch (Exception ignored) {}
            }
            manager.close();
        }

        return exitCode.get();
    }

    private void runInputWorker(DownloadManager manager,
                                 AtomicReference<String> downloadIdRef,
                                 CountDownLatch sessionLatch,
                                 AtomicReference<String> pendingCancelConfirm,
                                 Runnable requestShutdown) {
        while (sessionLatch.getCount() > 0) {
            final String line = lineReader.get();
            if (line == null) {
                break; // EOF — exit input worker without triggering shutdown
            }
            if (sessionLatch.getCount() == 0) {
                break; // session ended while we were blocked on input
            }
            final String cmd = line.trim().toLowerCase();

            final String pending = pendingCancelConfirm.getAndSet(null);
            if (pending != null) {
                if ("y".equals(cmd)) {
                    final String id = downloadIdRef.get();
                    if (id != null) {
                        manager.cancel(id);
                    }
                } else {
                    out.println("Cancel aborted.");
                }
                continue;
            }

            handleInputCommand(cmd, manager, downloadIdRef, pendingCancelConfirm, requestShutdown);
        }
    }

    private void handleInputCommand(String cmd,
                                     DownloadManager manager,
                                     AtomicReference<String> downloadIdRef,
                                     AtomicReference<String> pendingCancelConfirm,
                                     Runnable requestShutdown) {
        final String id = downloadIdRef.get();
        switch (cmd) {
            case "p" -> {
                if (id != null) {
                    manager.pause(id);
                    out.println("Pausing...");
                }
            }
            case "r" -> {
                if (id != null) {
                    manager.resume(id);
                    out.println("Resuming...");
                }
            }
            case "c" -> {
                pendingCancelConfirm.set("pending");
                out.print("Cancel download? [y/N]: ");
            }
            case "q" -> requestShutdown.run();
            default -> out.println("Commands: [p]ause  [r]esume  [c]ancel  [q]uit");
        }
    }

    private static Supplier<String> buildStdinReader() {
        final BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        return () -> {
            try {
                return br.readLine();
            } catch (java.io.IOException e) {
                return null;
            }
        };
    }

    private static String deriveDisplayName(URI uri) {
        final String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return uri.toString();
        }
        final int slash = path.lastIndexOf('/');
        final String name = (slash >= 0) ? path.substring(slash + 1) : path;
        return name.isBlank() ? uri.toString() : name;
    }
}
