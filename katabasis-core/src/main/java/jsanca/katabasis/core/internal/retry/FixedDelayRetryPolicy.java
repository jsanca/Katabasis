package jsanca.katabasis.core.internal.retry;

import java.time.Duration;
import java.util.Objects;

/**
 * Retries up to {@code maxAttempts} times with a fixed delay between each attempt.
 *
 * @author jsanca &amp; elo
 */
public final class FixedDelayRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final Duration delay;

    /**
     * @param maxAttempts maximum number of retries after the first failure (must be &gt; 0)
     * @param delay       fixed wait time between attempts (must be positive)
     */
    FixedDelayRetryPolicy(final int maxAttempts, final Duration delay) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0: " + maxAttempts);
        }
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive: " + delay);
        }
        this.maxAttempts = maxAttempts;
        this.delay = delay;
    }

    @Override
    public RetryDecision decide(final int failedAttempts, final Throwable cause) {
        if (failedAttempts >= maxAttempts) {
            return RetryDecision.ABORT;
        }
        return RetryDecision.retryAfter(delay);
    }
}
