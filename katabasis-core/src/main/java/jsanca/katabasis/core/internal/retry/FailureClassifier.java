package jsanca.katabasis.core.internal.retry;

/**
 * Classifies a throwable as transient (worth retrying) or permanent (terminal).
 */
@FunctionalInterface
public interface FailureClassifier {

    /**
     * Returns {@code true} if the failure is transient and the operation may be retried.
     *
     * @param cause the exception that caused the failure
     * @return {@code true} for transient failures
     */
    boolean isTransient(Throwable cause);
}
