package jsanca.katabasis.core.internal.json;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.adapter.JsonbAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class JsonbCompatibilityTest {

    // ── domain types used only in this test ──────────────────────────────────

    public enum TaskStatus { ACTIVE, COMPLETED, FAILED, PAUSED, CANCELLED }

    public record SourceInfo(URI uri, Path destination) {}

    public record TaskSnapshot(
            String id,
            SourceInfo source,
            TaskStatus status,
            long bytesDownloaded,
            long totalBytes,
            Instant startedAt,
            String errorMessage
    ) {}

    public record SnapshotBundle(String version, List<TaskSnapshot> tasks) {}

    // ── adapters ──────────────────────────────────────────────────────────────

    static final class UriAdapter implements JsonbAdapter<URI, String> {
        @Override public String adaptToJson(final URI uri) { return uri.toString(); }
        @Override public URI adaptFromJson(final String s) { return URI.create(s); }
    }

    static final class PathAdapter implements JsonbAdapter<Path, String> {
        @Override public String adaptToJson(final Path p) { return p.toString(); }
        @Override public Path adaptFromJson(final String s) { return Path.of(s); }
    }

    // ── shared Jsonb instance ─────────────────────────────────────────────────

    private static Jsonb jsonb;

    @BeforeAll
    static void setUpJsonb() {
        jsonb = JsonbBuilder.create(new JsonbConfig()
                .withAdapters(new UriAdapter(), new PathAdapter()));
    }

    @AfterAll
    static void tearDownJsonb() throws Exception {
        jsonb.close();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void recordWithPrimitivesUriPathAndInstantRoundTrips() {
        final Instant started = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final TaskSnapshot original = new TaskSnapshot(
                "task-1",
                new SourceInfo(
                        URI.create("http://example.com/file.zip"),
                        Path.of("/tmp/file.zip")),
                TaskStatus.COMPLETED,
                1024L,
                1024L,
                started,
                null
        );

        final String json = jsonb.toJson(original);
        assertNotNull(json, "Serialized JSON must not be null");
        assertTrue(json.contains("task-1"), "JSON must contain the task id");

        final TaskSnapshot restored = jsonb.fromJson(json, TaskSnapshot.class);
        assertEquals(original.id(), restored.id());
        assertEquals(original.source().uri(), restored.source().uri());
        assertEquals(original.source().destination(), restored.source().destination());
        assertEquals(original.status(), restored.status());
        assertEquals(original.bytesDownloaded(), restored.bytesDownloaded());
        assertEquals(original.totalBytes(), restored.totalBytes());
        assertEquals(original.startedAt(), restored.startedAt());
        assertNull(restored.errorMessage(), "null field must remain null after round-trip");
    }

    @Test
    void nullableStringFieldRoundTrips() {
        final TaskSnapshot withError = new TaskSnapshot(
                "task-err",
                new SourceInfo(URI.create("http://example.com/404"), Path.of("/tmp/404")),
                TaskStatus.FAILED,
                0L,
                -1L,
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                "HTTP 404 Not Found"
        );

        final String json = jsonb.toJson(withError);
        final TaskSnapshot restored = jsonb.fromJson(json, TaskSnapshot.class);
        assertEquals("HTTP 404 Not Found", restored.errorMessage());
        assertEquals(TaskStatus.FAILED, restored.status());
    }

    @Test
    void nestedRecordWithImmutableListRoundTrips() {
        final List<TaskSnapshot> tasks = List.of(
                new TaskSnapshot("t1",
                        new SourceInfo(URI.create("http://a.com/x"), Path.of("/x")),
                        TaskStatus.ACTIVE, 500L, 1000L,
                        Instant.now().truncatedTo(ChronoUnit.MILLIS), null),
                new TaskSnapshot("t2",
                        new SourceInfo(URI.create("http://b.com/y"), Path.of("/y")),
                        TaskStatus.PAUSED, 250L, 2000L,
                        Instant.now().truncatedTo(ChronoUnit.MILLIS), null)
        );
        final SnapshotBundle original = new SnapshotBundle("1.0", tasks);

        final String json = jsonb.toJson(original);
        final SnapshotBundle restored = jsonb.fromJson(json, SnapshotBundle.class);

        assertEquals("1.0", restored.version());
        assertEquals(2, restored.tasks().size());
        assertEquals("t1", restored.tasks().get(0).id());
        assertEquals("t2", restored.tasks().get(1).id());
        assertEquals(URI.create("http://a.com/x"), restored.tasks().get(0).source().uri());
        assertEquals(TaskStatus.PAUSED, restored.tasks().get(1).status());
    }

    @Test
    void allEnumValuesRoundTrip() {
        for (final TaskStatus status : TaskStatus.values()) {
            final TaskSnapshot snap = new TaskSnapshot(
                    "id-" + status.name(),
                    new SourceInfo(URI.create("http://x.com"), Path.of("/f")),
                    status, 0L, 0L,
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), null
            );
            final String json = jsonb.toJson(snap);
            final TaskSnapshot restored = jsonb.fromJson(json, TaskSnapshot.class);
            assertEquals(status, restored.status(), "Failed for enum value: " + status);
        }
    }
}
