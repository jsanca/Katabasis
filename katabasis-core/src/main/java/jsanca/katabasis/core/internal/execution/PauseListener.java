package jsanca.katabasis.core.internal.execution;

/**
 * Receives the pause and resume transitions observed by a running download.
 *
 * <p>Callbacks are invoked by {@link PauseCoordinator} while the download
 * thread is blocked; implementations should emit the corresponding events.
 *
 * @author jsanca &amp; elo
 */
public interface PauseListener {

    /**
     * Invoked once when the download enters the paused state.
     */
    void onPaused();

    /**
     * Invoked once when the download leaves the paused state.
     */
    void onResumed();
}
