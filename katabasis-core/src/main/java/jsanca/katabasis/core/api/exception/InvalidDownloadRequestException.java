package jsanca.katabasis.core.api.exception;

/**
 * Signals that a download request is invalid.
 *
 * <p>Not currently thrown: {@code DownloadRequest} validates its required
 * fields with {@link NullPointerException}. This type is reserved for future
 * semantic validation that cannot be expressed by null checks.
 *
 * @author jsanca &amp; elo
 */
public class InvalidDownloadRequestException extends RuntimeException {

    /**
     * Creates a new exception with no detail message or cause.
     */
    public InvalidDownloadRequestException() {
    }

    /**
     * Creates a new exception with a detail message.
     *
     * @param message the detail message
     */
    public InvalidDownloadRequestException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InvalidDownloadRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param cause the underlying cause
     */
    public InvalidDownloadRequestException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a new exception with full control over suppression and stack trace.
     *
     * @param message the detail message
     * @param cause the underlying cause
     * @param enableSuppression whether suppression is enabled
     * @param writableStackTrace whether the stack trace is writable
     */
    public InvalidDownloadRequestException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
