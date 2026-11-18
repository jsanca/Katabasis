package jsanca.katabasis.core.api.manager;

import java.util.Optional;

/**
 * Lookup port for retrieving the {@link CheckpointSource} registered by the
 * active strategy for a given download.
 *
 * <p>The {@link DownloadManager} implementation implements this interface so that
 * external components (e.g., the checkpoint coordinator) can obtain accurate
 * snapshots without depending on internal implementation types.
 *
 * <p>The returned source is only present while the download is actively executing
 * and after the strategy has registered it. It is absent for unknown download IDs
 * or downloads that have not yet registered a source.
 *
 * @author jsanca &amp; elo
 */
public interface CheckpointSourceProvider {

    /**
     * Returns the {@link CheckpointSource} for an active download, if one has
     * been registered.
     *
     * @param downloadId the identifier of the download
     * @return the registered source, or empty if the download is not active or
     *         has not yet registered a source
     */
    Optional<CheckpointSource> findSource(String downloadId);
}
