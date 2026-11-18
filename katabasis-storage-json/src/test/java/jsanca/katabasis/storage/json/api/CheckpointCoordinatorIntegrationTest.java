package jsanca.katabasis.storage.json.api;

import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCheckpointFailedEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadErrorCode;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadPausedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import jsanca.katabasis.core.api.manager.CheckpointSource;
import jsanca.katabasis.core.api.manager.CheckpointSourceProvider;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadControlAction;
import jsanca.katabasis.core.api.model.DownloadControlResult;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;
import jsanca.katabasis.core.api.model.SegmentSnapshot;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CheckpointCoordinator with CheckpointSourceProvider.
 *
 * <p>All 16 tests are deterministic and use in-memory stubs — no network, no disk I/O,
 * no real HTTP server.
 */
class CheckpointCoordinatorIntegrationTest {

    private static final String DL_ID   = "dl-t10-001";
    private static final URI    SOURCE  = URI.create("http://example.com/file.bin");
    private static final Path   DEST    = Path.of("/tmp/file.bin");
    private static final DownloadInfo INFO = new DownloadInfo(DL_ID, SOURCE, DEST);

    // ── stubs ─────────────────────────────────────────────────────────────────

    static final class CapturingStateStore implements DownloadStateStore {
        final List<DownloadSnapshot> saved   = new CopyOnWriteArrayList<>();
        final List<String>           deleted = new CopyOnWriteArrayList<>();
        volatile boolean throwOnSave = false;

        @Override public void save(final DownloadSnapshot s) {
            if (throwOnSave) { throw new RuntimeException("simulated save failure"); }
            saved.add(s);
        }
        @Override public Optional<DownloadSnapshot> findById(final String id) { return Optional.empty(); }
        @Override public List<DownloadSnapshot> findRecoverable() { return List.of(); }
        @Override public void delete(final String id) { deleted.add(id); }

        DownloadSnapshot lastSaved() { return saved.get(saved.size() - 1); }
    }

    static final class StubDownloadManager implements DownloadManager {
        final List<Consumer<DownloadEvent>> listeners = new ArrayList<>();

        @Override public DownloadSubmissionResult download(final DownloadRequest r) { throw new UnsupportedOperationException(); }
        @Override public DownloadControlResult cancel(final String id) { throw new UnsupportedOperationException(); }
        @Override public DownloadControlResult pause(final String id) { throw new UnsupportedOperationException(); }
        @Override public DownloadControlResult resume(final String id) { throw new UnsupportedOperationException(); }
        @Override public void addListener(final Consumer<DownloadEvent> l) { listeners.add(l); }
        @Override public void removeListener(final Consumer<DownloadEvent> l) { listeners.remove(l); }
        @Override public void close() {}

        void publish(final DownloadEvent event) {
            listeners.forEach(l -> l.accept(event));
        }
    }

    static final class StubCheckpointSourceProvider implements CheckpointSourceProvider {
        private volatile CheckpointSource source;

        void registerSource(final CheckpointSource s) { this.source = s; }
        void clearSource() { this.source = null; }

        @Override
        public Optional<CheckpointSource> findSource(final String downloadId) {
            return Optional.ofNullable(source);
        }
    }

    /** Builds a minimal CheckpointSource returning a fixed snapshot. */
    private static CheckpointSource fixedSource(final long confirmedBytes,
                                                  final String etag,
                                                  final long expectedTotal) {
        return at -> new DownloadSnapshot(
                DL_ID, SOURCE, DEST, null, etag, expectedTotal,
                confirmedBytes, DownloadStatus.IN_PROGRESS,
                List.of(), Instant.now().minusSeconds(60), at);
    }

