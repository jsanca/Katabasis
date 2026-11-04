package jsanca.katabasis.storage.json.internal.adapter;

import jakarta.json.bind.adapter.JsonbAdapter;

import java.nio.file.Path;

/** Serializes {@link Path} as a platform string; deserializes via {@link Path#of}. */
public final class PathAdapter implements JsonbAdapter<Path, String> {

    @Override
    public String adaptToJson(final Path p) {
        return p.toString();
    }

    @Override
    public Path adaptFromJson(final String s) {
        return Path.of(s);
    }
}
