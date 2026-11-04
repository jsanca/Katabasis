package jsanca.katabasis.core.internal.retry;

import jsanca.katabasis.core.api.model.RetryConfig;
import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import jsanca.katabasis.core.internal.http.HttpFailureClassifier;
import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
import jsanca.katabasis.core.api.model.DownloadInfo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RetryExecutorTest {

    private static final DownloadInfo INFO = new DownloadInfo(
            "dl-001", URI.create("http://example.com/f"), Path.of("/tmp/f"));

    // ── no-retry policy ───────────────────────────────────────────────────────

    @Test
    void noRetryPolicyNeverRetries() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                RetryPolicies.fromConfig(RetryConfig.noRetry()), e -> true);

        final IOException thrown = assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("fail");
                }, () -> false));

        assertEquals(1, calls.get(), "Should have been called exactly once");
        assertEquals("fail", thrown.getMessage());
    }

    // ── cancellation ──────────────────────────────────────────────────────────

    @Test
    void cancellationExceptionNeverRetried() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                RetryPolicies.fromConfig(RetryConfig.fixedDelay(5, Duration.ofMillis(1))),
                e -> true);

        assertThrows(DownloadCancelledException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new DownloadCancelledException(INFO, 0, 100, 0.0, Instant.now());
                }, () -> false));

        assertEquals(1, calls.get(), "Cancellation must not be retried");
    }

    @Test
    void cancellationFlagOnFirstThrowStopsImmediately() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                RetryPolicies.fromConfig(RetryConfig.fixedDelay(5, Duration.ofSeconds(60))),
                e -> true);

        assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("transient");
                }, () -> true)); // already cancelled before the first throw

        assertEquals(1, calls.get(), "Cancelled flag must stop retry immediately");
    }

    @Test
    void cancellationAfterFirstFailureStopsRetryBeforeSleep() {
        // The cancel flag is false for the first attempt and flips to true after.
        // This exercises the actual "cancel between retries, before sleep" path.
        final AtomicInteger calls = new AtomicInteger();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final RetryExecutor executor = new RetryExecutor(
                RetryPolicies.fromConfig(RetryConfig.fixedDelay(5, Duration.ofSeconds(60))),
                e -> true);

        assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    cancelled.set(true); // flip after executing, so cancel check fires before sleep
                    throw new IOException("transient");
                }, cancelled::get));

        assertEquals(1, calls.get(), "Should execute once and stop before sleeping");
    }

    // ── fixed-delay policy ────────────────────────────────────────────────────

    @Test
    void fixedDelayRetriesUpToMaxAttempts() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                new FixedDelayRetryPolicy(3, Duration.ofMillis(1)),
                e -> true);

        assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("fail");
                }, () -> false));

        // 1 original + 3 retries = 4 total
        assertEquals(4, calls.get(), "Should attempt 1 original + 3 retries");
    }

    @Test
    void fixedDelaySucceedsOnThirdAttempt() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                new FixedDelayRetryPolicy(5, Duration.ofMillis(1)),
                e -> true);

        executor.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("not yet");
            }
        }, () -> false);

        assertEquals(3, calls.get());
    }

    @Test
    void permanentFailureIsNotRetried() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                new FixedDelayRetryPolicy(5, Duration.ofMillis(1)),
                e -> false); // nothing is transient

        assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("permanent");
                }, () -> false));

        assertEquals(1, calls.get(), "Permanent failure must not be retried");
    }

    // ── exponential backoff policy ────────────────────────────────────────────

    @Test
    void exponentialBackoffRetriesUpToMaxAttempts() {
        final AtomicInteger calls = new AtomicInteger();
        final RetryExecutor executor = new RetryExecutor(
                new ExponentialBackoffRetryPolicy(
                        2, Duration.ofMillis(1), Duration.ofMillis(10), 0.0, new Random(0)),
                e -> true);

        assertThrows(IOException.class, () ->
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("fail");
                }, () -> false));

        assertEquals(3, calls.get(), "Should attempt 1 original + 2 retries");
    }

    @Test
    void exponentialBackoffDelayNeverExceedsMaxDelay() {
        final Duration maxDelay = Duration.ofMillis(50);
        final ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                10, Duration.ofMillis(1), maxDelay, 0.0, new Random(0));

        for (int i = 0; i < 10; i++) {
            final RetryDecision decision = policy.decide(i, new IOException());
            if (decision.shouldRetry()) {
                assertTrue(decision.delay().compareTo(maxDelay) <= 0,
                        "Delay must not exceed maxDelay at attempt " + i);
            }
        }
    }

    // ── http failure classifier ───────────────────────────────────────────────

    @Test
    void httpClassifierTreatsNetworkErrorsAsTransient() {
        final HttpFailureClassifier classifier = HttpFailureClassifier.INSTANCE;
        assertTrue(classifier.isTransient(new ConnectException("refused")));
        assertTrue(classifier.isTransient(new java.net.SocketTimeoutException("timeout")));
    }

    @Test
    void httpClassifierTreats5xxAsTransient() {
        final HttpFailureClassifier classifier = HttpFailureClassifier.INSTANCE;
        assertTrue(classifier.isTransient(new HttpDownloadStatusException(INFO, 500)));
        assertTrue(classifier.isTransient(new HttpDownloadStatusException(INFO, 503)));
        assertTrue(classifier.isTransient(new HttpDownloadStatusException(INFO, 429)));
    }

    @Test
    void httpClassifierTreats4xxAsPermanent() {
        final HttpFailureClassifier classifier = HttpFailureClassifier.INSTANCE;
        assertFalse(classifier.isTransient(new HttpDownloadStatusException(INFO, 404)));
        assertFalse(classifier.isTransient(new HttpDownloadStatusException(INFO, 403)));
        assertFalse(classifier.isTransient(new HttpDownloadStatusException(INFO, 400)));
    }

    @Test
    void httpClassifierTreats501AsPermanent() {
        final HttpFailureClassifier classifier = HttpFailureClassifier.INSTANCE;
        assertFalse(classifier.isTransient(new HttpDownloadStatusException(INFO, 501)));
    }

    @Test
    void httpClassifierTreatsGenericIoExceptionAsPermanent() {
        final HttpFailureClassifier classifier = HttpFailureClassifier.INSTANCE;
        assertFalse(classifier.isTransient(new IOException("generic")));
    }

    // ── RetryPolicies factory ─────────────────────────────────────────────────

    @Test
    void retryPoliciesFromConfigNone() {
        final RetryPolicy policy = RetryPolicies.fromConfig(RetryConfig.noRetry());
        final RetryDecision d = policy.decide(0, new IOException());
        assertFalse(d.shouldRetry());
    }

    @Test
    void retryPoliciesFromConfigFixed() {
        final RetryPolicy policy = RetryPolicies.fromConfig(
                RetryConfig.fixedDelay(2, Duration.ofMillis(100)));
        assertTrue(policy.decide(0, new IOException()).shouldRetry());
        assertTrue(policy.decide(1, new IOException()).shouldRetry());
        assertFalse(policy.decide(2, new IOException()).shouldRetry());
    }

    @Test
    void retryPoliciesFromConfigExponential() {
        final RetryPolicy policy = RetryPolicies.fromConfig(
                RetryConfig.exponentialBackoff(3, Duration.ofMillis(10), Duration.ofSeconds(1)),
                new Random(42));
        assertTrue(policy.decide(0, new IOException()).shouldRetry());
        assertTrue(policy.decide(2, new IOException()).shouldRetry());
        assertFalse(policy.decide(3, new IOException()).shouldRetry());
    }

    // ── DownloadRequest per-request override ─────────────────────────────────

    @Test
    void downloadRequestBuilderDefaultsToEmptyRetryConfig() {
        final jsanca.katabasis.core.api.model.DownloadRequest request =
                jsanca.katabasis.core.api.model.DownloadRequest.builder()
                        .sourceUri(URI.create("http://example.com/f"))
                        .targetPath(Path.of("/tmp/f"))
                        .build();
        assertTrue(request.retryConfig().isEmpty(),
                "No retryConfig set — should be Optional.empty()");
    }

    @Test
    void downloadRequestBuilderPropagatesRetryConfigOverride() {
        final RetryConfig override = RetryConfig.fixedDelay(1, Duration.ofSeconds(1));
        final jsanca.katabasis.core.api.model.DownloadRequest request =
                jsanca.katabasis.core.api.model.DownloadRequest.builder()
                        .sourceUri(URI.create("http://example.com/f"))
                        .targetPath(Path.of("/tmp/f"))
                        .retryConfig(override)
                        .build();
        assertTrue(request.retryConfig().isPresent());
        assertEquals(override, request.retryConfig().get());
    }

    // ── overflow safety (regression for long-shift bug) ──────────────────────

    @Test
    void exponentialBackoffDelayNeverNegativeAtLargeAttemptCounts() {
        final Duration maxDelay = Duration.ofSeconds(60);
        final ExponentialBackoffRetryPolicy policy = new ExponentialBackoffRetryPolicy(
                100, Duration.ofMillis(100), maxDelay, 0.0, new Random(0));

        for (int i = 0; i < 100; i++) {
            final RetryDecision decision = policy.decide(i, new IOException());
            if (decision.shouldRetry()) {
                assertTrue(decision.delay().toMillis() >= 0,
                        "Delay must never be negative at attempt " + i);
                assertTrue(decision.delay().compareTo(maxDelay) <= 0,
                        "Delay must not exceed maxDelay at attempt " + i);
            }
        }
    }

    // ── interrupt semantics ───────────────────────────────────────────────────

    @Test
    void interruptDuringRetrySleepRestoresFlagAndRethrows() throws Exception {
        final AtomicReference<Thread> workerThread = new AtomicReference<>();
        final AtomicInteger calls = new AtomicInteger();

        // Use a very short sleep so the interrupt hits in Thread.sleep.
        final RetryExecutor executor = new RetryExecutor(
                new FixedDelayRetryPolicy(5, Duration.ofMillis(200)),
                e -> true);

        final Thread t = Thread.ofVirtual().start(() -> {
            workerThread.set(Thread.currentThread());
            try {
                executor.execute(() -> {
                    calls.incrementAndGet();
                    throw new IOException("transient");
                }, () -> false);
            } catch (InterruptedException e) {
                // Expected — interrupt flag must be set by the time we catch it here.
                assertTrue(Thread.currentThread().isInterrupted(),
                        "Interrupt flag must be restored before InterruptedException escapes executor");
            } catch (Exception e) {
                fail("Expected InterruptedException, got: " + e);
            }
        });

        // Allow first attempt to execute, then interrupt during sleep.
        while (calls.get() < 1) {
            Thread.sleep(1);
        }
        t.interrupt();
        t.join(2000);
        assertFalse(t.isAlive(), "Worker thread should have finished");
    }
}
