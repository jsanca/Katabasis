package jsanca.katabasis.core.api.model;

import java.time.Duration;
import java.util.Objects;

/**
 * Configuration data for retry behaviour on transient download failures.
 *
 * <p>This is a pure data record; the retry algorithm is chosen by the internal
 * execution engine based on the values encoded here. Use the static factory
 * methods to create commonly-needed configurations.
 *
 * @param kind          the retry strategy to apply
 * @param maxAttempts   maximum number of retry attempts after the first failure;
 *                      {@code 0} means no retries
 * @param initialDelay  delay before the first retry; ignored when {@code kind} is
 *                      {@link Kind#NONE}
 * @param maxDelay      upper bound on delay; for {@link Kind#FIXED_DELAY} this equals
 *                      {@code initialDelay}
 * @param jitterFactor  fraction of the computed delay added as random jitter in the
 *                      range {@code [0.0, 1.0]}; {@code 0.0} disables jitter
 * @author jsanca &amp; elo
 */
public record RetryConfig(
        Kind kind,
        int maxAttempts,
        Duration initialDelay,
        Duration maxDelay,
        double jitterFactor
) {

    /**
     * Discriminator for the retry algorithm.
     */
    public enum Kind {
        /** No retries are performed. */
        NONE,
        /** Each retry waits the same fixed delay. */
        FIXED_DELAY,
        /** Each retry waits an exponentially increasing delay, optionally with jitter. */
        EXPONENTIAL_BACKOFF
    }

    public RetryConfig {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must be >= 0: " + maxAttempts);
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be >= 0: " + initialDelay);
        }
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("maxDelay must be >= 0: " + maxDelay);
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0]: " + jitterFactor);
        }
    }

    /**
     * Returns a configuration that performs no retries.
     *
     * @return no-retry configuration
     */
    public static RetryConfig noRetry() {
        return new RetryConfig(Kind.NONE, 0, Duration.ZERO, Duration.ZERO, 0.0);
    }

    /**
     * Returns a configuration that retries up to {@code maxAttempts} times with a
     * fixed delay between each attempt.
     *
     * @param maxAttempts number of retries (must be &gt; 0)
     * @param delay       delay between retries (must be positive)
     * @return fixed-delay retry configuration
     */
    public static RetryConfig fixedDelay(final int maxAttempts, final Duration delay) {
        Objects.requireNonNull(delay, "delay must not be null");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0: " + maxAttempts);
        }
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive: " + delay);
        }
        return new RetryConfig(Kind.FIXED_DELAY, maxAttempts, delay, delay, 0.0);
    }

    /**
     * Returns a configuration that retries with exponential backoff, no jitter.
     *
     * @param maxAttempts  number of retries (must be &gt; 0)
     * @param initialDelay delay before the first retry (must be positive)
     * @param maxDelay     upper bound on delay (must be &gt;= {@code initialDelay})
     * @return exponential-backoff retry configuration
     */
    public static RetryConfig exponentialBackoff(final int maxAttempts,
                                                  final Duration initialDelay,
                                                  final Duration maxDelay) {
        return exponentialBackoff(maxAttempts, initialDelay, maxDelay, 0.0);
    }

    /**
     * Returns a configuration that retries with exponential backoff and optional jitter.
     *
     * @param maxAttempts  number of retries (must be &gt; 0)
     * @param initialDelay delay before the first retry (must be positive)
     * @param maxDelay     upper bound on delay (must be &gt;= {@code initialDelay})
     * @param jitterFactor fraction of the computed delay added as random jitter;
     *                     must be in {@code [0.0, 1.0]}
     * @return exponential-backoff retry configuration
     */
    public static RetryConfig exponentialBackoff(final int maxAttempts,
                                                  final Duration initialDelay,
                                                  final Duration maxDelay,
                                                  final double jitterFactor) {
        Objects.requireNonNull(initialDelay, "initialDelay must not be null");
        Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("maxAttempts must be > 0: " + maxAttempts);
        }
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive: " + initialDelay);
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                    "maxDelay must be >= initialDelay: " + maxDelay + " < " + initialDelay);
        }
        return new RetryConfig(Kind.EXPONENTIAL_BACKOFF, maxAttempts, initialDelay, maxDelay, jitterFactor);
    }
}
