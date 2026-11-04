package jsanca.katabasis.core.internal.retry;

import jsanca.katabasis.core.api.model.RetryConfig;

import java.util.Objects;
import java.util.Random;

/**
 * Factory for creating {@link RetryPolicy} instances from {@link RetryConfig} data.
 *
 * @author jsanca &amp; elo
 */
public final class RetryPolicies {

    private RetryPolicies() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Creates a {@link RetryPolicy} from the given configuration using a default
     * {@link Random} source.
     *
     * @param config the retry configuration
     * @return the corresponding policy
     */
    public static RetryPolicy fromConfig(final RetryConfig config) {
        return fromConfig(config, new Random());
    }

    /**
     * Creates a {@link RetryPolicy} from the given configuration using the supplied
     * {@link Random} source (use a seeded instance for deterministic tests).
     *
     * @param config the retry configuration
     * @param random random source for jitter in exponential backoff
     * @return the corresponding policy
     */
    public static RetryPolicy fromConfig(final RetryConfig config, final Random random) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(random, "random must not be null");
        return switch (config.kind()) {
            case NONE -> NoRetryPolicy.INSTANCE;
            case FIXED_DELAY -> new FixedDelayRetryPolicy(config.maxAttempts(), config.initialDelay());
            case EXPONENTIAL_BACKOFF -> new ExponentialBackoffRetryPolicy(
                    config.maxAttempts(),
                    config.initialDelay(),
                    config.maxDelay(),
                    config.jitterFactor(),
                    random);
        };
    }

}
