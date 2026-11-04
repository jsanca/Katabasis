package jsanca.katabasis.storage.json.internal.store;

import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.storage.json.api.CodecException;
import jsanca.katabasis.storage.json.api.StorageDocument;
import jsanca.katabasis.storage.json.api.StorageKey;
import jsanca.katabasis.storage.json.api.VersionedCodec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Filesystem-backed {@link DownloadStateStore}.
 *
 * <h2>File layout</h2>
 * Each snapshot is stored as {@code <directory>/<downloadId>.json}. The file
 * contains a versioned JSON envelope produced by the supplied {@link VersionedCodec}.
 * Temporary files use the {@code .json.tmp} suffix and are removed or renamed
 * atomically on each write.
 *
 * <h2>Concurrency model</h2>
 * Per-download {@link ReentrantLock}s serialize concurrent saves and deletes for
 * the same download ID. Operations on different IDs proceed concurrently without
 * contention. Read operations ({@link #findById}, {@link #findRecoverable}) do not
 * acquire locks and may observe a snapshot that is concurrently being replaced;
 * this is acceptable because readers tolerate decoding failures gracefully.
 * Locks are retained in the lock map for the lifetime of the store instance; they
 * are never removed. The map grows at most proportionally to the number of distinct
 * download IDs ever processed, which is memory-bounded in practice.
 *
 * <h2>Multi-process sharing</h2>
 * <strong>Not supported.</strong> Multiple JVM processes writing to the same directory
 * will race without coordination. Use separate directories per process, or add an
 * external file lock if multi-process sharing is required.
 *
 * <h2>Atomic write</h2>
 * Writes go to a {@code .tmp} file first, then are moved to the target. The move
 * uses {@link StandardCopyOption#ATOMIC_MOVE} when the filesystem supports it; if
 * not, a non-atomic replace is performed and a warning is logged. Partial writes
 * to the final file are therefore impossible: the target is either the previous
 * complete document or the new complete document.
 *
 * <h2>Durability ordering</h2>
 * <ol>
 *   <li>Download bytes are written and flushed to the partial file by the HTTP strategy.</li>
 *   <li>The checkpoint coordinator calls {@link #save} with the confirmed byte count.</li>
 *   <li>{@link #save} atomically replaces the manifest with the new snapshot.</li>
 *   <li>The revision is incremented on each successful write.</li>
 * </ol>
 * Note: {@code fsync} on the directory entry is not performed. Journaling filesystems
 * (ext4, APFS) preserve rename atomicity. On non-journaling filesystems a power failure
 * during the rename may lose the most recent checkpoint but will not corrupt the previous
 * checkpoint.
 */
public final class FileDownloadStateStore implements DownloadStateStore {

    private static final Logger  log       = Logger.getLogger(FileDownloadStateStore.class.getName());
    private static final String  EXTENSION     = ".json";
    private static final String  TMP_EXTENSION = ".tmp";
    private static final Pattern SAFE_ID       = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");
    static final String STORAGE_TYPE = "download-snapshot";

    private final Path directory;
    private final VersionedCodec<DownloadSnapshot> codec;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * @param directory the directory where snapshot files are stored; must exist and be writable
     * @param codec     the codec used to encode/decode snapshots
     */
    public FileDownloadStateStore(final Path directory, final VersionedCodec<DownloadSnapshot> codec) {
        this.directory = Objects.requireNonNull(directory, "directory must not be null");
        this.codec     = Objects.requireNonNull(codec,     "codec must not be null");
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Not a directory: " + directory);
        }
        if (!Files.isWritable(directory)) {
            throw new IllegalArgumentException("Directory is not writable: " + directory);
        }
        cleanupOrphanedTmpFiles();
    }

    // ── DownloadStateStore ────────────────────────────────────────────────────

    @Override
    public void save(final DownloadSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        final String id   = snapshot.downloadId();
        final ReentrantLock lock = lockFor(id);
        lock.lock();
        try {
            final StorageKey key  = storageKey(id);
            final Path        target = resolve(id);
            final long        revision = currentRevision(target);
            final byte[]      bytes = codec.encode(key, snapshot, revision + 1);
            atomicWrite(target, bytes);
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to save snapshot for: " + id, e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<DownloadSnapshot> findById(final String downloadId) {
        Objects.requireNonNull(downloadId, "downloadId must not be null");
        final Path file = resolve(downloadId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(codec.decode(Files.readAllBytes(file)));
        } catch (final CodecException | IOException e) {
            log.warning("Could not decode snapshot for " + downloadId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<DownloadSnapshot> findRecoverable() {
        final List<DownloadSnapshot> result = new ArrayList<>();
        try (final var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                  .forEach(file -> {
                      try {
                          final DownloadSnapshot snap = codec.decode(Files.readAllBytes(file));
                          if (snap.isRecoverable()) {
                              result.add(snap);
                          }
                      } catch (final CodecException | IOException e) {
                          log.warning("Skipping unreadable snapshot file " + file + ": " + e.getMessage());
                      }
                  });
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to list snapshot directory: " + directory, e);
        }
        return List.copyOf(result);
    }

    @Override
    public void delete(final String downloadId) {
        Objects.requireNonNull(downloadId, "downloadId must not be null");
        final ReentrantLock lock = lockFor(downloadId);
        lock.lock();
        try {
            Files.deleteIfExists(resolve(downloadId));
        } catch (final IOException e) {
            throw new UncheckedIOException("Failed to delete snapshot for: " + downloadId, e);
        } finally {
            lock.unlock();
        }
    }

    // ── internal ─────────────────────────────────────────────────────────────

    private ReentrantLock lockFor(final String id) {
        return locks.computeIfAbsent(id, k -> new ReentrantLock());
    }

    private Path resolve(final String downloadId) {
        if (!SAFE_ID.matcher(downloadId).matches()) {
            throw new IllegalArgumentException(
                    "downloadId must match [a-zA-Z0-9][a-zA-Z0-9_-]*: " + downloadId);
        }
        return directory.resolve(downloadId + EXTENSION);
    }

    private static StorageKey storageKey(final String downloadId) {
        return new StorageKey(STORAGE_TYPE, downloadId);
    }

    /**
     * Returns the revision of the currently stored document, or {@code 0} if no document
     * exists yet. If the file exists but cannot be decoded (e.g. it is corrupt or temporarily
     * unreadable), throws {@link UncheckedIOException} rather than silently resetting to 0,
     * which would break revision monotonicity.
     *
     * <p>{@link #save} will propagate this exception so that the caller is aware a corrupt
     * manifest is present and no silent data loss occurs.
     */
    private long currentRevision(final Path file) {
        if (!Files.exists(file)) {
            return 0L;
        }
        try {
            final StorageDocument<DownloadSnapshot> doc =
                    codec.decodeDocument(Files.readAllBytes(file));
            return doc.revision();
        } catch (final CodecException | IOException e) {
            log.warning("Could not read revision from " + file
                    + "; treating as corrupt — will not reset to 0: " + e.getMessage());
            throw new UncheckedIOException(
                    "Cannot determine revision for " + file + "; refusing to overwrite",
                    new IOException(e.getMessage(), e));
        }
    }

    /**
     * Writes {@code bytes} to a {@code .tmp} file adjacent to {@code target}, then
     * renames it to {@code target}.
     *
     * <p>Attempts an atomic rename first. If the filesystem does not support atomic
     * move, falls back to a non-atomic replace and logs a warning. The fallback is
     * explicit and logged — it is not a silent degradation.
     *
     * <p>If any {@link IOException} occurs during the write or rename, the {@code .tmp}
     * file is deleted before rethrowing, preventing orphaned partial files from accumulating.
     */
    private void atomicWrite(final Path target, final byte[] bytes) throws IOException {
        final Path tmp = target.getParent().resolve(target.getFileName() + TMP_EXTENSION);
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                log.warning("ATOMIC_MOVE not supported on this filesystem ("
                        + e.getMessage() + "); falling back to non-atomic replace for: " + target);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /**
     * Scans the storage directory at startup and removes any orphaned {@code .json.tmp}
     * files left by a previous JVM process that crashed mid-write.
     */
    private void cleanupOrphanedTmpFiles() {
        try (final var stream = Files.list(directory)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION + TMP_EXTENSION))
                  .forEach(tmp -> {
                      try {
                          Files.deleteIfExists(tmp);
                          log.info("Removed orphaned tmp file: " + tmp);
                      } catch (final IOException e) {
                          log.warning("Could not remove orphaned tmp file " + tmp + ": " + e.getMessage());
                      }
                  });
        } catch (final IOException e) {
            log.warning("Could not scan for orphaned tmp files in " + directory + ": " + e.getMessage());
        }
    }
}
