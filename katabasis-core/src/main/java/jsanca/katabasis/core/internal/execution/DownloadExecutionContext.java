package jsanca.katabasis.core.internal.execution;

import jsanca.katabasis.core.api.model.RetryConfig;
import jsanca.katabasis.core.internal.retry.FailureClassifier;
import jsanca.katabasis.core.internal.retry.RetryExecutor;
import jsanca.katabasis.core.internal.retry.RetryPolicies;

import java.util.Objects;

/**
 * Execution context for a running download.
 *
 * <p>Groups runtime collaborators required by a {@code DownloadStrategy} without
 * polluting its method signature with multiple parameters.
 */
public final class DownloadExecutionContext {

    private final PauseCoordinator pauseCoordinator;
    private final RetryConfig retryConfig;

    /**
     * Creates an execution context with no retry.
     *
     * @param pauseCoordinator coordinator for pause/resume semantics
     */
    public DownloadExecutionContext(final PauseCoordinator pauseCoordinator) {
        this(pauseCoordinator, RetryConfig.noRetry());
    }

    /**
     * Creates an execution context with an explicit retry configuration.
     *
     * @param pauseCoordinator coordinator for pause/resume semantics
     * @param retryConfig      effective retry configuration for this download
     */
    public DownloadExecutionContext(final PauseCoordinator pauseCoordinator,
                                    final RetryConfig retryConfig) {
        this.pauseCoordinator = Objects.requireNonNull(pauseCoordinator,
                "pauseCoordinator cannot be null");
        this.retryConfig = Objects.requireNonNull(retryConfig, "retryConfig cannot be null");
    }

    /**
     * Returns the {@link PauseCoordinator} associated with this execution.
     *
     * @return pause coordinator
     */
    public PauseCoordinator pauseCoordinator() {
        return pauseCoordinator;
    }

    /**
     * Creates a {@link RetryExecutor} wired with the effective retry policy and the
     * supplied protocol-specific classifier.
     *
     * <p>Each call returns a new lightweight instance. Protocol strategies supply
     * their own classifier — the context owns only the policy.
     *
     * @param classifier protocol-specific failure classifier
     * @return a configured retry executor
     */
    public RetryExecutor retryExecutorFor(final FailureClassifier classifier) {
        return new RetryExecutor(RetryPolicies.fromConfig(retryConfig), classifier);
    }
}
