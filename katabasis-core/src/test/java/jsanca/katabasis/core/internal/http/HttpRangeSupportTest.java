package jsanca.katabasis.core.internal.http;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HttpRangeSupportTest {

    // ── split() ──────────────────────────────────────────────────────────────

    @Test
    void splitShouldReturnEqualChunksWhenContentLengthIsDivisibleExactly() {
        List<HttpRangeSupport.ChunkRange> chunks = HttpRangeSupport.split(12, 4);

        assertEquals(4, chunks.size());
        assertEquals(new HttpRangeSupport.ChunkRange(0, 0, 2), chunks.get(0));
        assertEquals(new HttpRangeSupport.ChunkRange(1, 3, 5), chunks.get(1));
        assertEquals(new HttpRangeSupport.ChunkRange(2, 6, 8), chunks.get(2));
        assertEquals(new HttpRangeSupport.ChunkRange(3, 9, 11), chunks.get(3));
    }

    @Test
    void splitShouldAbsorbRemainderInLastChunkWhenContentLengthIsNotDivisible() {
        List<HttpRangeSupport.ChunkRange> chunks = HttpRangeSupport.split(10, 3);

        assertEquals(3, chunks.size());
        assertEquals(new HttpRangeSupport.ChunkRange(0, 0, 2), chunks.get(0));
        assertEquals(new HttpRangeSupport.ChunkRange(1, 3, 5), chunks.get(1));
        assertEquals(new HttpRangeSupport.ChunkRange(2, 6, 9), chunks.get(2)); // absorbs extra byte
        assertEquals(4L, chunks.get(2).size());
    }

    @Test
    void splitShouldCapToOneBytesPerChunkWhenChunkCountExceedsContentLength() {
        List<HttpRangeSupport.ChunkRange> chunks = HttpRangeSupport.split(3, 10);

        assertEquals(3, chunks.size());
        assertEquals(new HttpRangeSupport.ChunkRange(0, 0, 0), chunks.get(0));
        assertEquals(new HttpRangeSupport.ChunkRange(1, 1, 1), chunks.get(1));
        assertEquals(new HttpRangeSupport.ChunkRange(2, 2, 2), chunks.get(2));
    }

    @Test
    void splitShouldCoverAllBytesWithNoGapsAndNoOverlaps() {
        long contentLength = 100;
        List<HttpRangeSupport.ChunkRange> chunks = HttpRangeSupport.split(contentLength, 7);

        assertEquals(0L, chunks.get(0).start());
        for (int i = 0; i < chunks.size() - 1; i++) {
            assertEquals(chunks.get(i).end() + 1, chunks.get(i + 1).start(),
                    "Gap or overlap between chunk " + i + " and " + (i + 1));
        }
        assertEquals(contentLength - 1, chunks.get(chunks.size() - 1).end());
    }

    @Test
    void splitShouldReturnSingleChunkWhenChunkCountIsOne() {
        List<HttpRangeSupport.ChunkRange> chunks = HttpRangeSupport.split(500, 1);

        assertEquals(1, chunks.size());
        assertEquals(new HttpRangeSupport.ChunkRange(0, 0, 499), chunks.get(0));
        assertEquals(500L, chunks.get(0).size());
    }

    @Test
    void splitShouldThrowWhenContentLengthIsZero() {
        assertThrows(IllegalArgumentException.class, () -> HttpRangeSupport.split(0, 4));
    }

    @Test
    void splitShouldThrowWhenContentLengthIsNegative() {
        assertThrows(IllegalArgumentException.class, () -> HttpRangeSupport.split(-1, 4));
    }

    @Test
    void splitShouldThrowWhenChunkCountIsZero() {
        assertThrows(IllegalArgumentException.class, () -> HttpRangeSupport.split(100, 0));
    }

    // ── ChunkRange.size() ────────────────────────────────────────────────────

    @Test
    void chunkRangeSizeShouldReturnCorrectByteCount() {
        assertEquals(10L, new HttpRangeSupport.ChunkRange(0, 5, 14).size());
        assertEquals(1L,  new HttpRangeSupport.ChunkRange(0, 0, 0).size());
        assertEquals(100L, new HttpRangeSupport.ChunkRange(2, 200, 299).size());
    }

    @Test
    void chunkRangeSizeShouldBeSingleByteWhenStartEqualsEnd() {
        assertEquals(1L, new HttpRangeSupport.ChunkRange(0, 42, 42).size());
    }

    // ── rangeRequest() ───────────────────────────────────────────────────────

    @Test
    void rangeRequestShouldSetCorrectRangeHeader() {
        URI uri = URI.create("https://example.com/file.zip");

        HttpRequest request = HttpRangeSupport.rangeRequest(uri, 0, 99);

        assertEquals("bytes=0-99", request.headers().firstValue("Range").orElseThrow());
    }

    @Test
    void rangeRequestShouldUseGetMethod() {
        URI uri = URI.create("https://example.com/file.zip");

        HttpRequest request = HttpRangeSupport.rangeRequest(uri, 1024, 2047);

        assertEquals("GET", request.method());
    }

    @Test
    void rangeRequestShouldEncodeArbitraryByteRanges() {
        URI uri = URI.create("https://example.com/file.bin");

        HttpRequest request = HttpRangeSupport.rangeRequest(uri, 1_000_000L, 1_999_999L);

        assertEquals("bytes=1000000-1999999", request.headers().firstValue("Range").orElseThrow());
    }

    @Test
    void rangeRequestShouldThrowWhenStartIsNegative() {
        URI uri = URI.create("https://example.com/file.zip");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRangeSupport.rangeRequest(uri, -1, 99));
    }

    @Test
    void rangeRequestShouldThrowWhenEndIsLessThanStart() {
        URI uri = URI.create("https://example.com/file.zip");

        assertThrows(IllegalArgumentException.class,
                () -> HttpRangeSupport.rangeRequest(uri, 50, 49));
    }
}
