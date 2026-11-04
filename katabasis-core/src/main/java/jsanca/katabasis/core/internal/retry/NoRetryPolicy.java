package jsanca.katabasis.core.internal.retry;

/**
 * Always aborts — no retries are performed.
 *
 * @author jsanca &amp; elo
 */
public final class NoRetryPolicy implements RetryPolicy {

    public static final NoRetryPolicy INSTANCE = new NoRetryPolicy();

    private NoRetryPolicy() {}

    @Override
    public RetryDecision decide(final int failedAttempts, final Throwable cause) {
        return RetryDecision.ABORT;
    }
}
