package jsanca.katabasis.core.api.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Result of submitting a download to the manager.
 *
 * <p>Submission is non-blocking: the returned status is
 * {@link DownloadStatus#PENDING} and further state is reported through events.
 *
 * @param downloadId the identifier assigned to the new download
 * @param status the status at submission time
 * @param createdAt the instant at which the download was registered
 * @author jsanca &amp; elo
 */
public record DownloadSubmissionResult (String downloadId, DownloadStatus status, Instant createdAt) {

    /**
     * Validates the required result fields.
     *
     * @throws NullPointerException if any field is {@code null}
     */
    public DownloadSubmissionResult {
        Objects.requireNonNull(downloadId, "downloadId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
