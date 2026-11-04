package jsanca.katabasis.core.api.model;

/**
 * Immutable snapshot of a single segment's download progress.
 *
 * <p>{@code startByte} and {@code endByte} are inclusive byte offsets in the
 * source resource. {@code confirmedBytes} is the number of bytes from
 * {@code startByte} that have been durably written to the partial file.
 *
 * @param index          zero-based segment index
 * @param startByte      inclusive start byte offset within the source resource
 * @param endByte        inclusive end byte offset within the source resource
 * @param confirmedBytes bytes from {@code startByte} durably written to disk
 */
public record SegmentSnapshot(
        int index,
        long startByte,
        long endByte,
        long confirmedBytes
) {

    public SegmentSnapshot {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0: " + index);
        }
        if (startByte < 0) {
            throw new IllegalArgumentException("startByte must be >= 0: " + startByte);
        }
        if (endByte < startByte) {
            throw new IllegalArgumentException(
                    "endByte must be >= startByte: startByte=" + startByte + " endByte=" + endByte);
        }
        if (confirmedBytes < 0 || confirmedBytes > (endByte - startByte + 1)) {
            throw new IllegalArgumentException(
                    "confirmedBytes out of range [0, " + (endByte - startByte + 1) + "]: " + confirmedBytes);
        }
    }
}
