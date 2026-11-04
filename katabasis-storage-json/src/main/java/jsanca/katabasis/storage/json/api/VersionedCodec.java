package jsanca.katabasis.storage.json.api;

/**
 * Encodes a typed domain object to bytes and decodes bytes back to the domain type.
 *
 * <p>The byte representation is a self-describing JSON envelope containing the
 * {@link StorageKey}, schema version, document revision, and the serialized payload.
 * Implementations are responsible for migrating documents whose stored schema version
 * is older than the current one.
 *
 * <p>No JSON framework types appear in this interface.
 *
 * @param <T> the domain type this codec handles
 */
public interface VersionedCodec<T> {

    /**
     * Encodes {@code value} as a new document (revision zero) identified by {@code key}.
     *
     * @throws CodecException if encoding fails
     */
    byte[] encode(StorageKey key, T value);

    /**
     * Encodes {@code value} with an explicit {@code revision}.
     * Use this when updating an existing document to preserve the revision counter.
     *
     * @throws CodecException if encoding fails
     */
    byte[] encode(StorageKey key, T value, long revision);

    /**
     * Decodes {@code bytes} and returns the payload, applying migrations if the stored
     * schema version is older than {@link #currentSchemaVersion()}.
     *
     * @throws CodecException if the envelope is malformed, the schema version is unknown,
     *                        or migration fails
     */
    T decode(byte[] bytes);

    /**
     * Decodes {@code bytes} and returns the full {@link StorageDocument} including
     * envelope metadata.
     *
     * @throws CodecException if the envelope is malformed, the schema version is unknown,
     *                        or migration fails
     */
    StorageDocument<T> decodeDocument(byte[] bytes);

    /** Returns the schema version this codec writes for new documents. */
    int currentSchemaVersion();
}
