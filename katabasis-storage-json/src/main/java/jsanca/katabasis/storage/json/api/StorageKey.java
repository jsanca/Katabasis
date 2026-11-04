package jsanca.katabasis.storage.json.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable logical identity for a stored document.
 *
 * <p>{@code type} is a human-readable logical category that must remain stable across
 * schema versions (e.g. {@code "download-task"}). It must not embed Java class names.
 * {@code id} is the unique identifier within that type.
 *
 * <p>Both components are restricted to {@code [a-zA-Z0-9][a-zA-Z0-9_-]*} to prevent
 * path traversal, injection, or ambiguous encoding in storage backends.
 */
public record StorageKey(String type, String id) {

    private static final Pattern SAFE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");

    public StorageKey {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
        if (!SAFE.matcher(type).matches()) {
            throw new IllegalArgumentException(
                    "StorageKey.type must match [a-zA-Z0-9][a-zA-Z0-9_-]*: " + type);
        }
        if (!SAFE.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "StorageKey.id must match [a-zA-Z0-9][a-zA-Z0-9_-]*: " + id);
        }
    }
}
