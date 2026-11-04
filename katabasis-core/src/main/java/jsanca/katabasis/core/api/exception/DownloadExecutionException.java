package jsanca.katabasis.core.api.exception;

/**
 * Signals that a download could not be executed.
 *
 * <p>Not currently thrown by the engine; reserved for execution-layer failures
 * that callers should distinguish from validation or rejection errors.
 *
 * @author jsanca &amp; elo
 */
public class DownloadExecutionException extends RuntimeException {

    /**
     * Creates a new exception with no detail message or cause.
     */
    public DownloadExecutionException() {
    }

    /**
     * Creates a new exception with a detail message.
     *
     * @param message the detail message
     */
    public DownloadExecutionException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public DownloadExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param cause the underlying cause
     */
    public DownloadExecutionException(Throwable cause) {
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
    public DownloadExecutionException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
