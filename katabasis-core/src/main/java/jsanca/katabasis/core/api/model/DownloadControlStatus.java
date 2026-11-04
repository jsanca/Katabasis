package jsanca.katabasis.core.api.model;

/**
 * Outcome of a control operation on a download.
 *
 * <p>{@link #REQUESTED} means the engine accepted the request; it does not
 * guarantee that the transfer has already reacted to it.
 *
 * @author jsanca &amp; elo
 */
public enum DownloadControlStatus {

    /** The operation was accepted and applied immediately. */
    ACCEPTED,

    /** The operation was accepted and will be observed cooperatively. */
    REQUESTED,

    /** No task matched the supplied download identifier. */
    NOT_FOUND,

    /** The task exists but cannot accept the operation in its current state. */
    INVALID_STATE,

    /** The task has already reached a terminal state. */
    ALREADY_DONE,

    /** The operation is not supported for this task or protocol. */
    UNSUPPORTED,

    /** The operation failed for an unexpected reason. */
    FAILED
}
