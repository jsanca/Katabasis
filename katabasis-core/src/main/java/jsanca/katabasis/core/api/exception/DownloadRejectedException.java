package jsanca.katabasis.core.api.exception;

/**
 * Signals that a download request was rejected before execution.
 *
 * <p>Not currently thrown by the engine; reserved for future admission control
 * such as capacity limits or policy rejection.
 *
 * @author jsanca &amp; elo
 */
public class DownloadRejectedException extends RuntimeException {

    /**
     * Creates a new exception with no detail message or cause.
     */
    public DownloadRejectedException() {
    }

    /**
     * Creates a new exception with a detail message.
     *
     * @param message the detail message
     */
    public DownloadRejectedException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public DownloadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param cause the underlying cause
     */
    public DownloadRejectedException(Throwable cause) {
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
    public DownloadRejectedException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
