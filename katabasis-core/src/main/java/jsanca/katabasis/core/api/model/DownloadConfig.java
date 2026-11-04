package jsanca.katabasis.core.api.model;

import jsanca.katabasis.core.internal.util.ByteSize;

import java.time.Duration;
import java.util.Objects;

/**
 * Engine configuration for concurrency, segmented downloads, checkpointing, and retry.
 *
 * <p>Defaults: four concurrent downloads, four segments, a 5 MB minimum
 * size before segmentation is considered, a 5-second checkpoint interval,
 * and no retries.
 *
 * @param maxConcurrentDownloads maximum number of downloads executing at once
 * @param segmentCount number of chunks used for a segmented download
 * @param segmentMinSizeBytes minimum content length that enables segmentation
 * @param checkpointInterval how often the checkpoint coordinator persists progress;
 *                           must be positive
 * @param retryConfig global retry configuration; individual requests may override this
 * @author jsanca &amp; elo
 */
public record DownloadConfig(
        int maxConcurrentDownloads,
        int segmentCount,
        long segmentMinSizeBytes,
        Duration checkpointInterval,
        RetryConfig retryConfig
) {

    public DownloadConfig {
        Objects.requireNonNull(checkpointInterval, "checkpointInterval must not be null");
        Objects.requireNonNull(retryConfig, "retryConfig must not be null");
        if (checkpointInterval.isNegative() || checkpointInterval.isZero()) {
            throw new IllegalArgumentException(
                    "checkpointInterval must be positive: " + checkpointInterval);
        }
    }

    /**
     * Returns the default configuration.
     *
     * @return four concurrent downloads, four segments, 5 MB threshold, 5-second checkpoint,
     *         no retries
     */
    public static DownloadConfig defaultConfig() {
        return new DownloadConfig(4, 4, ByteSize.FIVE_MB, Duration.ofSeconds(5), RetryConfig.noRetry());
    }

    /**
     * Creates a builder with all values unset (checkpointInterval defaults to 5 seconds).
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-populated from an existing configuration.
     *
     * @param config the configuration to copy
     * @return a builder initialised with the supplied values
     */
    public static Builder copyOf(final DownloadConfig config) {
        return new Builder()
                .maxConcurrentDownloads(config.maxConcurrentDownloads())
                .segmentCount(config.segmentCount())
                .segmentMinSizeBytes(config.segmentMinSizeBytes())
                .checkpointInterval(config.checkpointInterval())
                .retryConfig(config.retryConfig());
    }

    /**
     * Builder for {@link DownloadConfig}.
     */
    public static final class Builder {
        private int maxConcurrentDownloads;
        private int segmentCount;
        private long segmentMinSizeBytes;
        private Duration checkpointInterval = Duration.ofSeconds(5);
        private RetryConfig retryConfig = RetryConfig.noRetry();

        private Builder() {}

        /**
         * Sets the maximum number of concurrent downloads.
         *
         * @param maxConcurrentDownloads the concurrency limit
         * @return this builder
         */
        public Builder maxConcurrentDownloads(final int maxConcurrentDownloads) {
            this.maxConcurrentDownloads = maxConcurrentDownloads;
            return this;
        }

        /**
         * Sets the number of segments used for a segmented download.
         *
         * @param segmentCount the segment count
         * @return this builder
         */
        public Builder segmentCount(final int segmentCount) {
            this.segmentCount = segmentCount;
            return this;
        }

        /**
         * Sets the minimum content length that enables segmentation.
         *
         * @param segmentMinSizeBytes the threshold in bytes
         * @return this builder
         */
        public Builder segmentMinSizeBytes(final long segmentMinSizeBytes) {
            this.segmentMinSizeBytes = segmentMinSizeBytes;
            return this;
        }

        /**
         * Sets how often the checkpoint coordinator persists progress.
         *
         * @param checkpointInterval the interval; must be positive
         * @return this builder
         */
        public Builder checkpointInterval(final Duration checkpointInterval) {
            this.checkpointInterval = Objects.requireNonNull(checkpointInterval,
                    "checkpointInterval must not be null");
            return this;
        }

        /**
         * Sets the global retry configuration.
         *
         * @param retryConfig the retry configuration
         * @return this builder
         */
        public Builder retryConfig(final RetryConfig retryConfig) {
            this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig must not be null");
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return a new {@link DownloadConfig}
         */
        public DownloadConfig build() {
            return new DownloadConfig(
                    maxConcurrentDownloads, segmentCount, segmentMinSizeBytes,
                    checkpointInterval, retryConfig);
        }
    }
}
