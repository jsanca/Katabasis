package jsanca.katabasis.storage.json.internal.codec;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonValue;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.adapter.JsonbAdapter;
import jsanca.katabasis.storage.json.api.CodecException;
import jsanca.katabasis.storage.json.api.StorageDocument;
import jsanca.katabasis.storage.json.api.StorageKey;
import jsanca.katabasis.storage.json.api.VersionedCodec;
import jsanca.katabasis.storage.json.internal.migration.Migration;
import jsanca.katabasis.storage.json.internal.migration.MigrationRegistry;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Version-aware JSON codec that wraps a typed payload in a stable envelope.
 *
 * <h2>Envelope format</h2>
 * <pre>
 * {
 *   "envelopeVersion": "1",
 *   "type": "&lt;StorageKey.type&gt;",
 *   "id":   "&lt;StorageKey.id&gt;",
 *   "schemaVersion": &lt;int&gt;,
 *   "revision": &lt;long&gt;,
 *   "payload": { ... }
 * }
 * </pre>
 *
 * <h2>Migration</h2>
 * On decode the stored {@code schemaVersion} is compared against
 * {@link #currentSchemaVersion()}. If the stored version is older, all registered
 * {@link Migration} steps are applied in order before JSON-B deserialization.
 * A stored version newer than the current version throws {@link CodecException}.
 *
 * <h2>Thread safety</h2>
 * Instances are thread-safe once constructed.
 *
 * @param <T> the domain type this codec handles
 */
public final class JsonEnvelopeCodec<T> implements VersionedCodec<T> {

    static final String ENVELOPE_VERSION = "1";
    static final String F_ENV_VERSION   = "envelopeVersion";
    static final String F_TYPE          = "type";
    static final String F_ID            = "id";
    static final String F_SCHEMA        = "schemaVersion";
    static final String F_REVISION      = "revision";
    static final String F_PAYLOAD       = "payload";

    private final Class<T> type;
    private final int currentSchemaVersion;
    private final Jsonb jsonb;
    private final MigrationRegistry migrations;

    private JsonEnvelopeCodec(final Builder<T> builder) {
        this.type = builder.type;
        this.currentSchemaVersion = builder.schemaVersion;
        this.migrations = new MigrationRegistry(builder.migrations);
        final JsonbConfig config = new JsonbConfig();
        builder.adapters.forEach(config::withAdapters);
        this.jsonb = JsonbBuilder.create(config);
    }

    // ── VersionedCodec ────────────────────────────────────────────────────────

    @Override
    public byte[] encode(final StorageKey key, final T value) {
        return encode(key, value, 0L);
    }

    @Override
    public byte[] encode(final StorageKey key, final T value, final long revision) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0: " + revision);
        }
        try {
            final String payloadJson = jsonb.toJson(value);
            final JsonObject payload;
            try (final JsonReader reader = Json.createReader(new StringReader(payloadJson))) {
                payload = reader.readObject();
            }
            final JsonObjectBuilder env = Json.createObjectBuilder()
                    .add(F_ENV_VERSION, ENVELOPE_VERSION)
                    .add(F_TYPE,        key.type())
                    .add(F_ID,          key.id())
                    .add(F_SCHEMA,      currentSchemaVersion)
                    .add(F_REVISION,    revision)
                    .add(F_PAYLOAD,     payload);
            return env.build().toString().getBytes(StandardCharsets.UTF_8);
        } catch (final Exception e) {
            throw new CodecException("Failed to encode " + type.getSimpleName(), e);
        }
    }

    @Override
    public T decode(final byte[] bytes) {
        return decodeDocument(bytes).payload();
    }

    @Override
    public StorageDocument<T> decodeDocument(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        final JsonObject env = parseEnvelope(bytes);
        validateEnvelopeStructure(env);

        final StorageKey key = new StorageKey(
                env.getString(F_TYPE),
                env.getString(F_ID));
        final int storedVersion   = env.getInt(F_SCHEMA);
        final long revision       = env.getJsonNumber(F_REVISION).longValue();
        final JsonObject rawPayload = env.getJsonObject(F_PAYLOAD);

        final JsonObject migratedPayload = migrations.migrate(
                rawPayload, storedVersion, currentSchemaVersion);

        try {
            final T payload = jsonb.fromJson(migratedPayload.toString(), type);
            return new StorageDocument<>(key, currentSchemaVersion, revision, payload);
        } catch (final Exception e) {
            throw new CodecException(
                    "Failed to deserialize payload for " + key + " at schema version "
                    + currentSchemaVersion, e);
        }
    }

    @Override
    public int currentSchemaVersion() {
        return currentSchemaVersion;
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private static JsonObject parseEnvelope(final byte[] bytes) {
        try (final JsonReader reader = Json.createReader(
                new ByteArrayInputStream(bytes))) {
            return reader.readObject();
        } catch (final Exception e) {
            throw new CodecException("Malformed JSON envelope", e);
        }
    }

    private static void validateEnvelopeStructure(final JsonObject env) {
        requireString(env, F_ENV_VERSION);
        requireString(env, F_TYPE);
        requireString(env, F_ID);
        requireInt(env,    F_SCHEMA);
        requireNumber(env, F_REVISION);
        requireObject(env, F_PAYLOAD);

        final String envVersion = env.getString(F_ENV_VERSION);
        if (!ENVELOPE_VERSION.equals(envVersion)) {
            throw new CodecException(
                    "Unsupported envelopeVersion '" + envVersion
                    + "'; expected '" + ENVELOPE_VERSION + "'");
        }
        final int schemaVersion = env.getInt(F_SCHEMA);
        if (schemaVersion < 1) {
            throw new CodecException("schemaVersion must be >= 1, got: " + schemaVersion);
        }
        final long revision = env.getJsonNumber(F_REVISION).longValue();
        if (revision < 0) {
            throw new CodecException("revision must be >= 0, got: " + revision);
        }
    }

    private static void requireString(final JsonObject obj, final String field) {
        if (!obj.containsKey(field) || obj.get(field).getValueType() != JsonValue.ValueType.STRING) {
            throw new CodecException("Envelope missing or non-string field: " + field);
        }
    }

    private static void requireInt(final JsonObject obj, final String field) {
        requireNumber(obj, field);
        try {
            obj.getInt(field);
        } catch (final ClassCastException | ArithmeticException e) {
            throw new CodecException("Envelope field must be an integer: " + field, e);
        }
    }

    private static void requireNumber(final JsonObject obj, final String field) {
        if (!obj.containsKey(field) || obj.get(field).getValueType() != JsonValue.ValueType.NUMBER) {
            throw new CodecException("Envelope missing or non-numeric field: " + field);
        }
    }

    private static void requireObject(final JsonObject obj, final String field) {
        if (!obj.containsKey(field) || obj.get(field).getValueType() != JsonValue.ValueType.OBJECT) {
            throw new CodecException("Envelope missing or non-object field: " + field);
        }
    }

    // ── builder ───────────────────────────────────────────────────────────────

    /**
     * Returns a builder for a codec that handles {@code type}.
     *
     * <p>Example:
     * <pre>
     * VersionedCodec&lt;Snapshot&gt; codec = JsonEnvelopeCodec
     *     .builder(Snapshot.class)
     *     .schemaVersion(2)
     *     .addMigration(new SnapshotV1ToV2())
     *     .adapter(new UriAdapter())
     *     .build();
     * </pre>
     */
    public static <T> Builder<T> builder(final Class<T> type) {
        return new Builder<>(type);
    }

    public static final class Builder<T> {

        private final Class<T> type;
        private int schemaVersion = 1;
        private final List<Migration> migrations = new ArrayList<>();
        private final List<JsonbAdapter<?, ?>> adapters = new ArrayList<>();

        private Builder(final Class<T> type) {
            this.type = Objects.requireNonNull(type, "type must not be null");
        }

        /** Sets the current schema version written by {@link #encode}. Defaults to 1. */
        public Builder<T> schemaVersion(final int version) {
            if (version < 1) {
                throw new IllegalArgumentException("schemaVersion must be >= 1: " + version);
            }
            this.schemaVersion = version;
            return this;
        }

        /** Registers a migration step. Multiple calls are allowed; order is by version. */
        public Builder<T> addMigration(final Migration migration) {
            this.migrations.add(Objects.requireNonNull(migration, "migration must not be null"));
            return this;
        }

        /** Registers a JSON-B adapter for a custom type. */
        public Builder<T> adapter(final JsonbAdapter<?, ?> adapter) {
            this.adapters.add(Objects.requireNonNull(adapter, "adapter must not be null"));
            return this;
        }

        /** Registers multiple JSON-B adapters. */
        public Builder<T> adapters(final JsonbAdapter<?, ?>... adapterArray) {
            this.adapters.addAll(Arrays.asList(adapterArray));
            return this;
        }

        /** Builds an immutable {@link JsonEnvelopeCodec}. */
        public JsonEnvelopeCodec<T> build() {
            return new JsonEnvelopeCodec<>(this);
        }
    }
}
