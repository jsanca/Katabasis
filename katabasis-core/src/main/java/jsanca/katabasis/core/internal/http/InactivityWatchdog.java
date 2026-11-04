package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors a streaming download for stalls and closes the target stream when the
 * configured inactivity threshold is exceeded.
 *
 * <p>The watchdog runs on a background virtual thread. Callers must:
 * <ol>
 *   <li>Call {@link #recordActivity()} whenever bytes are successfully read from the stream.</li>
 *   <li>Call {@link #resetTimer()} after any pause ends so pause duration does not count.</li>
 *   <li>Call {@link #wasTriggered()} in any {@link IOException} catch block to decide whether
 *       to rethrow as {@link ReadInactivityTimeoutException}.</li>
 *   <li>Close this watchdog (via try-with-resources or explicit {@link #close()}) when the
 *       read loop ends, whether normally or exceptionally.</li>
 * </ol>
 *
 * @author jsanca &amp; elo
 */
final class InactivityWatchdog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(InactivityWatchdog.class);

    private final AtomicLong lastActivityNanos;
    private final AtomicBoolean triggered;
    private final long timeoutNanos;
    private final InputStream target;
    private final PauseCoordinator pauseCoordinator;
    private final String downloadId;
    private final Thread watchdogThread;

    /**
     * Creates and immediately starts the watchdog.
     *
     * @param timeout           inactivity threshold; must be positive
     * @param target            stream to close when the threshold is exceeded
     * @param pauseCoordinator  coordinator used to suppress the watchdog during pause
     * @param downloadId        download being watched
     */
    InactivityWatchdog(final Duration timeout,
                       final InputStream target,
                       final PauseCoordinator pauseCoordinator,
                       final String downloadId) {

        Objects.requireNonNull(timeout, "timeout must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(pauseCoordinator, "pauseCoordinator must not be null");
        Objects.requireNonNull(downloadId, "downloadId must not be null");

        this.lastActivityNanos = new AtomicLong(System.nanoTime());
        this.triggered = new AtomicBoolean(false);
        this.timeoutNanos = timeout.toNanos();
        this.target = target;
        this.pauseCoordinator = pauseCoordinator;
        this.downloadId = downloadId;

        final long checkMs = Math.max(250L, timeout.toMillis() / 4);

        this.watchdogThread = Thread.ofVirtual().start(() -> {
            try {
                while (true) {
                    Thread.sleep(checkMs);
                    if (pauseCoordinator.isPauseRequested(downloadId)) {
                        continue;
                    }
                    if (System.nanoTime() - lastActivityNanos.get() > timeoutNanos) {
                        log.warn("Read inactivity timeout triggered for {}", downloadId);
                        triggered.set(true);
                        try {
                            target.close();
                        } catch (final IOException ignored) {}
                        break;
                    }
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Records that bytes were received; resets the inactivity timer.
     */
    void recordActivity() {
        lastActivityNanos.set(System.nanoTime());
    }

    /**
     * Explicitly resets the inactivity timer without requiring a read to have occurred.
     * Call this after a pause ends so pause duration does not count toward inactivity.
     */
    void resetTimer() {
        lastActivityNanos.set(System.nanoTime());
    }

    /**
     * Returns {@code true} if the watchdog fired and closed the stream.
     *
     * @return whether inactivity timeout was triggered
     */
    boolean wasTriggered() {
        return triggered.get();
    }

    /**
     * Stops the watchdog thread. Safe to call multiple times.
     */
    @Override
    public void close() {
        watchdogThread.interrupt();
    }
}