    /** Builds a source that returns a snapshot with two segments. */
    private static CheckpointSource segmentedSource(final long seg0Confirmed,
                                                      final long seg1Confirmed,
                                                      final String etag) {
        return at -> {
            final List<SegmentSnapshot> segs = List.of(
                    new SegmentSnapshot(0, 0L,    511L, seg0Confirmed),
                    new SegmentSnapshot(1, 512L, 1023L, seg1Confirmed));
            return new DownloadSnapshot(
                    DL_ID, SOURCE, DEST, null, etag, 1024L,
                    seg0Confirmed + seg1Confirmed, DownloadStatus.IN_PROGRESS,
                    segs, Instant.now().minusSeconds(60), at);
        };
    }

    // ── 1 ─────────────────────────────────────────────────────────────────────

    @Test
    void createWithSourceProviderRegistersListenerOnManager() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofSeconds(60), event -> {})) {
            assertEquals(1, manager.listeners.size());
        }
    }

    // ── 2 ─────────────────────────────────────────────────────────────────────

    @Test
    void createWithSourceProviderDeregistersListenerOnClose() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofSeconds(60), event -> {});
        coord.close();

        assertEquals(0, manager.listeners.size());
    }

    // ── 3 ─────────────────────────────────────────────────────────────────────

    @Test
    void flushWithSourceProviderCallsBuildSnapshot() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(fixedSource(512L, null, 1024L));

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {});

        // Start a download so pending map has an entry
        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 100L, 1024L, Instant.now()));

        coord.close(); // triggers final flush

        assertFalse(store.saved.isEmpty(), "Flush must have persisted at least one snapshot");
        // The source returns confirmedBytes=512, not 100 from the progress event
        assertEquals(512L, store.lastSaved().confirmedBytes(),
                "Flush must use source-provided confirmedBytes");
    }

    // ── 4 ─────────────────────────────────────────────────────────────────────

    @Test
    void flushWithSourceProviderUpdatesPendingEntry() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(fixedSource(800L, "\"etag-abc\"", 1024L));

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 200L, 1024L, Instant.now()));

        coord.close();

        final DownloadSnapshot saved = store.lastSaved();
        assertEquals("\"etag-abc\"", saved.etag(),
                "Pending entry updated with source data should carry the ETag through to flush");
    }

    // ── 5 ─────────────────────────────────────────────────────────────────────

    @Test
    void flushWithoutSourceFallsBackToEventData() {
        final StubDownloadManager manager = new StubDownloadManager();
        // Provider never returns a source
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 300L, 1024L, Instant.now()));

        coord.close();

        assertFalse(store.saved.isEmpty());
        assertEquals(300L, store.lastSaved().confirmedBytes(),
                "Without a source, event-derived confirmedBytes must be used");
    }

    // ── 6 ─────────────────────────────────────────────────────────────────────

    @Test
    void pauseEventWithSourceProviderSavesRichSnapshot() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(segmentedSource(400L, 350L, "\"etag-xyz\""));

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {})) {
            manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
            manager.publish(new DownloadPausedEvent(INFO, 700L, 1024L, Instant.now()));
        }

        assertTrue(store.saved.stream().anyMatch(s -> s.status() == DownloadStatus.PAUSED),
                "A PAUSED snapshot must be saved on pause event");
        final DownloadSnapshot paused = store.saved.stream()
                .filter(s -> s.status() == DownloadStatus.PAUSED)
                .findFirst().orElseThrow();
        assertEquals(2, paused.segments().size(), "Segmented source must include segment data");
        assertEquals("\"etag-xyz\"", paused.etag(), "ETag from source must be carried through");
        // confirmedBytes from source (750) overrides event confirmedBytes (700)
        assertEquals(750L, paused.confirmedBytes());
    }

    // ── 7 ─────────────────────────────────────────────────────────────────────

    @Test
    void pauseEventWithoutSourceFallsBackToEventData() {
        final StubDownloadManager manager = new StubDownloadManager();
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60), event -> {})) {
            manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
            manager.publish(new DownloadPausedEvent(INFO, 500L, 1024L, Instant.now()));
        }

        assertTrue(store.saved.stream().anyMatch(s -> s.status() == DownloadStatus.PAUSED));
        final DownloadSnapshot paused = store.saved.stream()
                .filter(s -> s.status() == DownloadStatus.PAUSED)
                .findFirst().orElseThrow();
        assertEquals(500L, paused.confirmedBytes());
        assertTrue(paused.segments().isEmpty());
    }

    // ── 8 ─────────────────────────────────────────────────────────────────────

    @Test
    void failedEventWithSourceProviderSavesRichSnapshot() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(fixedSource(900L, "\"etag-fail\"", 1024L));

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {})) {
            manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
            manager.publish(new DownloadProgressEvent(INFO, 100L, 1024L, Instant.now()));
            manager.publish(new DownloadFailedEvent(
                    INFO, "timeout", null, Duration.ofSeconds(5), Instant.now(),
                    DownloadErrorCode.UNKNOWN));
        }

        assertTrue(store.saved.stream().anyMatch(s -> s.status() == DownloadStatus.FAILED));
        final DownloadSnapshot failed = store.saved.stream()
                .filter(s -> s.status() == DownloadStatus.FAILED)
                .findFirst().orElseThrow();
        assertEquals(900L, failed.confirmedBytes(), "Failed snapshot must use source-provided bytes");
        assertEquals("\"etag-fail\"", failed.etag());
    }

    // ── 9 ─────────────────────────────────────────────────────────────────────

    @Test
    void failedEventWithoutSourceFallsBackToExistingPending() {
        final StubDownloadManager manager = new StubDownloadManager();
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60), event -> {})) {
            manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
            manager.publish(new DownloadProgressEvent(INFO, 650L, 1024L, Instant.now()));
            manager.publish(new DownloadFailedEvent(
                    INFO, "io error", null, Duration.ofSeconds(2), Instant.now(),
                    DownloadErrorCode.IO_ERROR));
        }

        assertTrue(store.saved.stream().anyMatch(s -> s.status() == DownloadStatus.FAILED));
        final DownloadSnapshot failed = store.saved.stream()
                .filter(s -> s.status() == DownloadStatus.FAILED)
                .findFirst().orElseThrow();
        // Falls back to existing pending which had confirmedBytes=650 from progress event
        assertEquals(650L, failed.confirmedBytes());
    }

    // ── 10 ────────────────────────────────────────────────────────────────────

    @Test
    void saveFailureEmitsCheckpointFailedEvent() {
        final StubDownloadManager manager = new StubDownloadManager();
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();
        store.throwOnSave = true;

        final List<DownloadEvent> failures = new CopyOnWriteArrayList<>();

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60), failures::add);

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadPausedEvent(INFO, 100L, 1024L, Instant.now()));
        coord.close();

        assertFalse(failures.isEmpty(), "Save failures must emit DownloadCheckpointFailedEvent");
        assertTrue(failures.stream().allMatch(e -> e instanceof DownloadCheckpointFailedEvent));
    }

    // ── 11 ────────────────────────────────────────────────────────────────────

    @Test
    void checkpointFailedEventContainsCorrectDownloadInfo() {
        final StubDownloadManager manager = new StubDownloadManager();
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();
        store.throwOnSave = true;

        final List<DownloadCheckpointFailedEvent> failures = new CopyOnWriteArrayList<>();

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60),
                event -> {
                    if (event instanceof DownloadCheckpointFailedEvent e) { failures.add(e); }
                });

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadPausedEvent(INFO, 200L, 1024L, Instant.now()));
        coord.close();

        assertFalse(failures.isEmpty());
        final DownloadCheckpointFailedEvent failure = failures.get(0);
        assertEquals(DL_ID, failure.info().downloadId());
        assertEquals(SOURCE, failure.info().sourceUri());
        assertNotNull(failure.occurredAt());
        assertNotNull(failure.errorMessage());
    }

    // ── 12 ────────────────────────────────────────────────────────────────────

    @Test
    void multipleActiveDownloadsAllFlushedOnClose() {
        final String id2 = "dl-t10-002";
        final DownloadInfo info2 = new DownloadInfo(id2, SOURCE,
                Path.of("/tmp/file2.bin"));

        final StubDownloadManager manager = new StubDownloadManager();
        // No source registered — both downloads use event-derived fallback
        final CheckpointSourceProvider emptyProvider = id -> Optional.empty();
        final CapturingStateStore store = new CapturingStateStore();

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, emptyProvider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO,  Instant.now()));
        manager.publish(new DownloadStartedEvent(info2, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO,  50L, 500L, Instant.now()));
        manager.publish(new DownloadProgressEvent(info2, 50L, 500L, Instant.now()));

        coord.close();

        final long dlId1Saves = store.saved.stream()
                .filter(s -> s.downloadId().equals(DL_ID)).count();
        final long dlId2Saves = store.saved.stream()
                .filter(s -> s.downloadId().equals(id2)).count();
        assertTrue(dlId1Saves >= 1, "First download must be flushed on close");
        assertTrue(dlId2Saves >= 1, "Second download must be flushed on close");
    }

    // ── 13 ────────────────────────────────────────────────────────────────────

    @Test
    void sourceProviderReturnsEmptyMidFlushHandledGracefully() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        // No source registered — provider always returns empty
        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 123L, 1024L, Instant.now()));

        // Should not throw even though source is absent
        assertDoesNotThrow(coord::close);
        assertFalse(store.saved.isEmpty(), "Event-derived fallback must still produce a save");
    }

    // ── 14 ────────────────────────────────────────────────────────────────────

    @Test
    void richSnapshotFromSourceIncludesSegmentData() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(segmentedSource(256L, 256L, null));

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 400L, 1024L, Instant.now()));
        coord.close();

        assertFalse(store.saved.isEmpty());
        final DownloadSnapshot snap = store.lastSaved();
        assertEquals(2, snap.segments().size(), "Source-provided segments must appear in saved snapshot");
        assertEquals(0, snap.segments().get(0).index());
        assertEquals(1, snap.segments().get(1).index());
        assertEquals(256L, snap.segments().get(0).confirmedBytes());
        assertEquals(256L, snap.segments().get(1).confirmedBytes());
        assertEquals(512L, snap.confirmedBytes(), "confirmedBytes must equal sum of segment bytes");
    }

    // ── 15 ────────────────────────────────────────────────────────────────────

    @Test
    void richSnapshotFromSourceIncludesETag() {
        final StubDownloadManager manager = new StubDownloadManager();
        final StubCheckpointSourceProvider provider = new StubCheckpointSourceProvider();
        final CapturingStateStore store = new CapturingStateStore();

        provider.registerSource(fixedSource(600L, "\"abc123\"", 1024L));

        final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, provider, store, Duration.ofMinutes(60), event -> {});

        manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
        manager.publish(new DownloadProgressEvent(INFO, 100L, 1024L, Instant.now()));
        coord.close();

        assertFalse(store.saved.isEmpty());
        assertEquals("\"abc123\"", store.lastSaved().etag(),
                "ETag from source must be persisted in the snapshot");
    }

    // ── 16 ────────────────────────────────────────────────────────────────────

    @Test
    void legacyCreateWithoutSourceProviderStillWorks() {
        // The 3-arg create() must still compile and behave correctly.
        final StubDownloadManager manager = new StubDownloadManager();
        final CapturingStateStore store = new CapturingStateStore();

        try (final CheckpointCoordinator coord = CheckpointCoordinator.create(
                manager, store, Duration.ofSeconds(60))) {
            assertEquals(1, manager.listeners.size());
            manager.publish(new DownloadStartedEvent(INFO, Instant.now()));
            manager.publish(new DownloadProgressEvent(INFO, 200L, 1024L, Instant.now()));
        }

        assertFalse(store.saved.isEmpty(), "Legacy API must still persist snapshots");
        assertEquals(200L, store.lastSaved().confirmedBytes());
    }
}
