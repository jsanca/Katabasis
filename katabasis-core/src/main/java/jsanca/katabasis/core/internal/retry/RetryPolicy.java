package jsanca.katabasis.core.internal.retry;

/**
 * Decides whether and when to retry after a transient failure.
 *
 * <p>Implementations only answer timing questions: given this many failed attempts,
 * should another be made and after how long? Classification of failures as transient
 * vs permanent is handled separately by {@link FailureClassifier}.
 *
 * @author jsanca &amp; elo
 */
public interface RetryPolicy {

    /**
     * Evaluates whether another attempt should be made.
     *
     * @param failedAttempts number of attempts that have failed so far (0-based)
     * @param cause          the last exception
     * @return a decision indicating whether and when to retry
     */
    RetryDecision decide(int failedAttempts, Throwable cause);
}
