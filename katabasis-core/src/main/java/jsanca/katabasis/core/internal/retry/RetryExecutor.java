package jsanca.katabasis.core.internal.retry;

import jsanca.katabasis.core.internal.exception.DownloadCancelledException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Executes a retriable operation, sleeping between attempts according to the
 * configured {@link RetryPolicy} and skipping retry for permanent failures identified
 * by the {@link FailureClassifier}.
 *
 * <p>{@link DownloadCancelledException} is never retried — it is re-thrown immediately
 * regardless of the policy.
 *
 * <p>Instances are reusable across operations and thread-safe.
 *
 * @author jsanca &amp; elo
 */
public final class RetryExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetryExecutor.class);

    private final RetryPolicy policy;
    private final FailureClassifier classifier;

    /**
     * @param policy     decides how many times and how long to wait between retries
     * @param classifier decides whether a failure is worth retrying
     */
    public RetryExecutor(final RetryPolicy policy, final FailureClassifier classifier) {
        this.policy     = Objects.requireNonNull(policy,     "policy must not be null");
        this.classifier = Objects.requireNonNull(classifier, "classifier must not be null");
    }

    /**
     * Executes {@code op}, retrying on transient failures according to the policy.
     *
     * <p>The {@code cancelled} supplier is tested after each failure, before consulting
     * the retry policy or sleeping. If {@code true}, the last exception is re-thrown
     * immediately.
     *
     * <p>If the thread is interrupted during a retry sleep, the interrupt flag is
     * restored and {@link InterruptedException} is rethrown immediately without further
     * retry attempts.
     *
     * @param op        the operation to execute
     * @param cancelled tested after each failure; if {@code true}, retrying stops immediately
     * @throws Exception the final exception when retries are exhausted, the failure is
     *                   permanent, cancellation is detected, or the thread is interrupted
     */
    public void execute(final RetryableOperation op, final BooleanSupplier cancelled) throws Exception {
        Objects.requireNonNull(op,        "op must not be null");
        Objects.requireNonNull(cancelled, "cancelled must not be null");

        int failedAttempts = 0;
        while (true) {
            try {
                op.execute();
                return;
            } catch (final DownloadCancelledException e) {
                throw e;
            } catch (final Exception e) {
                if (cancelled.getAsBoolean()) {
                    log.debug("Cancellation detected after failure — not retrying");
                    throw e;
                }
                if (!classifier.isTransient(e)) {
                    log.debug("Permanent failure — not retrying: {}", e.getMessage());
                    throw e;
                }
                final RetryDecision decision = policy.decide(failedAttempts, e);
                if (!decision.shouldRetry()) {
                    log.debug("Retry policy exhausted after {} attempt(s) — giving up", failedAttempts + 1);
                    throw e;
                }
                failedAttempts++;
                log.info("Transient failure on attempt {} — retrying in {} ms: {}",
                        failedAttempts, decision.delay().toMillis(), e.getMessage());
                try {
                    sleep(decision.delay());
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
            }
        }
    }

    private static void sleep(final Duration delay) throws InterruptedException {
        final long ms = delay.toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }
}
