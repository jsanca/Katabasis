package jsanca.katabasis.storage.json.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StorageKeyTest {

    @Test
    void validKeyAccepted() {
        final StorageKey key = new StorageKey("download-task", "abc123");
        assertEquals("download-task", key.type());
        assertEquals("abc123", key.id());
    }

    @Test
    void singleCharKeysAccepted() {
        assertDoesNotThrow(() -> new StorageKey("a", "b"));
    }

    @Test
    void underscoresAndHyphensAccepted() {
        assertDoesNotThrow(() -> new StorageKey("my_type", "task-001"));
        assertDoesNotThrow(() -> new StorageKey("type-with-hyphens", "id_with_underscores"));
    }

    @Test
    void typeStartingWithDigitAccepted() {
        assertDoesNotThrow(() -> new StorageKey("1type", "1id"));
    }

    @Test
    void nullTypeRejected() {
        assertThrows(NullPointerException.class,
                () -> new StorageKey(null, "id"));
    }

    @Test
    void nullIdRejected() {
        assertThrows(NullPointerException.class,
                () -> new StorageKey("type", null));
    }

    @Test
    void emptyTypeRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("", "id"));
    }

    @Test
    void emptyIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("type", ""));
    }

    @Test
    void typeWithSlashRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("my/type", "id"));
    }

    @Test
    void typeWithDotRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("com.example.Type", "id"));
    }

    @Test
    void typeWithSpaceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("my type", "id"));
    }

    @Test
    void idWithPathTraversalRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new StorageKey("type", "../etc/passwd"));
    }

    @Test
    void equalsAndHashCodeAreDerivedFromComponents() {
        final StorageKey a = new StorageKey("download-task", "t1");
        final StorageKey b = new StorageKey("download-task", "t1");
        final StorageKey c = new StorageKey("download-task", "t2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
