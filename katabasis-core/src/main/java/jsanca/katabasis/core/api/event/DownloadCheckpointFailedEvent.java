package jsanca.katabasis.core.api.event;

import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadStatus;

import java.time.Instant;
import java.util.Objects;

/**
 * Event emitted when a checkpoint write fails.
 *
 * <p>The download itself continues; this event is informational so that monitoring
 * code can observe persistence failures without interrupting the transfer.
 *
 * @author jsanca &amp; elo
 */
public record DownloadCheckpointFailedEvent(
        DownloadInfo info,
        String errorMessage,
        Throwable cause,
        Instant occurredAt
) implements DownloadEvent {

    public DownloadCheckpointFailedEvent {
        Objects.requireNonNull(info,       "info must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }

    @Override
    public DownloadStatus status() {
        return DownloadStatus.IN_PROGRESS;
    }
}
