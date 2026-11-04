package jsanca.katabasis.core.internal.http;

import java.net.http.HttpClient;

/**
 * Factory for pre-configured {@link HttpClient} instances.
 * @author jsanca &amp; elo
 */
public final class HttpClients {

    private HttpClients() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a new {@link HttpClient} that follows redirects normally.
     *
     * @return a default HTTP client
     */
    public static HttpClient createDefault() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}
