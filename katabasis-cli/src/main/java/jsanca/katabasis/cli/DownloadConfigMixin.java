package jsanca.katabasis.cli;

import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.HttpTimeoutConfig;
import jsanca.katabasis.core.api.model.RetryConfig;
import picocli.CommandLine.Option;

import java.time.Duration;

/**
 * picocli mixin exposing shared download configuration options.
 *
 * <p>Used by both {@link DownloadCommand} and {@link DownloadListCommand} via {@code @Mixin}.
 * All options are optional; unset options leave the corresponding field in the base
 * configuration unchanged.
 *
 * <p>Validation is deferred to {@link #buildConfig(DownloadConfig)}. The converter for
 * duration options accepts zero and negative values syntactically; this method rejects them.
 */
public class DownloadConfigMixin {

    @Option(names = "--segments", paramLabel = "<N>",
            description = "Number of parallel segments for HTTP downloads (default: 4). Must be >= 1.")
    Integer segments;

    @Option(names = "--retries", paramLabel = "<N>",
            description = "Retry attempts on transient failure, 1-second fixed delay (default: 0). Must be >= 0.")
    Integer retries;

    @Option(names = "--connect-timeout", paramLabel = "<duration>",
            converter = CliDurationConverter.class,
            description = "HTTP connection timeout (e.g. 30s, 500ms, PT10S). Must be positive.")
    Duration connectTimeout;

    @Option(names = "--response-timeout", paramLabel = "<duration>",
            converter = CliDurationConverter.class,
            description = "HTTP response header timeout. Must be positive.")
    Duration responseTimeout;

    @Option(names = "--read-timeout", paramLabel = "<duration>",
            converter = CliDurationConverter.class,
            description = "HTTP read inactivity timeout. Must be positive.")
    Duration readTimeout;

    /**
     * Returns a {@link DownloadConfig} derived from {@code base} with any explicitly supplied
     * options applied on top.
     *
     * @param base starting configuration; unset options leave its values unchanged
     * @return a new configuration with overrides applied
     * @throws IllegalArgumentException if any supplied value is out of range
     */
    DownloadConfig buildConfig(DownloadConfig base) {
        if (segments != null && segments < 1) {
            throw new IllegalArgumentException("--segments must be >= 1: " + segments);
        }
        if (retries != null && retries < 0) {
            throw new IllegalArgumentException("--retries must be >= 0: " + retries);
        }
        if (connectTimeout != null && (connectTimeout.isZero() || connectTimeout.isNegative())) {
            throw new IllegalArgumentException("--connect-timeout must be positive: " + connectTimeout);
        }
        if (responseTimeout != null && (responseTimeout.isZero() || responseTimeout.isNegative())) {
            throw new IllegalArgumentException("--response-timeout must be positive: " + responseTimeout);
        }
        if (readTimeout != null && (readTimeout.isZero() || readTimeout.isNegative())) {
            throw new IllegalArgumentException("--read-timeout must be positive: " + readTimeout);
        }

        final DownloadConfig.Builder configBuilder = DownloadConfig.copyOf(base);

        if (segments != null) {
            configBuilder.segmentCount(segments);
        }
        if (retries != null) {
            configBuilder.retryConfig(retries == 0
                    ? RetryConfig.noRetry()
                    : RetryConfig.fixedDelay(retries, Duration.ofSeconds(1)));
        }

        final HttpTimeoutConfig.Builder timeoutBuilder = HttpTimeoutConfig.copyOf(base.httpTimeoutConfig());
        if (connectTimeout != null) {
            timeoutBuilder.connectTimeout(connectTimeout);
        }
        if (responseTimeout != null) {
            timeoutBuilder.responseTimeout(responseTimeout);
        }
        if (readTimeout != null) {
            timeoutBuilder.readInactivityTimeout(readTimeout);
        }
        configBuilder.httpTimeoutConfig(timeoutBuilder.build());

        return configBuilder.build();
    }
}
