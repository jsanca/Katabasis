package jsanca.katabasis.core.internal.exception;


import jsanca.katabasis.core.api.model.DownloadInfo;

import java.time.Instant;

/**
 * Internal signal that a running download observed a cancellation request.
 *
 * <p>Carries a progress snapshot so that {@code HttpDownloadSupport} can build a
 * {@code DownloadCancelledEvent} without re-reading task state.
 *
 * @author jsanca &amp; elo
 */
public class DownloadCancelledException extends RuntimeException {

    private final DownloadInfo info;
    private final long downloadedBytes;
    private final long totalBytes;
    private final double progress;

    private final Instant cancelledAt;

    /**
     * Creates a cancellation signal with the progress reached at cancellation.
     *
     * @param info identifying information of the cancelled download
     * @param downloadedBytes bytes written before cancellation
     * @param totalBytes expected total bytes, or {@code 0} when unknown
     * @param progress progress ratio in the range {@code [0.0, 1.0]}
     * @param cancelledAt instant at which cancellation was observed
     */
    public DownloadCancelledException(final DownloadInfo info,
                                      final long downloadedBytes,
                                      final long totalBytes,
                                      final double progress,
                                      final Instant cancelledAt) {

        this.info = info;
        this.downloadedBytes = downloadedBytes;
        this.totalBytes = totalBytes;
        this.progress = progress;
        this.cancelledAt = cancelledAt;
    }

    /**
     * Returns the identifying information of the cancelled download.
     *
     * @return the download information
     */
    public DownloadInfo info() {
        return info;
    }

    /**
     * Returns the instant at which cancellation was observed.
     *
     * @return the cancellation timestamp
     */
    public Instant cancelledAt() {
        return cancelledAt;
    }

    /**
     * Returns the number of bytes written before cancellation.
     *
     * @return bytes read so far
     */
    public long bytesRead() {
        return downloadedBytes;
    }

    /**
     * Returns the expected total bytes, or {@code 0} when unknown.
     *
     * @return expected content length
     */
    public long totalBytes() {
        return totalBytes;
    }

    /**
     * Returns the progress ratio in the range {@code [0.0, 1.0]}.
     *
     * @return the progress ratio
     */
    public double progress() {
        return this.progress;
    }
}
