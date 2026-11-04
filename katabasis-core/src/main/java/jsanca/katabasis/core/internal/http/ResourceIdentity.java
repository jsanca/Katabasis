package jsanca.katabasis.core.internal.http;

import java.util.Objects;
import java.util.Optional;

/**
 * Validated identity of an HTTP resource used to guard segmented downloads
 * against in-flight representation changes.
 *
 * <p>{@link #contentLength()} is the total resource size in bytes. The segmented
 * strategy writes directly to absolute byte offsets; it must not change.
 *
 * <p>{@link #strongETag()} is a strong ETag value as it appears in the HTTP header
 * (e.g. {@code "abc123"} — with enclosing double-quotes, without the {@code W/} prefix).
 * When present, it is included as {@code If-Range} on every segment request and validated
 * in every 206 response. A change in the ETag between segments indicates a representation
 * change and triggers an integrity failure.
 *
 * @author jsanca &amp; elo
 */
public record ResourceIdentity(long contentLength, Optional<String> strongETag) {

    public ResourceIdentity {
        Objects.requireNonNull(strongETag, "strongETag must not be null");
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be > 0: " + contentLength);
        }
    }

    /**
     * Creates an identity with a strong ETag.
     *
     * @param contentLength total resource size in bytes
     * @param strongETag    the strong ETag value as it appears in the ETag header
     * @return a new identity
     */
    public static ResourceIdentity of(final long contentLength, final String strongETag) {
        return new ResourceIdentity(contentLength,
                Optional.of(Objects.requireNonNull(strongETag, "strongETag must not be null")));
    }

    /**
     * Creates an identity without a resource validator.
     *
     * @param contentLength total resource size in bytes
     * @return a new identity with no ETag
     */
    public static ResourceIdentity ofSize(final long contentLength) {
        return new ResourceIdentity(contentLength, Optional.empty());
    }
}
