package jsanca.katabasis.storage.json.internal.migration;

import jakarta.json.JsonObject;

/**
 * A single schema migration step that transforms a payload from one version to the next.
 *
 * <p>Implementations must be pure functions: given the same input payload they must always
 * produce the same output. No I/O, no class loading, no dynamic dispatch on untrusted
 * document metadata.
 *
 * <p>The {@link #toVersion()} is always {@code fromVersion() + 1}.
 */
public interface Migration {

    /** The schema version this migration reads. */
    int fromVersion();

    /**
     * Transforms a payload at {@link #fromVersion()} to {@code fromVersion() + 1}.
     * The envelope fields are not included; only the payload object is passed.
     */
    JsonObject migrate(JsonObject payload);

    /** Always {@code fromVersion() + 1}. */
    default int toVersion() {
        return fromVersion() + 1;
    }
}
