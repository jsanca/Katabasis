package jsanca.katabasis.core.api.manager;

import jsanca.katabasis.core.api.model.DownloadSnapshot;

import java.time.Instant;

/**
 * Strategy contract for building an internally consistent {@link DownloadSnapshot}
 * at any point in time.
 *
 * <p>Implementations are created by download strategies and registered on the
 * associated {@link jsanca.katabasis.core.internal.model.DownloadTask} at the
 * start of execution. They capture references to the strategy's own mutable
 * accounting state so that snapshots accurately reflect segment boundaries,
 * confirmed byte counts, and resource identity details that are not visible
 * through public events.
 *
 * @author jsanca &amp; elo
 */
@FunctionalInterface
public interface CheckpointSource {

    /**
     * Builds an immutable snapshot of the current download state.
     *
     * @param at the timestamp to record as the checkpoint time
     * @return a consistent snapshot at this instant
     */
    DownloadSnapshot buildSnapshot(Instant at);
}
