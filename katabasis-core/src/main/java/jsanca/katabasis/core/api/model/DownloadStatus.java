package jsanca.katabasis.core.api.model;

/**
 * Represents the lifecycle states of a download.
 *
 * <p>This enum is part of the public API and is intended to be used by clients
 * to understand and react to the current state of a download operation.
 * @author jsanca &amp; elo
 */
public enum DownloadStatus {

    /**
     * The download has been created but not yet started.
     */
    PENDING,

    /**
     * The download is currently in progress.
     */
    IN_PROGRESS,

    /**
     * The download is currently paused
     */
    PAUSED,

    /**
     * The download completed successfully. (terminal state)
     */
    COMPLETED,

    /**
     * The download failed due to an error.
     */
    FAILED,

    /**
     * The download was cancelled before completion.
     */
    CANCELLED;

    /**
     * Returns whether this status is terminal.
     *
     * @return {@code true} for {@code COMPLETED}, {@code FAILED}, or {@code CANCELLED}
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Returns whether this status represents an active transfer.
     *
     * @return {@code true} only for {@code IN_PROGRESS}
     */
    public boolean isActive() {
        return this == IN_PROGRESS;
    }
}
