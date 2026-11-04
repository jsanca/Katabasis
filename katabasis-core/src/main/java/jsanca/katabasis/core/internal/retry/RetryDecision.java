package jsanca.katabasis.core.internal.retry;

import java.time.Duration;
import java.util.Objects;

/**
 * Outcome of a single retry policy evaluation.
 *
 * @param shouldRetry {@code true} if another attempt should be made
 * @param delay       how long to wait before the next attempt; {@link Duration#ZERO} for
 *                    immediate retry or when {@code shouldRetry} is {@code false}
 */
public record RetryDecision(boolean shouldRetry, Duration delay) {

    public static final RetryDecision ABORT = new RetryDecision(false, Duration.ZERO);

    public RetryDecision {
        Objects.requireNonNull(delay, "delay must not be null");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must be >= 0: " + delay);
        }
    }

    public static RetryDecision retryAfter(final Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        return new RetryDecision(true, delay);
    }
}
