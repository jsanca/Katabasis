package jsanca.katabasis.core.internal.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static jsanca.katabasis.core.internal.util.Preconditions.*;
/**
 * Utilities for HTTP byte-range request support used by segmented downloads.
 *
 * <p>Provides server capability probing, range-request construction, and
 * file-to-chunk splitting logic. All methods are stateless and thread-safe.
 * @author jsanca & elo
 */
public final class HttpRangeSupport {

    private final static Logger LOGGER = LoggerFactory.getLogger(HttpRangeSupport.class);
    private HttpRangeSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Result of probing a server for byte-range capability.
     *
     * @param acceptsRanges {@code true} if the server advertises {@code Accept-Ranges: bytes}
     * @param contentLength total file size in bytes, or {@code 0} if not reported
     */
    public record RangeResponse(boolean acceptsRanges, long contentLength) {
        public RangeResponse {

            reject(contentLength < 0, "contentLength must be >= 0");
        }
    }

    /**
     * A single byte-range chunk within a segmented download.
     *
     * @param index zero-based position of this chunk in the download sequence
     * @param start inclusive start byte offset
     * @param end   inclusive end byte offset
     */
    public record ChunkRange(int index, long start, long end) {
        public ChunkRange {

            reject(index < 0, "index must be >= 0");
            reject(start < 0, "start must be >= 0");
            reject(end < start, "end must be >= start");
        }

        /** Returns the number of bytes covered by this chunk. */
        public long size() {
            return end - start + 1;
        }
    }

    /**
     * Sends a HEAD request to determine whether the server supports byte-range
     * downloads and reports the total content length.
     *
     * @param client the HTTP client to use
     * @param uri    the resource URI
     * @return a {@link RangeResponse} describing the server's capabilities
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the request is interrupted
     */
    public static RangeResponse probe(final HttpClient client, final URI uri)
            throws IOException, InterruptedException {

        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(uri, "uri must not be null");

        final HttpRequest request = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build();

        final HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

        final boolean acceptsRanges = response.headers()
                .firstValue("Accept-Ranges")
                .map(v -> v.equalsIgnoreCase("bytes"))
                .orElse(false);

        final long contentLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(0L);

        return new RangeResponse(acceptsRanges, contentLength);
    }

    /**
     * Builds an HTTP GET request with a {@code Range} header for the specified byte range.
     *
     * @param uri   the resource URI
     * @param start inclusive start byte offset (must be >= 0)
     * @param end   inclusive end byte offset (must be >= {@code start})
     * @return an {@link HttpRequest} with {@code Range: bytes=start-end}
     * @throws IllegalArgumentException if {@code start < 0} or {@code end < start}
     */
    public static HttpRequest rangeRequest(final URI uri, final long start, final long end) {
        Objects.requireNonNull(uri, "uri must not be null");
        reject(start < 0, "start must be >= 0");
        reject(end < start, "end must be >= start");

        return HttpRequest.newBuilder(uri)
                .GET()
                .header("Range", "bytes=" + start + "-" + end)
                .build();
    }

    /**
     * Divides a file into at most {@code chunkCount} non-overlapping, contiguous byte ranges.
     *
     * <p>If {@code chunkCount} exceeds {@code contentLength}, the result is capped so that
     * each chunk covers exactly one byte. The last chunk always absorbs any bytes left over
     * from integer division.
     *
     * @param contentLength total file size in bytes (must be > 0)
     * @param chunkCount    desired number of chunks (must be > 0)
     * @return an immutable list of {@link ChunkRange} instances covering {@code [0, contentLength-1]}
     * @throws IllegalArgumentException if {@code contentLength <= 0} or {@code chunkCount <= 0}
     */
    public static List<ChunkRange> split(final long contentLength, final int chunkCount) {

        reject(contentLength <= 0, "contentLength must be > 0");
        reject(chunkCount <= 0, "chunkCount must be > 0");

        final int actualCount = (int) Math.min(chunkCount, contentLength);
        final long chunkSize = contentLength / actualCount;

        final List<ChunkRange> chunks = new ArrayList<>(actualCount);

        LOGGER.debug("Splitting {}bytes into {} chunks → actualCount={}, chunkSize={}",
                contentLength, chunkCount, actualCount, chunkSize);

        for (int i = 0; i < actualCount; i++) {
            final long start = (long) i * chunkSize;
            final long end = (i == actualCount - 1) ? contentLength - 1 : start + chunkSize - 1;
            chunks.add(new ChunkRange(i, start, end));
        }
        return List.copyOf(chunks);
    }
}
