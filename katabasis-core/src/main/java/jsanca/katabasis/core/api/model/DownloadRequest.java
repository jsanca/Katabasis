
package jsanca.katabasis.core.api.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Public request model that describes a download operation.
 *
 * <p>This type represents the input contract for clients of the downloader module.
 * It intentionally captures only the information that callers should provide,
 * while execution-specific metadata remains internal to the downloader.
 *
 * <p>A builder is provided to keep call sites readable and to make the record easy
 * to evolve in the future if more optional fields are added.
 *
 * @param sourceUri   the source URI to download from
 * @param targetPath  the target file path where the download should be written
 * @param retryConfig per-request retry override; when present, supersedes the global
 *                    {@link DownloadConfig#retryConfig()}
 * @author jsanca &amp; elo
 */
public record DownloadRequest(URI sourceUri, Path targetPath, Optional<RetryConfig> retryConfig) {

    /**
     * Creates a new download request after validating required fields.
     *
     * @param sourceUri   the source URI to download from
     * @param targetPath  the target file path where the download should be written
     * @param retryConfig per-request retry override; {@code Optional.empty()} defers to
     *                    the global configuration
     * @throws NullPointerException if any required field is {@code null}
     */
    public DownloadRequest {
        Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        Objects.requireNonNull(targetPath, "targetPath must not be null");
        Objects.requireNonNull(retryConfig, "retryConfig must not be null");
    }

    /**
     * Creates a new builder for {@link DownloadRequest}.
     *
     * @return a new builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DownloadRequest}.
     */
    public static final class Builder {

        private URI sourceUri;
        private Path targetPath;
        private RetryConfig retryConfig;

        private Builder() {
        }

        /**
         * Sets the source URI for the download.
         *
         * @param sourceUri the source URI
         * @return this builder instance
         */
        public Builder sourceUri(final URI sourceUri) {
            this.sourceUri = sourceUri;
            return this;
        }

        /**
         * Sets the target path for the download.
         *
         * @param targetPath the destination path
         * @return this builder instance
         */
        public Builder targetPath(final Path targetPath) {
            this.targetPath = targetPath;
            return this;
        }

        /**
         * Sets a per-request retry configuration that overrides the global setting.
         *
         * @param retryConfig the override; {@code null} means defer to global config
         * @return this builder instance
         */
        public Builder retryConfig(final RetryConfig retryConfig) {
            this.retryConfig = retryConfig;
            return this;
        }

        /**
         * Builds a validated {@link DownloadRequest}.
         *
         * @return a new validated download request
         * @throws NullPointerException if any required field is missing
         */
        public DownloadRequest build() {
            return new DownloadRequest(sourceUri, targetPath, Optional.ofNullable(retryConfig));
        }
    }
}
