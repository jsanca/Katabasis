package jsanca.katabasis.core.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RetryConfigTest {

    @Test
    void noRetryHasZeroAttempts() {
        final RetryConfig config = RetryConfig.noRetry();
        assertEquals(RetryConfig.Kind.NONE, config.kind());
        assertEquals(0, config.maxAttempts());
    }

    @Test
    void fixedDelayPreservesParameters() {
        final RetryConfig config = RetryConfig.fixedDelay(3, Duration.ofSeconds(2));
        assertEquals(RetryConfig.Kind.FIXED_DELAY, config.kind());
        assertEquals(3, config.maxAttempts());
        assertEquals(Duration.ofSeconds(2), config.initialDelay());
        assertEquals(Duration.ofSeconds(2), config.maxDelay());
        assertEquals(0.0, config.jitterFactor());
    }

    @Test
    void exponentialBackoffPreservesParameters() {
        final RetryConfig config = RetryConfig.exponentialBackoff(
                5, Duration.ofMillis(100), Duration.ofSeconds(10), 0.2);
        assertEquals(RetryConfig.Kind.EXPONENTIAL_BACKOFF, config.kind());
        assertEquals(5, config.maxAttempts());
        assertEquals(Duration.ofMillis(100), config.initialDelay());
        assertEquals(Duration.ofSeconds(10), config.maxDelay());
        assertEquals(0.2, config.jitterFactor());
    }

    @Test
    void exponentialBackoffWithoutJitter() {
        final RetryConfig config = RetryConfig.exponentialBackoff(
                2, Duration.ofMillis(50), Duration.ofSeconds(1));
        assertEquals(0.0, config.jitterFactor());
    }

    @Test
    void fixedDelayRejectsZeroAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.fixedDelay(0, Duration.ofSeconds(1)));
    }

    @Test
    void fixedDelayRejectsNonPositiveDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.fixedDelay(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.fixedDelay(1, Duration.ofMillis(-1)));
    }

    @Test
    void exponentialBackoffRejectsMaxDelayLessThanInitialDelay() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.exponentialBackoff(
                        1, Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }

    @Test
    void exponentialBackoffRejectsInvalidJitterFactor() {
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.exponentialBackoff(
                        1, Duration.ofMillis(100), Duration.ofSeconds(10), -0.1));
        assertThrows(IllegalArgumentException.class,
                () -> RetryConfig.exponentialBackoff(
                        1, Duration.ofMillis(100), Duration.ofSeconds(10), 1.1));
    }

    @Test
    void compactConstructorRejectsNegativeMaxAttempts() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetryConfig(RetryConfig.Kind.NONE, -1,
                        Duration.ZERO, Duration.ZERO, 0.0));
    }

    @Test
    void compactConstructorRejectsNullKind() {
        assertThrows(NullPointerException.class,
                () -> new RetryConfig(null, 0, Duration.ZERO, Duration.ZERO, 0.0));
    }

    @Test
    void downloadConfigDefaultsToNoRetry() {
        final DownloadConfig config = DownloadConfig.defaultConfig();
        assertEquals(RetryConfig.Kind.NONE, config.retryConfig().kind());
    }

    @Test
    void downloadConfigBuilderPropagatesRetryConfig() {
        final RetryConfig retry = RetryConfig.fixedDelay(2, Duration.ofSeconds(1));
        final DownloadConfig config = DownloadConfig.builder()
                .maxConcurrentDownloads(2)
                .segmentCount(2)
                .segmentMinSizeBytes(1024)
                .retryConfig(retry)
                .build();
        assertEquals(retry, config.retryConfig());
    }

    @Test
    void downloadConfigCopyOfPropagatesRetryConfig() {
        final RetryConfig retry = RetryConfig.fixedDelay(3, Duration.ofMillis(500));
        final DownloadConfig original = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                .retryConfig(retry).build();
        final DownloadConfig copy = DownloadConfig.copyOf(original).build();
        assertEquals(retry, copy.retryConfig());
    }
}
