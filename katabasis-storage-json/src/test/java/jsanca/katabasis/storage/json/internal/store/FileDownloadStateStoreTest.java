package jsanca.katabasis.storage.json.internal.store;

import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.model.SegmentSnapshot;
import jsanca.katabasis.storage.json.api.CodecException;
import jsanca.katabasis.storage.json.api.DownloadStateStores;
import jsanca.katabasis.storage.json.api.VersionedCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileDownloadStateStoreTest {

    @TempDir
    Path tempDir;

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static final String ID1 = "task-001";
    private static final String ID2 = "task-002";

    private static DownloadSnapshot snapshot(final String id, final DownloadStatus status) {
        return new DownloadSnapshot(
                id,
                URI.create("http://example.com/" + id),
                Path.of("/tmp/" + id + ".bin"),
                null, null,
                1024L, 512L,
                status,
                List.of(),
                Instant.ofEpochSecond(1000),
                Instant.now()
        );
    }

    private static DownloadSnapshot snapshotWithSegments(
            final String id, final DownloadStatus status) {
        final List<SegmentSnapshot> segs = List.of(
                new SegmentSnapshot(0, 0,   511, 511),
                new SegmentSnapshot(1, 512, 1023, 0)
        );
        return new DownloadSnapshot(
                id,
                URI.create("http://example.com/" + id),
                Path.of("/tmp/" + id + ".bin"),
                Path.of("/tmp/" + id + ".bin.part"),
                "\"etag-abc\"",
                1024L, 511L,
                status,
                segs,
                Instant.ofEpochSecond(1000),
                Instant.now()
        );
    }

    private DownloadStateStore store() {
        return DownloadStateStores.fileStore(tempDir);
    }

    // ── tests: basic save / findById / delete ─────────────────────────────────

    @Test
    void saveAndFindByIdRoundTrips() {
        final DownloadStateStore store = store();
        final DownloadSnapshot original = snapshot(ID1, DownloadStatus.IN_PROGRESS);
        store.save(original);

        final Optional<DownloadSnapshot> found = store.findById(ID1);
        assertTrue(found.isPresent());
        final DownloadSnapshot restored = found.get();

        assertEquals(original.downloadId(),         restored.downloadId());
        assertEquals(original.sourceUri(),          restored.sourceUri());
        assertEquals(original.targetPath(),         restored.targetPath());
        assertEquals(original.status(),             restored.status());
        assertEquals(original.expectedTotalBytes(), restored.expectedTotalBytes());
        assertEquals(original.confirmedBytes(),     restored.confirmedBytes());
    }

    @Test
    void findByIdReturnsEmptyForUnknownId() {
        final Optional<DownloadSnapshot> result = store().findById("does-not-exist");
        assertFalse(result.isPresent());
    }

    @Test
    void deleteRemovesFile() {
        final DownloadStateStore store = store();
        store.save(snapshot(ID1, DownloadStatus.PAUSED));
        assertTrue(store.findById(ID1).isPresent());

        store.delete(ID1);
        assertFalse(store.findById(ID1).isPresent());
        assertFalse(Files.exists(tempDir.resolve(ID1 + ".json")));
    }

    @Test
    void deleteIsIdempotentForMissingId() {
        assertDoesNotThrow(() -> store().delete("never-existed"));
    }

    @Test
    void saveOverwritesPreviousSnapshot() {
        final DownloadStateStore store = store();
        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
        store.save(snapshot(ID1, DownloadStatus.PAUSED));

        final DownloadSnapshot latest = store.findById(ID1).orElseThrow();
        assertEquals(DownloadStatus.PAUSED, latest.status());
    }

    // ── tests: revision increments ────────────────────────────────────────────

    @Test
    void revisionIncrementsOnEachSave() {
        final VersionedCodec<DownloadSnapshot> codec = DownloadStateStores.defaultSnapshotCodec();
        final DownloadStateStore store = DownloadStateStores.fileStore(tempDir, codec);

        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));

        final Path file = tempDir.resolve(ID1 + ".json");
        try {
            final var doc = codec.decodeDocument(Files.readAllBytes(file));
            assertEquals(3L, doc.revision(),
                    "Three saves should produce revision 3 (1-based increment from 0)");
        } catch (final Exception e) {
            fail("Failed to decode stored document: " + e.getMessage());
        }
    }

    // ── tests: recovery listing ───────────────────────────────────────────────

    @Test
    void findRecoverableReturnsInProgressAndPaused() {
        final DownloadStateStore store = store();
        store.save(snapshot("in-progress-1",  DownloadStatus.IN_PROGRESS));
        store.save(snapshot("paused-1",        DownloadStatus.PAUSED));
        store.save(snapshot("completed-1",     DownloadStatus.COMPLETED));
        store.save(snapshot("failed-1",        DownloadStatus.FAILED));
        store.save(snapshot("cancelled-1",     DownloadStatus.CANCELLED));

        final List<DownloadSnapshot> recoverable = store.findRecoverable();
        assertEquals(2, recoverable.size());
        assertTrue(recoverable.stream().allMatch(DownloadSnapshot::isRecoverable));
    }

    @Test
    void findRecoverableReturnsEmptyWhenNoSnapshots() {
        assertTrue(store().findRecoverable().isEmpty());
    }

    // ── tests: segmented snapshot round-trip ──────────────────────────────────

    @Test
    void segmentedSnapshotWithEtagAndPartialPathRoundTrips() {
        final DownloadStateStore store = store();
        final DownloadSnapshot original = snapshotWithSegments(ID1, DownloadStatus.PAUSED);
        store.save(original);

        final DownloadSnapshot restored = store.findById(ID1).orElseThrow();

        assertEquals(original.partialPath(), restored.partialPath());
        assertEquals(original.etag(),        restored.etag());
        assertEquals(2,                      restored.segments().size());
        assertEquals(0,                      restored.segments().get(0).index());
        assertEquals(511L,                   restored.segments().get(0).confirmedBytes());
        assertEquals(1,                      restored.segments().get(1).index());
        assertEquals(0L,                     restored.segments().get(1).confirmedBytes());
    }

    // ── tests: atomic replacement ─────────────────────────────────────────────

    @Test
    void noTmpFileRemainsAfterSuccessfulSave() throws IOException {
        store().save(snapshot(ID1, DownloadStatus.IN_PROGRESS));

        final long tmpCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().endsWith(".tmp"))
                .count();
        assertEquals(0, tmpCount, "No .tmp files should remain after a successful save");
    }

    @Test
    void interruptedWriteSimulation_existingFilePreservedWhenTmpLeftBehind()
            throws IOException {
        final DownloadStateStore store = store();
        final DownloadSnapshot original = snapshot(ID1, DownloadStatus.IN_PROGRESS);
        store.save(original);

        // Simulate an interrupted write: leave a corrupt .tmp file
        final Path tmp = tempDir.resolve(ID1 + ".json.tmp");
        Files.writeString(tmp, "{ CORRUPT", StandardCharsets.UTF_8);

        // A fresh save overwrites the tmp and produces a valid result
        final DownloadSnapshot updated = snapshot(ID1, DownloadStatus.PAUSED);
        store.save(updated);

        assertEquals(DownloadStatus.PAUSED, store.findById(ID1).orElseThrow().status());
        assertFalse(Files.exists(tmp), "tmp file must not remain after save");
    }

    // ── tests: corrupt / incomplete JSON ─────────────────────────────────────

    @Test
    void corruptJsonFileReturnsEmptyFromFindById() throws IOException {
        Files.writeString(tempDir.resolve(ID1 + ".json"), "not valid json at all",
                StandardCharsets.UTF_8);

        final Optional<DownloadSnapshot> result = store().findById(ID1);
        assertFalse(result.isPresent(), "Corrupt file must produce empty Optional");
    }

    @Test
    void corruptFileIsSkippedByFindRecoverable() throws IOException {
        Files.writeString(tempDir.resolve("corrupt.json"), "{ BROKEN",
                StandardCharsets.UTF_8);
        store().save(snapshot(ID1, DownloadStatus.IN_PROGRESS));

        final List<DownloadSnapshot> recoverable = store().findRecoverable();
        assertEquals(1, recoverable.size());
        assertEquals(ID1, recoverable.get(0).downloadId());
    }

    // ── tests: unsupported schema version ────────────────────────────────────

    @Test
    void documentWithFutureSchemaVersionThrowsOnDecode() throws IOException {
        final String futureVersionJson = "{\"envelopeVersion\":\"1\","
                + "\"type\":\"download-snapshot\",\"id\":\"" + ID1 + "\","
                + "\"schemaVersion\":999,\"revision\":0,"
                + "\"payload\":{\"downloadId\":\"" + ID1 + "\","
                + "\"sourceUri\":\"http://x.com\",\"targetPath\":\"/tmp/x\","
                + "\"expectedTotalBytes\":0,\"confirmedBytes\":0,"
                + "\"status\":\"IN_PROGRESS\","
                + "\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"checkpointAt\":\"2026-01-01T00:00:00Z\"}}";
        Files.writeString(tempDir.resolve(ID1 + ".json"), futureVersionJson,
                StandardCharsets.UTF_8);

        // findById returns empty (codec exception is swallowed)
        assertFalse(store().findById(ID1).isPresent());
        // but the raw codec throws CodecException
        assertThrows(CodecException.class,
                () -> DownloadStateStores.defaultSnapshotCodec()
                        .decode(Files.readAllBytes(tempDir.resolve(ID1 + ".json"))));
    }

    // ── tests: concurrent saves ───────────────────────────────────────────────

    @Test
    void concurrentSavesForSameIdProduceValidFinalDocument()
            throws InterruptedException {
        final DownloadStateStore store = store();
        final int threads = 50;
        final CountDownLatch start  = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(threads);
        final AtomicInteger errors  = new AtomicInteger(0);

        try (final ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int i = 0; i < threads; i++) {
                final long bytes = i * 10L;
                pool.submit(() -> {
                    try {
                        start.await();
                        store.save(new DownloadSnapshot(
                                ID1,
                                URI.create("http://x.com"),
                                Path.of("/tmp/x.bin"),
                                null, null,
                                1000L, bytes,
                                DownloadStatus.IN_PROGRESS,
                                List.of(),
                                Instant.ofEpochSecond(0),
                                Instant.now()));
                    } catch (final Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finish.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finish.await(15, TimeUnit.SECONDS));
        }

        assertEquals(0, errors.get(), "No save should throw under concurrent load");
        assertTrue(store.findById(ID1).isPresent(), "Final document must be readable");
    }

    @Test
    void concurrentSavesForDifferentIdsAllPersist() throws InterruptedException {
        final DownloadStateStore store = store();
        final int count = 20;
        final CountDownLatch start  = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(count);

        try (final ExecutorService pool = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                final String id = "task-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        store.save(snapshot(id, DownloadStatus.IN_PROGRESS));
                    } catch (final Exception e) {
                        fail("Unexpected exception: " + e.getMessage());
                    } finally {
                        finish.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finish.await(15, TimeUnit.SECONDS));
        }

        assertEquals(count, store.findRecoverable().size());
    }

    // ── tests: repeated checkpoints ───────────────────────────────────────────

    @Test
    void repeatedCheckpointsIncrementRevisionMonotonically() throws Exception {
        final VersionedCodec<DownloadSnapshot> codec = DownloadStateStores.defaultSnapshotCodec();
        final DownloadStateStore store = DownloadStateStores.fileStore(tempDir, codec);
        final int saves = 10;
        for (int i = 0; i < saves; i++) {
            store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
        }
        final var doc = codec.decodeDocument(Files.readAllBytes(tempDir.resolve(ID1 + ".json")));
        assertEquals(saves, doc.revision());
    }

    // ── tests: filesystem errors ──────────────────────────────────────────────

    @Test
    void saveToNonExistentDirectoryThrows() {
        final Path missing = tempDir.resolve("nonexistent");
        assertThrows(IllegalArgumentException.class,
                () -> DownloadStateStores.fileStore(missing));
    }

    // ── tests: partial file consistency ──────────────────────────────────────

    @Test
    void confirmedBytesNeverExceedExpectedTotalBytes() {
        final DownloadStateStore store = store();
        // confirmedBytes == totalBytes is legal (fully downloaded)
        final DownloadSnapshot snap = new DownloadSnapshot(
                ID1,
                URI.create("http://example.com"),
                Path.of("/tmp/done.bin"),
                null, null,
                1024L, 1024L,   // confirmed == total
                DownloadStatus.COMPLETED,
                List.of(),
                Instant.now(), Instant.now());
        store.save(snap);
        final DownloadSnapshot restored = store.findById(ID1).orElseThrow();
        assertEquals(1024L, restored.confirmedBytes());
        assertEquals(1024L, restored.expectedTotalBytes());
    }

    // ── tests: module boundary ────────────────────────────────────────────────

    @Test
    void storeFactoryReturnsDownloadStateStoreInterface() {
        // The store() return type is DownloadStateStore — no json types leak
        final DownloadStateStore store = DownloadStateStores.fileStore(tempDir);
        assertNotNull(store);
        // We only interact via the interface; concrete type is internal
        assertInstanceOf(DownloadStateStore.class, store);
    }

    @Test
    void multipleDownloadIdsAreIsolated() {
        final DownloadStateStore store = store();
        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
        store.save(snapshot(ID2, DownloadStatus.PAUSED));

        store.delete(ID1);

        assertFalse(store.findById(ID1).isPresent(), "ID1 must be deleted");
        assertTrue(store.findById(ID2).isPresent(),  "ID2 must be unaffected");
    }

    // ── tests: path traversal validation ─────────────────────────────────────

    @Test
    void findByIdRejectsPathTraversalId() {
        assertThrows(IllegalArgumentException.class,
                () -> store().findById("../evil"));
    }

    @Test
    void deleteRejectsPathTraversalId() {
        assertThrows(IllegalArgumentException.class,
                () -> store().delete("../../etc"));
    }

    @Test
    void findByIdRejectsIdWithSlash() {
        assertThrows(IllegalArgumentException.class,
                () -> store().findById("a/b"));
    }

    // ── tests: save/delete concurrency (STOR-001 regression) ─────────────────

    @Test
    void concurrentSaveAndDeleteDoNotRaceUnderDifferentLocks() throws InterruptedException, IOException {
        final DownloadStateStore store = store();
        final int iterations = 200;
        final java.util.concurrent.CountDownLatch start  = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch finish = new java.util.concurrent.CountDownLatch(iterations * 2);
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();

        try (final java.util.concurrent.ExecutorService pool =
                     java.util.concurrent.Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < iterations; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        store.save(snapshot(ID1, DownloadStatus.IN_PROGRESS));
                    } catch (final Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finish.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        store.delete(ID1);
                    } catch (final Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        finish.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(finish.await(15, TimeUnit.SECONDS));
        }

        assertEquals(0, errors.get(), "No exception must occur under concurrent save+delete");
        // No leftover .tmp files
        final long tmpCount;
        try (final var stream = Files.list(tempDir)) {
            tmpCount = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
        assertEquals(0, tmpCount, "No orphaned .tmp files must remain");
    }

    // ── tests: orphaned tmp cleanup at startup ────────────────────────────────

    @Test
    void constructorCleansUpOrphanedTmpFiles() throws IOException {
        // Pre-place an orphaned .tmp file
        final Path orphan = tempDir.resolve("old-task.json.tmp");
        Files.writeString(orphan, "partial data", java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(Files.exists(orphan));

        // Creating a fresh store should clean it up
        store(); // constructor runs cleanup
        assertFalse(Files.exists(orphan), "Orphaned .tmp must be deleted by constructor");
    }
}
