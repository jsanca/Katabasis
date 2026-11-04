package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.internal.execution.PauseCoordinator;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InactivityWatchdogTest {

    // Probe round-trip target: watchdog fires within timeout + check-interval margin
    private static final Duration FAST_TIMEOUT = Duration.ofMillis(300);

    @Test
    void watchdogFiresWhenStreamStalls() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final CountDownLatch closedLatch = new CountDownLatch(1);

        final InputStream stall = new InputStream() {
            @Override
            public int read() throws IOException {
                return -1;
            }

            @Override
            public void close() {
                closed.set(true);
                closedLatch.countDown();
            }
        };

        final PauseCoordinator coordinator = new PauseCoordinator();
        try (InactivityWatchdog watchdog = new InactivityWatchdog(FAST_TIMEOUT, stall, coordinator, "d1")) {
            // Don't call recordActivity() — let the timer expire
            assertTrue(closedLatch.await(3, TimeUnit.SECONDS),
                    "Watchdog must close the stream within 3 seconds");
            assertTrue(closed.get(), "Stream must be closed");
            assertTrue(watchdog.wasTriggered(), "wasTriggered must be true");
        }
    }

    @Test
    void watchdogDoesNotFireWhenActivityIsRecorded() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final InputStream stream = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        final PauseCoordinator coordinator = new PauseCoordinator();
        try (InactivityWatchdog watchdog = new InactivityWatchdog(FAST_TIMEOUT, stream, coordinator, "d2")) {
            final long deadline = System.currentTimeMillis() + FAST_TIMEOUT.toMillis() * 3;
            // Keep resetting the timer faster than the timeout expires
            while (System.currentTimeMillis() < deadline) {
                watchdog.recordActivity();
                Thread.sleep(50);
            }
            assertFalse(watchdog.wasTriggered(), "Watchdog must not fire while activity is being recorded");
            assertFalse(closed.get(), "Stream must not be closed");
        }
    }

    @Test
    void watchdogDoesNotFireWhilePaused() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final CountDownLatch closedLatch = new CountDownLatch(1);

        final InputStream stall = new InputStream() {
            @Override
            public int read() { return -1; }

            @Override
            public void close() {
                closed.set(true);
                closedLatch.countDown();
            }
        };

        final PauseCoordinator coordinator = new PauseCoordinator();
        final String downloadId = "d3";

        // Pause BEFORE creating the watchdog so the first check sees pause
        coordinator.requestPause(downloadId);

        try (InactivityWatchdog watchdog = new InactivityWatchdog(FAST_TIMEOUT, stall, coordinator, downloadId)) {
            // Wait for 2x timeout — watchdog must stay suppressed
            Thread.sleep(FAST_TIMEOUT.toMillis() * 2 + 100);
            assertFalse(watchdog.wasTriggered(), "Watchdog must not fire while paused");
            assertFalse(closed.get(), "Stream must not be closed while paused");

            // Resume; now let it stall and fire
            coordinator.requestResume(downloadId);
            assertTrue(closedLatch.await(3, TimeUnit.SECONDS),
                    "After resume, watchdog must fire when stalled");
            assertTrue(watchdog.wasTriggered(), "wasTriggered must be true after resume");
        }
    }

    @Test
    void resetTimerPreventsFireAfterPause() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final CountDownLatch closedLatch = new CountDownLatch(1);

        final InputStream stall = new InputStream() {
            @Override
            public int read() { return -1; }

            @Override
            public void close() {
                closed.set(true);
                closedLatch.countDown();
            }
        };

        final PauseCoordinator coordinator = new PauseCoordinator();
        final String downloadId = "d4";
        coordinator.requestPause(downloadId);

        try (InactivityWatchdog watchdog = new InactivityWatchdog(FAST_TIMEOUT, stall, coordinator, downloadId)) {
            Thread.sleep(FAST_TIMEOUT.toMillis() + 50); // pause suppresses the watchdog

            // Resume and immediately reset the timer
            coordinator.requestResume(downloadId);
            watchdog.resetTimer();

            // Now keep the timer alive — watchdog must not fire within the first timeout window
            final long deadline = System.currentTimeMillis() + FAST_TIMEOUT.toMillis();
            while (System.currentTimeMillis() < deadline) {
                watchdog.resetTimer();
                Thread.sleep(30);
            }
            assertFalse(watchdog.wasTriggered(), "resetTimer after pause must restart the grace window");
        }
    }

    @Test
    void closeStopsWatchdogThreadAndPreventsLatefire() throws Exception {
        final AtomicBoolean closed = new AtomicBoolean(false);
        final InputStream stall = new InputStream() {
            @Override
            public int read() { return -1; }

            @Override
            public void close() { closed.set(true); }
        };

        final PauseCoordinator coordinator = new PauseCoordinator();
        final InactivityWatchdog watchdog =
                new InactivityWatchdog(FAST_TIMEOUT, stall, coordinator, "d5");

        // Close immediately — watchdog thread must stop
        watchdog.close();

        // Wait longer than timeout — if thread is truly stopped, the stream must NOT be closed
        Thread.sleep(FAST_TIMEOUT.toMillis() * 2 + 100);
        assertFalse(watchdog.wasTriggered(), "Closed watchdog must not fire");
        assertFalse(closed.get(), "Stream must not be closed after watchdog is stopped");
    }
}
