package jsanca.katabasis.core.api.model;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * HTTP-specific timeout configuration for connection, initial response, and stream inactivity.
 *
 * <p>All three timeouts are independently optional. Absent (empty) means the JDK default
 * applies — no timeout for that phase.
 *
 * <ul>
 *   <li>{@link #connectTimeout()} — passed to {@link java.net.http.HttpClient}; limits TCP
 *       connection establishment. Applies to every request sent through the client.</li>
 *   <li>{@link #responseTimeout()} — passed to {@link java.net.http.HttpRequest#timeout()};
 *       limits the time from sending the request to receiving the first byte of response
 *       headers. Does NOT limit streaming body transfer.</li>
 *   <li>{@link #readInactivityTimeout()} — custom watchdog; fires if no bytes are received
 *       from the response body stream for the configured duration. Pause duration does not
 *       count toward this limit.</li>
 * </ul>
 *
 * @author jsanca &amp; elo
 */
public record HttpTimeoutConfig(
        Optional<Duration> connectTimeout,
        Optional<Duration> responseTimeout,
        Optional<Duration> readInactivityTimeout
) {

    public HttpTimeoutConfig {
        Objects.requireNonNull(connectTimeout, "connectTimeout must not be null");
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
        Objects.requireNonNull(readInactivityTimeout, "readInactivityTimeout must not be null");
        connectTimeout.ifPresent(d -> {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("connectTimeout must be positive: " + d);
            }
        });
        responseTimeout.ifPresent(d -> {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("responseTimeout must be positive: " + d);
            }
        });
        readInactivityTimeout.ifPresent(d -> {
            if (d.isNegative() || d.isZero()) {
                throw new IllegalArgumentException("readInactivityTimeout must be positive: " + d);
            }
        });
    }

    /**
     * Returns a configuration with no timeouts configured (all three optional fields absent).
     * The JDK defaults apply.
     *
     * @return disabled timeout configuration
     */
    public static HttpTimeoutConfig disabled() {
        return new HttpTimeoutConfig(Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * Returns a sensible default configuration: 30 s connect, 60 s response headers,
     * 30 s read inactivity.
     *
     * @return default timeout configuration
     */
    public static HttpTimeoutConfig defaultTimeouts() {
        return new HttpTimeoutConfig(
                Optional.of(Duration.ofSeconds(30)),
                Optional.of(Duration.ofSeconds(60)),
                Optional.of(Duration.ofSeconds(30))
        );
    }

    /**
     * Creates a builder with all timeouts absent.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder pre-populated from an existing configuration.
     *
     * @param config source configuration
     * @return a builder initialised with the supplied values
     */
    public static Builder copyOf(final HttpTimeoutConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        final Builder b = new Builder();
        config.connectTimeout().ifPresent(b::connectTimeout);
        config.responseTimeout().ifPresent(b::responseTimeout);
        config.readInactivityTimeout().ifPresent(b::readInactivityTimeout);
        return b;
    }

    /**
     * Builder for {@link HttpTimeoutConfig}.
     */
    public static final class Builder {
        private Duration connectTimeout;
        private Duration responseTimeout;
        private Duration readInactivityTimeout;

        private Builder() {}

        /**
         * Sets the connection timeout.
         *
         * @param d a positive duration
         * @return this builder
         */
        public Builder connectTimeout(final Duration d) {
            this.connectTimeout = Objects.requireNonNull(d, "connectTimeout must not be null");
            return this;
        }

        /**
         * Sets the response header timeout.
         *
         * @param d a positive duration
         * @return this builder
         */
        public Builder responseTimeout(final Duration d) {
            this.responseTimeout = Objects.requireNonNull(d, "responseTimeout must not be null");
            return this;
        }

        /**
         * Sets the read inactivity timeout.
         *
         * @param d a positive duration
         * @return this builder
         */
        public Builder readInactivityTimeout(final Duration d) {
            this.readInactivityTimeout = Objects.requireNonNull(d, "readInactivityTimeout must not be null");
            return this;
        }

        /**
         * Builds the configuration.
         *
         * @return a new {@link HttpTimeoutConfig}
         */
        public HttpTimeoutConfig build() {
            return new HttpTimeoutConfig(
                    Optional.ofNullable(connectTimeout),
                    Optional.ofNullable(responseTimeout),
                    Optional.ofNullable(readInactivityTimeout)
            );
        }
    }
}
