package jsanca.katabasis.core.api.model;

import jsanca.katabasis.core.internal.util.ByteSize;

/**
 * Encapsulates the app configuration
 * @param maxConcurrentDownloads
 * @author jsanca & elo
 */
public record DownloadConfig(
        int maxConcurrentDownloads,
        int segmentCount,
        long segmentMinSizeBytes
) {

    public static DownloadConfig defaultConfig() {
        return new DownloadConfig(4, 4, ByteSize.FIVE_MB);
    }

    public static Builder builder() {
        return new Builder();
    }


    public static Builder copyOf(DownloadConfig config) {
        return new Builder()
                .maxConcurrentDownloads(config.maxConcurrentDownloads())
                .segmentCount(config.segmentCount())
                .segmentMinSizeBytes(config.segmentMinSizeBytes());
    }


    public static class Builder {
        private int maxConcurrentDownloads;
        private int segmentCount;
        private long segmentMinSizeBytes;

        private Builder() {}

        public Builder maxConcurrentDownloads(int maxConcurrentDownloads) {
            this.maxConcurrentDownloads = maxConcurrentDownloads;
            return this;
        }

        public Builder segmentCount(int segmentCount) {
            this.segmentCount = segmentCount;
            return this;
        }

        public Builder segmentMinSizeBytes(long segmentMinSizeBytes) {
            this.segmentMinSizeBytes = segmentMinSizeBytes;
            return this;
        }

        public DownloadConfig build() {
            return new DownloadConfig(maxConcurrentDownloads, segmentCount, segmentMinSizeBytes);
        }
    }
}
