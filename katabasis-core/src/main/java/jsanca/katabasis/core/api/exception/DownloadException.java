package jsanca.katabasis.core.api.exception;

/**
 * Base unchecked exception for download failures.
 *
 * <p>Reserved as the common base for public failure signals. The current engine
 * does not throw this type; strategies report failures through
 * {@link jsanca.katabasis.core.api.event.DownloadFailedEvent} instead.
 *
 * @author jsanca &amp; elo
 */
public class DownloadException extends RuntimeException {

    /**
     * Creates a new exception with no detail message or cause.
     */
    public DownloadException() {
    }

    /**
     * Creates a new exception with a detail message.
     *
     * @param message the detail message
     */
    public DownloadException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param cause the underlying cause
     */
    public DownloadException(Throwable cause) {
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
    public DownloadException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
