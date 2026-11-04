package jsanca.katabasis.core.internal.http;

import java.net.http.HttpTimeoutException;

/**
 * Thrown by the inactivity watchdog when no bytes are received from the response body
 * stream for longer than the configured {@code readInactivityTimeout}.
 *
 * <p>Extends {@link HttpTimeoutException} so that {@link HttpFailureClassifier} classifies
 * it as a transient failure and the retry executor will retry it if attempts remain.
 *
 * @author jsanca &amp; elo
 */
public final class ReadInactivityTimeoutException extends HttpTimeoutException {

    /**
     * Creates an exception with a descriptive message.
     *
     * @param message describes which download stalled and for how long
     */
    public ReadInactivityTimeoutException(final String message) {
        super(message);
    }
}
