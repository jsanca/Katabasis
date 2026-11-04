package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.model.HttpTimeoutConfig;

import java.net.http.HttpClient;
import java.util.Objects;

/**
 * Factory for pre-configured {@link HttpClient} instances.
 * @author jsanca &amp; elo
 */
public final class HttpClients {

    private HttpClients() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a new {@link HttpClient} that follows redirects normally, with no timeouts.
     *
     * @return a default HTTP client
     */
    public static HttpClient createDefault() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Returns a new {@link HttpClient} configured from the supplied timeout settings.
     *
     * <p>Only {@link HttpTimeoutConfig#connectTimeout()} is applied at the client level.
     * Response and inactivity timeouts are applied per-request or via a watchdog.
     *
     * @param timeoutConfig timeout configuration to apply
     * @return a configured HTTP client
     */
    public static HttpClient createFor(final HttpTimeoutConfig timeoutConfig) {
        Objects.requireNonNull(timeoutConfig, "timeoutConfig must not be null");
        final HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL);
        timeoutConfig.connectTimeout().ifPresent(builder::connectTimeout);
        return builder.build();
    }
}
