package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.model.DownloadControlResult;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;

import java.util.function.Consumer;

/**
 * Public API for managing download operations.
 *
 * <p>
 * This interface defines the entry point for submitting downloads and
 * receiving lifecycle events.
 * 
 * @author jsanca & elo
 */
public interface DownloadManager {

    /**
     * Submits a download request.
     *
     * @param request the download request
     * @return DownloadSubmissionResult
     */
    DownloadSubmissionResult download(jsanca.katabasis.core.api.model.DownloadRequest request);

    /**
     * Requests cancellation of the download identified by the given id.
     *
     * <p>
     * This method performs cooperative cancellation. A successful result means
     * the cancellation request was accepted for an existing task, but it does not
     * guarantee that the download has already stopped at the time this method
     * returns.
     *
     * <p>
     * The actual cancellation is observed when the running download detects the
     * cancellation request and emits a corresponding
     * {@code DownloadCancelledEvent}.
     *
     * @param downloadId the identifier of the download to cancel
     * @return a control result describing whether the cancellation request was
     *         accepted
     *         or whether no matching download was found
     * @throws NullPointerException if {@code downloadId} is null
     */
    DownloadControlResult cancel(String downloadId);

    DownloadControlResult pause(String downloadId);

    DownloadControlResult resume(String downloadId);

    /**
     * Subscribes to download events.
     *
     * @param listener consumer that will receive download events
     */
    void addListener(Consumer<DownloadEvent> listener);

    /**
     * Removes a previously registered listener.
     *
     * @param listener the listener to remove
     */
    void removeListener(Consumer<DownloadEvent> listener);
}
