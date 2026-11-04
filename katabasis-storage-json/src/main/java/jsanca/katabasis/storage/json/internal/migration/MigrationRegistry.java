package jsanca.katabasis.storage.json.internal.migration;

import jakarta.json.JsonObject;
import jsanca.katabasis.storage.json.api.CodecException;

import java.util.List;
import java.util.Objects;

/**
 * Ordered registry of {@link Migration} steps.
 *
 * <p>Migrations must form a contiguous chain starting from some minimum version up to
 * (but not including) the current schema version. Gaps or duplicates are rejected at
 * construction time.
 */
public final class MigrationRegistry {

    private final List<Migration> migrations;

    /**
     * @param migrations unordered list of migrations; the registry sorts them and validates
     *                   they form a contiguous chain
     * @throws IllegalArgumentException if the chain has gaps or duplicate {@code fromVersion} values
     */
    public MigrationRegistry(final List<Migration> migrations) {
        Objects.requireNonNull(migrations, "migrations must not be null");
        final List<Migration> sorted = migrations.stream()
                .sorted((a, b) -> Integer.compare(a.fromVersion(), b.fromVersion()))
                .toList();
        for (int i = 1; i < sorted.size(); i++) {
            final int prev = sorted.get(i - 1).fromVersion();
            final int curr = sorted.get(i).fromVersion();
            if (curr == prev) {
                throw new IllegalArgumentException(
                        "Duplicate migration fromVersion: " + curr);
            }
            if (curr != prev + 1) {
                throw new IllegalArgumentException(
                        "Migration chain gap between version " + prev + " and " + curr);
            }
        }
        this.migrations = List.copyOf(sorted);
    }

    /**
     * Applies migrations in sequence to bring {@code payload} from {@code storedVersion}
     * up to {@code targetVersion}.
     *
     * @throws CodecException if {@code storedVersion} > {@code targetVersion} (future version),
     *                        or if no migration path exists
     */
    public JsonObject migrate(final JsonObject payload, final int storedVersion, final int targetVersion) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (storedVersion == targetVersion) {
            return payload;
        }
        if (storedVersion > targetVersion) {
            throw new CodecException(
                    "Document schema version " + storedVersion
                    + " is newer than the codec's current version " + targetVersion
                    + ". Upgrade the application to read this document.");
        }
        JsonObject current = payload;
        for (int v = storedVersion; v < targetVersion; v++) {
            final int from = v;
            final Migration step = migrations.stream()
                    .filter(m -> m.fromVersion() == from)
                    .findFirst()
                    .orElseThrow(() -> new CodecException(
                            "No migration registered from version " + from
                            + " to " + (from + 1)));
            current = step.migrate(current);
        }
        return current;
    }

    /** Returns the number of registered migrations. */
    public int size() {
        return migrations.size();
    }
}
