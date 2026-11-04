package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.model.DownloadSnapshot;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for download state.
 *
 * <p>Implementations must be thread-safe. The store is responsible for
 * durable persistence of {@link DownloadSnapshot} records; it does not
 * manage the download lifecycle or trigger recovery.
 *
 * <p>No JSON or filesystem types appear in this interface. Adapters
 * (e.g. {@code FileDownloadStateStore}) implement it externally.
 *
 * @author jsanca &amp; elo
 */
public interface DownloadStateStore {

    /**
     * Persists or replaces the snapshot for the given download.
     *
     * <p>Thread-safe for concurrent saves of different download IDs. Concurrent
     * saves for the same ID are serialized by the implementation.
     *
     * @param snapshot the snapshot to persist
     * @throws jsanca.katabasis.core.api.exception.DownloadException if the write fails
     */
    void save(DownloadSnapshot snapshot);

    /**
     * Returns the most recently saved snapshot for the given download ID, or
     * {@link Optional#empty()} if no snapshot exists or the stored document is corrupt.
     *
     * @param downloadId the download identifier
     * @return the stored snapshot, or empty
     */
    Optional<DownloadSnapshot> findById(String downloadId);

    /**
     * Returns all snapshots whose status is recoverable
     * ({@link jsanca.katabasis.core.api.model.DownloadStatus#IN_PROGRESS} or
     * {@link jsanca.katabasis.core.api.model.DownloadStatus#PAUSED}).
     *
     * <p>Corrupt or unreadable documents are silently skipped.
     *
     * @return an unmodifiable list of recoverable snapshots
     */
    List<DownloadSnapshot> findRecoverable();

    /**
     * Removes the persisted snapshot for the given download ID, if present.
     *
     * @param downloadId the download identifier
     */
    void delete(String downloadId);
}
