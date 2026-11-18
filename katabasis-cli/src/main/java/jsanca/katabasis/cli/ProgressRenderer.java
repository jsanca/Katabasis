package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;

import java.io.PrintStream;

/**
 * Renders download progress and terminal outcomes to the terminal.
 *
 * <p>Progress renders are throttled to avoid flooding the console on
 * high-frequency progress events. For interactive terminals the current
 * line is overwritten using CR; for non-interactive output each update is
 * written on its own line.
 */
class ProgressRenderer {

    private static final long MIN_RENDER_INTERVAL_MS = 250;
    private static final int BAR_WIDTH = 30;

    private final PrintStream out;
    private final PrintStream err;
    private final boolean interactive;
    private long lastRenderMs = 0;

    ProgressRenderer(PrintStream out, PrintStream err, boolean interactive) {
        this.out = out;
        this.err = err;
        this.interactive = interactive;
    }

    void start(String name) {
        out.println("Downloading " + name);
    }

    void onProgress(DownloadProgressEvent event) {
        long now = System.currentTimeMillis();
        if (now - lastRenderMs < MIN_RENDER_INTERVAL_MS) {
            return;
        }
        lastRenderMs = now;
        String line = buildProgressLine(event.downloadedBytes(), event.totalBytes(), event.hasTotalBytes(), event.percentage());
        if (interactive) {
            out.print("\r" + line);
        } else {
            out.println(line);
        }
    }

    void onCompleted(DownloadCompletedEvent event) {
        if (interactive) {
            out.println();
        }
        out.println("Completed: " + formatBytes(event.totalBytes()) + " downloaded to " + event.finalPath());
    }

    void onFailed(DownloadFailedEvent event) {
        if (interactive) {
            out.println();
        }
        err.println(CliErrorFormatter.formatError(
                CliErrorCode.DOWNLOAD_FAILED,
                event.info().downloadId(),
                event.info().sourceUri(),
                event.errorMessage()));
    }

    void onCancelled(DownloadCancelledEvent event) {
        if (interactive) {
            out.println();
        }
        err.println(CliErrorFormatter.formatError(
                CliErrorCode.DOWNLOAD_CANCELLED,
                event.info().downloadId(),
                event.info().sourceUri(),
                "Download cancelled"));
    }

    private static String buildProgressLine(long downloaded, long total, boolean hasTotalBytes, double pct) {
        if (!hasTotalBytes) {
            return "  " + formatBytes(downloaded) + " downloaded";
        }
        int filled = (int) (pct / 100.0 * BAR_WIDTH);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < BAR_WIDTH; i++) {
            if (i < filled) {
                bar.append('=');
            } else if (i == filled) {
                bar.append('>');
            } else {
                bar.append(' ');
            }
        }
        bar.append(']');
        return String.format("%s %3.0f%%  %s / %s", bar, pct, formatBytes(downloaded), formatBytes(total));
    }

    static String formatBytes(long bytes) {
        if (bytes < 1_024L) {
            return bytes + " B";
        }
        if (bytes < 1_024L * 1_024L) {
            return String.format("%.1f KB", bytes / 1_024.0);
        }
        if (bytes < 1_024L * 1_024L * 1_024L) {
            return String.format("%.1f MB", bytes / (1_024.0 * 1_024.0));
        }
        return String.format("%.2f GB", bytes / (1_024.0 * 1_024.0 * 1_024.0));
    }
}
