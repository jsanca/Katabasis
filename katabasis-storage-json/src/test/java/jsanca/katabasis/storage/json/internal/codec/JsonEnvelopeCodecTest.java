package jsanca.katabasis.storage.json.internal.codec;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jsanca.katabasis.storage.json.api.CodecException;
import jsanca.katabasis.storage.json.api.StorageDocument;
import jsanca.katabasis.storage.json.api.StorageKey;
import jsanca.katabasis.storage.json.api.VersionedCodec;
import jsanca.katabasis.storage.json.internal.adapter.PathAdapter;
import jsanca.katabasis.storage.json.internal.adapter.UriAdapter;
import jsanca.katabasis.storage.json.internal.migration.Migration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonEnvelopeCodecTest {

    // ── domain types used in this test ────────────────────────────────────────

    public enum WidgetStatus { ACTIVE, ARCHIVED }

    /** Current (v2) domain model. */
    public record Widget(String name, WidgetStatus status, int count) {}

    /** Domain model with URI, Path, Instant — covers the full Katabasis snapshot shape. */
    public record SnapshotLike(
            String id,
            URI sourceUri,
            Path targetPath,
            Instant capturedAt,
            String note
    ) {}

    // ── codec factories ───────────────────────────────────────────────────────

    private static VersionedCodec<Widget> widgetCodecV2() {
        return JsonEnvelopeCodec.builder(Widget.class)
                .schemaVersion(2)
                .addMigration(new WidgetV1ToV2())
                .build();
    }

    private static VersionedCodec<Widget> widgetCodecV1() {
        return JsonEnvelopeCodec.builder(Widget.class)
                .schemaVersion(1)
                .build();
    }

    private static VersionedCodec<SnapshotLike> snapshotCodec() {
        return JsonEnvelopeCodec.builder(SnapshotLike.class)
                .schemaVersion(1)
                .adapter(new UriAdapter())
                .adapter(new PathAdapter())
                .build();
    }

    // ── migration: Widget v1 → v2 (adds 'count' defaulting to 0) ─────────────

    static final class WidgetV1ToV2 implements Migration {
        @Override
        public int fromVersion() { return 1; }

        @Override
        public JsonObject migrate(final JsonObject payload) {
            final JsonObjectBuilder builder = Json.createObjectBuilder();
            payload.forEach(builder::add);
            if (!payload.containsKey("count")) {
                builder.add("count", 0);
            }
            return builder.build();
        }
    }

    // ── StorageKey helper ─────────────────────────────────────────────────────

    private static final StorageKey WIDGET_KEY = new StorageKey("widget", "w1");
    private static final StorageKey SNAP_KEY   = new StorageKey("snapshot", "s1");

    // ── tests: round-trip ─────────────────────────────────────────────────────

    @Test
    void recordRoundTripsWithCurrentSchemaVersion() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        final Widget original = new Widget("sprocket", WidgetStatus.ACTIVE, 42);

        final byte[] bytes = codec.encode(WIDGET_KEY, original);
        final Widget restored = codec.decode(bytes);

        assertEquals(original.name(),   restored.name());
        assertEquals(original.status(), restored.status());
        assertEquals(original.count(),  restored.count());
    }

    @Test
    void envelopeContainsExpectedFields() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        final byte[] bytes = codec.encode(WIDGET_KEY, new Widget("cog", WidgetStatus.ARCHIVED, 1));

        final String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"envelopeVersion\""), "envelope version field");
        assertTrue(json.contains("\"widget\""),          "type field");
        assertTrue(json.contains("\"w1\""),              "id field");
        assertTrue(json.contains("\"schemaVersion\""),   "schema version field");
        assertTrue(json.contains("\"revision\""),        "revision field");
        assertTrue(json.contains("\"payload\""),         "payload field");
    }

    @Test
    void decodeDocumentExposesEnvelopeMetadata() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        final byte[] bytes = codec.encode(WIDGET_KEY, new Widget("gear", WidgetStatus.ACTIVE, 7), 5L);

        final StorageDocument<Widget> doc = codec.decodeDocument(bytes);

        assertEquals(WIDGET_KEY, doc.key());
        assertEquals(2,          doc.schemaVersion());
        assertEquals(5L,         doc.revision());
        assertEquals("gear",     doc.payload().name());
    }

    @Test
    void revisionZeroIsDefaultForNewDocuments() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        final byte[] bytes = codec.encode(WIDGET_KEY, new Widget("bolt", WidgetStatus.ACTIVE, 0));

        final StorageDocument<Widget> doc = codec.decodeDocument(bytes);
        assertEquals(0L, doc.revision());
    }

    @Test
    void explicitRevisionIsPreserved() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        final byte[] bytes = codec.encode(WIDGET_KEY, new Widget("bolt", WidgetStatus.ACTIVE, 0), 99L);

        final StorageDocument<Widget> doc = codec.decodeDocument(bytes);
        assertEquals(99L, doc.revision());
    }

    // ── tests: nested records + adapters ─────────────────────────────────────

    @Test
    void recordWithUriPathAndInstantRoundTrips() {
        final VersionedCodec<SnapshotLike> codec = snapshotCodec();
        final Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        final SnapshotLike original = new SnapshotLike(
                "snap-1",
                URI.create("http://example.com/data.zip"),
                Path.of("/tmp/data.zip"),
                now,
                null
        );

        final SnapshotLike restored = codec.decode(codec.encode(SNAP_KEY, original));

        assertEquals(original.id(),         restored.id());
        assertEquals(original.sourceUri(),  restored.sourceUri());
        assertEquals(original.targetPath(), restored.targetPath());
        assertEquals(original.capturedAt(), restored.capturedAt());
        assertNull(restored.note(),         "null field must survive round-trip");
    }

    @Test
    void allEnumValuesRoundTrip() {
        final VersionedCodec<Widget> codec = widgetCodecV1();
        for (final WidgetStatus status : WidgetStatus.values()) {
            final Widget w = new Widget("x", status, 0);
            final Widget restored = codec.decode(codec.encode(WIDGET_KEY, w));
            assertEquals(status, restored.status());
        }
    }

    // ── tests: migration ──────────────────────────────────────────────────────

    @Test
    void v1DocumentIsMigratedToV2OnDecode() {
        // Craft a true v1 document: envelope declares schemaVersion=1, payload has no 'count'
        final byte[] v1Bytes = makeV1WidgetDocument("w1", "spring", "ACTIVE");

        final Widget restored = widgetCodecV2().decode(v1Bytes);
        assertEquals("spring",            restored.name());
        assertEquals(WidgetStatus.ACTIVE, restored.status());
        assertEquals(0,                   restored.count(), "migration must default count to 0");
    }

    @Test
    void migratedDocumentSchemaVersionIsCurrentVersion() {
        final byte[] v1Bytes = makeV1WidgetDocument("w1", "x", "ACTIVE");

        final StorageDocument<Widget> doc = widgetCodecV2().decodeDocument(v1Bytes);
        assertEquals(2, doc.schemaVersion(),
                "decoded document must report current schema version after migration");
    }

    // ── tests: schema validation failures ────────────────────────────────────

    @Test
    void malformedJsonThrowsCodecException() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        assertThrows(CodecException.class,
                () -> codec.decode("not json at all".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void missingEnvelopeFieldThrowsCodecException() {
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":2,\"revision\":0}"; // missing 'payload'
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void wrongEnvelopeVersionThrowsCodecException() {
        final String json = "{\"envelopeVersion\":\"99\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":2,\"revision\":0,\"payload\":{}}";
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void payloadNotAnObjectThrowsCodecException() {
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":2,\"revision\":0,\"payload\":\"not-an-object\"}";
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void schemaVersionZeroThrowsCodecException() {
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":0,\"revision\":0,\"payload\":{}}";
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void negativeRevisionThrowsCodecException() {
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":1,\"revision\":-1,\"payload\":{}}";
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    // ── tests: unknown / unsupported version ──────────────────────────────────

    @Test
    void documentNewerThanCodecThrowsCodecException() {
        // Craft a document claiming schemaVersion=99 — codec only knows up to v2
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"w1\","
                + "\"schemaVersion\":99,\"revision\":0,\"payload\":{\"name\":\"x\","
                + "\"status\":\"ACTIVE\",\"count\":0}}";
        assertThrows(CodecException.class,
                () -> widgetCodecV2().decode(json.getBytes(StandardCharsets.UTF_8)));
    }

    // ── tests: invalid metadata ───────────────────────────────────────────────

    @Test
    void negativRevisionOnEncodeThrowsIllegalArgument() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        assertThrows(IllegalArgumentException.class,
                () -> codec.encode(WIDGET_KEY, new Widget("x", WidgetStatus.ACTIVE, 0), -1L));
    }

    @Test
    void nullKeyOnEncodeThrowsNullPointer() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        assertThrows(NullPointerException.class,
                () -> codec.encode(null, new Widget("x", WidgetStatus.ACTIVE, 0)));
    }

    @Test
    void nullValueOnEncodeThrowsNullPointer() {
        final VersionedCodec<Widget> codec = widgetCodecV2();
        assertThrows(NullPointerException.class,
                () -> codec.encode(WIDGET_KEY, null));
    }

    // ── tests: immutable collection in payload ────────────────────────────────

    public record WidgetBundle(String version, List<Widget> items) {}

    @Test
    void immutableListInPayloadRoundTrips() {
        final VersionedCodec<WidgetBundle> codec = JsonEnvelopeCodec.builder(WidgetBundle.class)
                .schemaVersion(1)
                .build();

        final WidgetBundle original = new WidgetBundle("1.0", List.of(
                new Widget("a", WidgetStatus.ACTIVE, 1),
                new Widget("b", WidgetStatus.ARCHIVED, 2)
        ));

        final WidgetBundle restored = codec.decode(codec.encode(WIDGET_KEY, original));
        assertEquals("1.0", restored.version());
        assertEquals(2, restored.items().size());
        assertEquals("a", restored.items().get(0).name());
        assertEquals(WidgetStatus.ARCHIVED, restored.items().get(1).status());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a v1 widget envelope document that pre-dates the 'count' field.
     * Crafted directly rather than stripping from codec output to avoid fragile JSON editing.
     */
    private static byte[] makeV1WidgetDocument(
            final String id, final String name, final String status) {
        final String json = "{\"envelopeVersion\":\"1\",\"type\":\"widget\",\"id\":\"" + id + "\","
                + "\"schemaVersion\":1,\"revision\":0,"
                + "\"payload\":{\"name\":\"" + name + "\",\"status\":\"" + status + "\"}}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
