package jsanca.katabasis.storage.json.api;

import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.storage.json.internal.adapter.PathAdapter;
import jsanca.katabasis.storage.json.internal.adapter.UriAdapter;
import jsanca.katabasis.storage.json.internal.codec.JsonEnvelopeCodec;
import jsanca.katabasis.storage.json.internal.store.FileDownloadStateStore;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Factory for {@link DownloadStateStore} implementations.
 *
 * <p>No JSON or filesystem types appear in the returned interface. Implementations
 * are hidden behind the port; concrete types never leak into callers.
 */
public final class DownloadStateStores {

    private DownloadStateStores() {}

    /**
     * Returns a filesystem-backed store that writes one {@code .json} file per
     * download in {@code directory}, using the default versioned JSON codec.
     *
     * <p>The directory must exist and be writable. Multi-process sharing of a single
     * directory is not supported.
     *
     * @param directory writable directory for snapshot files
     * @return a thread-safe {@link DownloadStateStore}
     */
    public static DownloadStateStore fileStore(final Path directory) {
        return fileStore(directory, defaultSnapshotCodec());
    }

    /**
     * Returns a filesystem-backed store with a custom codec. Use this overload to
     * inject a codec with registered migrations.
     *
     * @param directory writable directory for snapshot files
     * @param codec     the versioned codec to use for serialization
     * @return a thread-safe {@link DownloadStateStore}
     */
    public static DownloadStateStore fileStore(
            final Path directory,
            final VersionedCodec<DownloadSnapshot> codec) {
        Objects.requireNonNull(directory, "directory must not be null");
        Objects.requireNonNull(codec,     "codec must not be null");
        return new FileDownloadStateStore(directory, codec);
    }

    /**
     * Returns the default {@link VersionedCodec} for {@link DownloadSnapshot}
     * at schema version 1, with URI and Path adapters registered.
     */
    public static VersionedCodec<DownloadSnapshot> defaultSnapshotCodec() {
        return JsonEnvelopeCodec.builder(DownloadSnapshot.class)
                .schemaVersion(1)
                .adapter(new UriAdapter())
                .adapter(new PathAdapter())
                .build();
    }
}
