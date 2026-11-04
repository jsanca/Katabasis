package jsanca.katabasis.core.internal.retry;

/**
 * A single attempt of a retriable operation.
 */
@FunctionalInterface
public interface RetryableOperation {

    /**
     * Executes one attempt.
     *
     * @throws Exception on failure
     */
    void execute() throws Exception;
}
