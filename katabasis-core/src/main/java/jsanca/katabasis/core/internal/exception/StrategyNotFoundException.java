package jsanca.katabasis.core.internal.exception;

/**
 * Signals that no download strategy matches a download source.
 *
 * <p>Not currently thrown: {@code DefaultDownloadStrategyResolver} raises
 * {@link IllegalArgumentException} for unsupported schemes and this type is
 * retained as a reserved internal signal.
 *
 * @author jsanca &amp; elo
 */
public class StrategyNotFoundException extends RuntimeException {

    /**
     * Creates a new exception with no detail message or cause.
     */
    public StrategyNotFoundException() {
    }

    /**
     * Creates a new exception with a detail message.
     *
     * @param message the detail message
     */
    public StrategyNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a detail message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public StrategyNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new exception with a cause.
     *
     * @param cause the underlying cause
     */
    public StrategyNotFoundException(Throwable cause) {
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
    public StrategyNotFoundException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
