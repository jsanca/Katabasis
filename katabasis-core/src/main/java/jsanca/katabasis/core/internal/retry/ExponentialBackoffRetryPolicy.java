package jsanca.katabasis.core.internal.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * Retries with exponentially increasing delay, optionally with random jitter.
 *
 * <p>Delay formula: {@code min(initialDelay * 2^failedAttempts, maxDelay) + jitter}.
 * Jitter is computed as a random fraction of the base delay, bounded by
 * {@code jitterFactor}. The result is capped at {@code maxDelay}.
 *
 * <p>Pass a seeded {@link Random} for deterministic tests.
 *
 * @author jsanca &amp; elo
 */
public final class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final double jitterFactor;
    private final Random random;

    /**
     * @param maxAttempts  maximum number of retries after the first failure (must be &gt; 0)
     * @param initialDelay delay before the first retry (must be positive)
     * @param maxDelay     upper bound on delay (must be &gt;= {@code initialDelay})
     * @param jitterFactor fraction of the base delay to add as jitter ({@code [0.0, 1.0]})
     * @param random       random source for jitter; pass a seeded instance for determinism
     */
    ExponentialBackoffRetryPolicy(final int maxAttempts,
                                   final Duration initialDelay,
                                   final Duration maxDelay,
                                   final double jitterFactor,
                                   final Random random) {
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0: " + maxAttempts);
        }
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        Objects.requireNonNull(random, "random must not be null");
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive: " + initialDelay);
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay must be >= initialDelay: " + maxDelay + " < " + initialDelay);
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0]: " + jitterFactor);
        }
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.jitterFactor = jitterFactor;
        this.random = random;
    }

    @Override
    public RetryDecision decide(final int failedAttempts, final Throwable cause) {
        if (failedAttempts >= maxAttempts) {
            return RetryDecision.ABORT;
        }
        final long initMs = initialDelay.toMillis();
        final long maxMs  = maxDelay.toMillis();
        final long baseMs = Math.min(saturatingShift(initMs, failedAttempts), maxMs);
        final long jitterMs = jitterFactor > 0.0
                ? (long) (baseMs * jitterFactor * random.nextDouble())
                : 0L;
        final long delayMs = Math.min(baseMs + jitterMs, maxMs);
        return RetryDecision.retryAfter(Duration.ofMillis(delayMs));
    }

    /** Left-shifts {@code value} by {@code shift} bits, saturating at {@link Long#MAX_VALUE}. */
    private static long saturatingShift(final long value, final int shift) {
        if (shift <= 0 || value <= 0) {
            return value;
        }
        // If value would overflow, return MAX_VALUE so the subsequent min() caps at maxMs.
        if (shift >= Long.SIZE - 1 || value > (Long.MAX_VALUE >> shift)) {
            return Long.MAX_VALUE;
        }
        return value << shift;
    }
}
