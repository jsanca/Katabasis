package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.internal.exception.HttpDownloadStatusException;
import jsanca.katabasis.core.internal.retry.FailureClassifier;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * Classifies HTTP download failures as transient or permanent.
 *
 * <p>Transient failures are those that may succeed on a subsequent attempt:
 * <ul>
 *   <li>Network-level errors — {@link ConnectException}, {@link SocketTimeoutException},
 *       {@link SocketException}, {@link HttpTimeoutException}</li>
 *   <li>HTTP 429 (Too Many Requests) and 5xx server errors, except 501 (Not Implemented)</li>
 * </ul>
 *
 * <p>All other failures are considered permanent: 4xx client errors, 501, non-HTTP IOExceptions
 * that are not network-level, and all unchecked exceptions.
 *
 * @author jsanca &amp; elo
 */
public final class HttpFailureClassifier implements FailureClassifier {

    public static final HttpFailureClassifier INSTANCE = new HttpFailureClassifier();

    private HttpFailureClassifier() {}

    @Override
    public boolean isTransient(final Throwable cause) {
        if (cause instanceof HttpDownloadStatusException h) {
            return isTransientStatusCode(h.statusCode());
        }
        if (cause instanceof SocketTimeoutException
                || cause instanceof HttpTimeoutException
                || cause instanceof ConnectException) {
            return true;
        }
        if (cause instanceof SocketException) {
            return true;
        }
        if (cause instanceof IOException) {
            final Throwable nested = cause.getCause();
            if (nested instanceof SocketTimeoutException
                    || nested instanceof ConnectException
                    || nested instanceof SocketException) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTransientStatusCode(final int code) {
        return code == 429
                || (code >= 500 && code <= 599 && code != 501);
    }
}
