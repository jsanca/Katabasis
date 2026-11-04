package jsanca.katabasis.core.api.model;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable recoverable snapshot of a download, persisted for future recovery.
 *
 * <p>This record captures the minimum state needed to resume or report on an
 * interrupted download after an application restart. It is intentionally separate
 * from {@link DownloadTaskSnapshot} (which is a live runtime view) and must not
 * contain credentials, connections, thread references, or cancellation tokens.
 *
 * <p>{@code partialPath} is the in-progress file being written. If {@code null},
 * the partial file is assumed to be {@code targetPath} itself.
 *
 * <p>{@code etag} is a strong ETag for resource revalidation before resume; may
 * be {@code null} if the server did not provide one.
 *
 * <p>{@code expectedTotalBytes} is {@code -1} when the server did not advertise
 * a content length.
 *
 * <p>{@code segments} is empty for single-stream downloads and non-empty for
 * segmented downloads.
 *
 * @param downloadId          stable unique identifier
 * @param sourceUri           original source URI
 * @param targetPath          final destination path
 * @param partialPath         in-progress partial file, or {@code null} if same as targetPath
 * @param etag                strong ETag for revalidation, or {@code null}
 * @param expectedTotalBytes  expected total content length, or {@code -1} if unknown
 * @param confirmedBytes      bytes durably written to disk
 * @param status              download status at checkpoint time
 * @param segments            segment boundaries and confirmed offsets; empty for single-stream
 * @param createdAt           when the download was originally submitted
 * @param checkpointAt        when this snapshot was taken
 */
public record DownloadSnapshot(
        String downloadId,
        URI sourceUri,
        Path targetPath,
        Path partialPath,
        String etag,
        long expectedTotalBytes,
        long confirmedBytes,
        DownloadStatus status,
        List<SegmentSnapshot> segments,
        Instant createdAt,
        Instant checkpointAt
) {

    public DownloadSnapshot {
        Objects.requireNonNull(downloadId,    "downloadId must not be null");
        Objects.requireNonNull(sourceUri,     "sourceUri must not be null");
        Objects.requireNonNull(targetPath,    "targetPath must not be null");
        Objects.requireNonNull(status,        "status must not be null");
        Objects.requireNonNull(createdAt,     "createdAt must not be null");
        Objects.requireNonNull(checkpointAt,  "checkpointAt must not be null");
        if (downloadId.isBlank()) {
            throw new IllegalArgumentException("downloadId must not be blank");
        }
        if (expectedTotalBytes < -1) {
            throw new IllegalArgumentException(
                    "expectedTotalBytes must be -1 (unknown) or >= 0: " + expectedTotalBytes);
        }
        if (confirmedBytes < 0) {
            throw new IllegalArgumentException("confirmedBytes must be >= 0: " + confirmedBytes);
        }
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** Returns {@code true} if this snapshot represents a download that can be resumed. */
    public boolean isRecoverable() {
        return status == DownloadStatus.IN_PROGRESS || status == DownloadStatus.PAUSED;
    }

    /** Returns the effective partial path: {@code partialPath} if set, otherwise {@code targetPath}. */
    public Path effectivePartialPath() {
        return partialPath != null ? partialPath : targetPath;
    }
}
