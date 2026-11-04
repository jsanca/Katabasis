package jsanca.katabasis.core.internal.http;

import java.io.IOException;

/**
 * Thrown when a segment response violates the resource identity established during
 * the initial capability probe.
 *
 * <p>This exception signals a permanent failure — the resource changed, the server
 * returned an incorrect range, or the response body was truncated or overlong. It
 * is classified as non-transient by {@link HttpFailureClassifier} and therefore
 * does not trigger a retry.
 *
 * @author jsanca &amp; elo
 */
public final class ResourceIntegrityException extends IOException {

    /**
     * Creates an integrity exception with a descriptive message.
     *
     * @param message describes which invariant was violated
     */
    public ResourceIntegrityException(final String message) {
        super(message);
    }
}
