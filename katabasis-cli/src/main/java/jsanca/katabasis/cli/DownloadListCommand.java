package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
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

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CLI subcommand that downloads all URLs listed in a file to a target directory.
 *
 * <p>Usage: {@code katabasis download-list <url-list-file> --output-dir <directory>}
 *
 * <p>The URL list file must contain one absolute URI per line. Blank lines and lines
 * starting with {@code #} are ignored. All entries are pre-validated before any download
 * begins. Duplicate filenames (two URLs resolving to the same output file) are rejected.
 *
 * <p>All downloads share a single {@link DownloadManager} and proceed concurrently up to
 * its configured concurrency limit.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0 ({@link CliExitCode#SUCCESS}) — all downloads completed successfully</li>
 *   <li>1 ({@link CliExitCode#EXECUTION_FAILURE}) — all downloads failed, or input was invalid</li>
 *   <li>2 ({@link CliExitCode#USAGE_ERROR}) — invalid arguments or usage error (picocli)</li>
 *   <li>3 ({@link CliExitCode#PARTIAL_BATCH_FAILURE}) — partial failure: some succeeded, some failed</li>
 * </ul>
 */
@Command(
        name = "download-list",
        description = "Download files listed in a URL list file.",
        mixinStandardHelpOptions = true
)
public class DownloadListCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "File containing one absolute URL per line.")
    private Path urlListFile;

    @Option(names = {"--output-dir", "-d"}, required = true, description = "Directory to save downloaded files.")
    private Path outputDir;

    @Option(names = "--max-concurrent", paramLabel = "<N>",
            description = "Maximum parallel downloads (default: 4). Must be >= 1.")
    Integer maxConcurrent;

    @Mixin
    private DownloadConfigMixin configMixin;

    private final Function<DownloadConfig, DownloadManager> managerFactory;
    private final PrintStream out;
    private final PrintStream err;
    private final Consumer<Runnable> shutdownHookCapture;
    private CheckpointCoordinator checkpointCoordinator = null;

    /** Production constructor — called by picocli via reflection. */
    public DownloadListCommand() {
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
        this.shutdownHookCapture = null;
    }

    private static Path resolveCheckpointDir() {
        return Path.of(System.getProperty("user.home"), ".katabasis", "checkpoints");
    }

    /** Test constructor — injects a pre-built manager and output streams. */
    DownloadListCommand(DownloadManager manager, PrintStream out, PrintStream err) {
        this.managerFactory = config -> manager;
        this.out = out;
        this.err = err;
        this.shutdownHookCapture = null;
    }

    /** Test constructor — injects a factory and output streams for config-capture tests. */
    DownloadListCommand(Function<DownloadConfig, DownloadManager> factory, PrintStream out, PrintStream err) {
        this.managerFactory = factory;
        this.out = out;
        this.err = err;
        this.shutdownHookCapture = null;
    }

    /**
     * Test constructor — SIGINT simulation tests.
     *
     * <p>{@code shutdownHookCapture} receives the {@code requestShutdown} {@link Runnable}
     * registered as the JVM shutdown hook, allowing tests to simulate SIGINT.
     */
    DownloadListCommand(DownloadManager manager, PrintStream out, PrintStream err,
                        Consumer<Runnable> shutdownHookCapture) {
        this.managerFactory = config -> manager;
        this.out = out;
        this.err = err;
        this.shutdownHookCapture = shutdownHookCapture;
    }

    @Override
    public Integer call() {
        // 1. Build and validate configuration
        DownloadConfig config;
        try {
            config = configMixin.buildConfig(DownloadConfig.defaultConfig());
        } catch (IllegalArgumentException e) {
            err.println("Invalid configuration: " + e.getMessage());
            return CliExitCode.USAGE_ERROR;
        }
        if (maxConcurrent != null) {
            if (maxConcurrent < 1) {
                err.println("Invalid configuration: --max-concurrent must be >= 1: " + maxConcurrent);
                return CliExitCode.USAGE_ERROR;
            }
            config = DownloadConfig.copyOf(config).maxConcurrentDownloads(maxConcurrent).build();
        }
        final DownloadConfig effectiveConfig = config;

        // 3. Parse URL list file
        final List<UrlListParser.ParsedEntry> entries;
        try {
            entries = UrlListParser.parse(urlListFile);
        } catch (IOException e) {
            err.println("Error reading URL list: " + e.getMessage());
            return CliExitCode.EXECUTION_FAILURE;
        } catch (UrlListParser.ParseException e) {
            err.println("Invalid URLs in list:");
            for (String msg : e.errors()) {
                err.println("  " + msg);
            }
            return CliExitCode.EXECUTION_FAILURE;
        }

        if (entries.isEmpty()) {
            err.println("No URLs to download.");
            return CliExitCode.EXECUTION_FAILURE;
        }

        // 4. Create output directory if needed
        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            err.println("Cannot create output directory: " + e.getMessage());
            return CliExitCode.EXECUTION_FAILURE;
        }

        // 5. Resolve output filenames; reject duplicates before submitting anything
        final List<Map.Entry<URI, Path>> resolvedEntries = new ArrayList<>();
        final Map<Path, URI> seenPaths = new LinkedHashMap<>();
        final List<String> resolutionErrors = new ArrayList<>();

        for (UrlListParser.ParsedEntry entry : entries) {
            try {
                final Path target = OutputFilenameResolver.resolve(entry.uri(), outputDir);
                if (seenPaths.containsKey(target)) {
                    resolutionErrors.add("Line " + entry.lineNumber() + ": " + entry.uri()
                            + " — output filename conflicts with " + seenPaths.get(target));
                } else if (Files.isSymbolicLink(target)) {
                    resolutionErrors.add("Line " + entry.lineNumber() + ": destination is a symbolic link: "
                            + target.getFileName());
                } else if (Files.exists(target)) {
                    resolutionErrors.add("Line " + entry.lineNumber() + ": output file already exists: "
                            + target.getFileName());
                } else {
                    seenPaths.put(target, entry.uri());
                    resolvedEntries.add(new AbstractMap.SimpleImmutableEntry<>(entry.uri(), target));
                }
            } catch (IllegalArgumentException e) {
                resolutionErrors.add("Line " + entry.lineNumber() + ": " + e.getMessage());
            }
        }

        if (!resolutionErrors.isEmpty()) {
            err.println("Cannot resolve output filenames:");
            for (String msg : resolutionErrors) {
                err.println("  " + msg);
            }
            return CliExitCode.EXECUTION_FAILURE;
        }

        // 6. Submit all downloads and await terminal events
        final int n = resolvedEntries.size();
        final AtomicInteger remaining = new AtomicInteger(n);
        final CountDownLatch sessionLatch = new CountDownLatch(1);
        final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
        final ConcurrentHashMap<String, Boolean> outcomes = new ConcurrentHashMap<>();
        final AtomicInteger completedCount = new AtomicInteger(0);
        final AtomicInteger failedCount = new AtomicInteger(0);
        final AtomicInteger cancelledCount = new AtomicInteger(0);

        final Runnable requestShutdown = () -> {
            if (shutdownRequested.compareAndSet(false, true)) {
                sessionLatch.countDown();
            }
        };

        if (shutdownHookCapture != null) {
            shutdownHookCapture.accept(requestShutdown);
        }

        final Thread shutdownHook = new Thread(requestShutdown, "katabasis-download-list-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        final Consumer<DownloadEvent> listener = event -> {
            final String id = event.info().downloadId();
            if (event instanceof DownloadCompletedEvent) {
                if (outcomes.putIfAbsent(id, true) == null) {
                    out.println("  Completed: " + event.info().targetPath().getFileName());
                    completedCount.incrementAndGet();
                    if (remaining.decrementAndGet() == 0) {
                        sessionLatch.countDown();
                    }
                }
            } else if (event instanceof DownloadFailedEvent failed) {
                if (outcomes.putIfAbsent(id, false) == null) {
                    err.println(CliErrorFormatter.formatError(
                            CliErrorCode.DOWNLOAD_FAILED,
                            id,
                            event.info().sourceUri(),
                            failed.errorMessage()));
                    failedCount.incrementAndGet();
                    if (remaining.decrementAndGet() == 0) {
                        sessionLatch.countDown();
                    }
                }
            } else if (event instanceof DownloadCancelledEvent) {
                if (outcomes.putIfAbsent(id, false) == null) {
                    err.println(CliErrorFormatter.formatError(
                            CliErrorCode.DOWNLOAD_CANCELLED,
                            id,
                            event.info().sourceUri(),
                            "Download cancelled"));
                    cancelledCount.incrementAndGet();
                    if (remaining.decrementAndGet() == 0) {
                        sessionLatch.countDown();
                    }
                }
            }
        };

        final DownloadManager manager = managerFactory.apply(effectiveConfig);
        out.println("Downloading " + n + " file" + (n == 1 ? "" : "s") + " to " + outputDir + "...");

        final AtomicInteger rejectedCount = new AtomicInteger(0);
        // Register listener before first download call to avoid missing fast completions.
        manager.addListener(listener);
        try {
            for (Map.Entry<URI, Path> entry : resolvedEntries) {
                try {
                    manager.download(DownloadRequest.builder()
                            .sourceUri(entry.getKey())
                            .targetPath(entry.getValue())
                            .build());
                } catch (RuntimeException e) {
                    err.println(CliErrorFormatter.formatError(
                            CliErrorCode.DOWNLOAD_REJECTED,
                            null,
                            entry.getKey(),
                            e.getMessage()));
                    rejectedCount.incrementAndGet();
                    if (remaining.decrementAndGet() == 0) {
                        sessionLatch.countDown();
                    }
                }
            }

            if (!sessionLatch.await(10, TimeUnit.MINUTES)) {
                err.println(CliErrorFormatter.formatError(
                        CliErrorCode.INTERNAL_ERROR, null, null,
                        "Timed out waiting for downloads to complete"));
                return CliExitCode.EXECUTION_FAILURE;
            }

            if (shutdownRequested.get()) {
                out.println("Shutting down...");
                return CliExitCode.EXECUTION_FAILURE;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println(CliErrorFormatter.formatError(
                    CliErrorCode.INTERNAL_ERROR, null, null,
                    "Download batch interrupted"));
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

        final int totalFailed = failedCount.get() + cancelledCount.get() + rejectedCount.get();
        out.println(CliErrorFormatter.formatSummary(
                n, completedCount.get(), failedCount.get(), cancelledCount.get(), rejectedCount.get()));

        if (totalFailed == 0) {
            return CliExitCode.SUCCESS;
        }
        if (completedCount.get() == 0) {
            return CliExitCode.EXECUTION_FAILURE;
        }
        return CliExitCode.PARTIAL_BATCH_FAILURE;
    }
}
