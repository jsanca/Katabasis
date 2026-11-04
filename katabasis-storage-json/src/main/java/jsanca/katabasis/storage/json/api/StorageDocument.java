package jsanca.katabasis.storage.json.api;

import java.util.Objects;

/**
 * Decoded envelope metadata paired with the deserialized payload.
 *
 * <p>{@code schemaVersion} is the version declared in the stored document after migration.
 * {@code revision} is an internal monotonic write counter managed by the storage layer;
 * zero means "new document". It is incremented on each successful save but does not
 * provide optimistic concurrency control — the store serializes concurrent writes per
 * download ID with a lock.
 */
public record StorageDocument<T>(StorageKey key, int schemaVersion, long revision, T payload) {

    public StorageDocument {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1: " + schemaVersion);
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0: " + revision);
        }
    }
}
