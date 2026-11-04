package jsanca.katabasis.storage.json.internal.adapter;

import jakarta.json.bind.adapter.JsonbAdapter;

import java.net.URI;

/** Serializes {@link URI} as a plain string; deserializes via {@link URI#create}. */
public final class UriAdapter implements JsonbAdapter<URI, String> {

    @Override
    public String adaptToJson(final URI uri) {
        return uri.toString();
    }

    @Override
    public URI adaptFromJson(final String s) {
        return URI.create(s);
    }
}
