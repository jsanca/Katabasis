package jsanca.katabasis.core.api.model;

/**
 * Control operations that can be requested for an active download.
 *
 * @author jsanca &amp; elo
 */
public enum DownloadControlAction {

    /** Requests cooperative cancellation. */
    CANCEL,

    /** Requests a cooperative pause at the next checkpoint. */
    PAUSE,

    /** Requests resuming a paused download. */
    RESUME
}
