package jsanca.katabasis.core.internal.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static jsanca.katabasis.core.internal.util.Preconditions.*;

/**
 * Utilities for HTTP byte-range capability negotiation, request construction,
 * per-segment response validation, and file splitting.
 *
 * <p>All methods are stateless and thread-safe.
 *
 * @author jsanca &amp; elo
 */
public final class HttpRangeSupport {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRangeSupport.class);

    private HttpRangeSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    // ── Probe result types ────────────────────────────────────────────────────

    /**
     * Result of a HEAD probe for byte-range capability.
     *
     * @param acceptsRanges {@code true} if the server advertises {@code Accept-Ranges: bytes}
     * @param contentLength total file size in bytes, or {@code 0} if not reported
     * @param strongETag    strong ETag from the HEAD response, if present
     */
    public record RangeResponse(boolean acceptsRanges, long contentLength,
                                Optional<String> strongETag) {
        public RangeResponse {
            reject(contentLength < 0, "contentLength must be >= 0");
            Objects.requireNonNull(strongETag, "strongETag must not be null");
        }
    }

    /**
     * A parsed {@code Content-Range} header value.
     *
     * @param start inclusive start byte offset
     * @param end   inclusive end byte offset
     * @param total total resource size in bytes
     */
    public record ContentRange(long start, long end, long total) {}

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

    // ── HEAD probe ────────────────────────────────────────────────────────────

    /**
     * Sends a HEAD request to determine whether the server supports byte-range downloads,
     * reports the total content length, and extracts any strong ETag.
     *
     * @param client          the HTTP client to use
     * @param uri             the resource URI
     * @param responseTimeout optional per-request timeout for receiving response headers
     * @return a {@link RangeResponse} describing the server's capabilities
     * @throws IOException          if an I/O error occurs
     * @throws InterruptedException if the request is interrupted
     */
    public static RangeResponse probe(final HttpClient client,
                                      final URI uri,
                                      final Optional<Duration> responseTimeout)
            throws IOException, InterruptedException {

        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");

        final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .method("HEAD", HttpRequest.BodyPublishers.noBody());
        responseTimeout.ifPresent(requestBuilder::timeout);

        final HttpResponse<Void> response =
                client.send(requestBuilder.build(), HttpResponse.BodyHandlers.discarding());

        final boolean acceptsRanges = response.headers()
                .firstValue("Accept-Ranges")
                .map(v -> v.equalsIgnoreCase("bytes"))
                .orElse(false);

        final long contentLength = response.headers()
                .firstValueAsLong("Content-Length")
                .orElse(0L);

        final Optional<String> strongETag = extractStrongETag(response);

        return new RangeResponse(acceptsRanges, contentLength, strongETag);
    }

    // ── Range capability probe ────────────────────────────────────────────────

    /**
     * Performs a lightweight {@code GET bytes=0-0} probe to verify that the server honours
     * range requests in practice, and returns a validated {@link ResourceIdentity} when safe.
     *
     * <p>Returns {@link Optional#empty()} (fall back to single-stream) when:
     * <ul>
     *   <li>The server responds with 200 (ignores the {@code Range} header).</li>
     *   <li>The server responds with 416 or any other non-206 status.</li>
     *   <li>The {@code Content-Range} is absent, unparseable, or inconsistent.</li>
     *   <li>The ETag from the range probe conflicts with the HEAD ETag.</li>
     *   <li>Neither HEAD nor range probe provides a strong ETag — cannot guarantee integrity.</li>
     * </ul>
     *
     * @param client            the HTTP client to use
     * @param uri               the resource URI
     * @param headETag          strong ETag from the preceding HEAD probe, if any
     * @param headContentLength content length reported by the HEAD probe
     * @param responseTimeout   optional per-request timeout
     * @return a validated identity ready for segmented use, or empty to fall back
     * @throws IOException          if an I/O error occurs sending the probe
     * @throws InterruptedException if the request is interrupted
     */
    public static Optional<ResourceIdentity> rangeProbe(final HttpClient client,
                                                         final URI uri,
                                                         final Optional<String> headETag,
                                                         final long headContentLength,
                                                         final Optional<Duration> responseTimeout)
            throws IOException, InterruptedException {

        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(headETag, "headETag must not be null");
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");

        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .header("Range", "bytes=0-0")
                .header("Accept-Encoding", "identity");
        headETag.ifPresent(etag -> builder.header("If-Range", etag));
        responseTimeout.ifPresent(builder::timeout);

        final HttpResponse<byte[]> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());

        final int status = response.statusCode();
        if (status != 206) {
            LOGGER.debug("Range probe returned {} — falling back to single-stream (uri={})", status, uri);
            return Optional.empty();
        }

        // Validate Content-Range: must be bytes 0-0/totalLength
        final Optional<ContentRange> contentRange = response.headers()
                .firstValue("Content-Range")
                .flatMap(HttpRangeSupport::parseContentRange);

        if (contentRange.isEmpty()) {
            LOGGER.debug("Range probe: missing or unparseable Content-Range — fallback (uri={})", uri);
            return Optional.empty();
        }
        final ContentRange cr = contentRange.get();
        if (cr.start() != 0 || cr.end() != 0 || cr.total() != headContentLength) {
            LOGGER.debug("Range probe: Content-Range inconsistency {} — fallback (uri={})", cr, uri);
            return Optional.empty();
        }

        // Exactly one byte must have been received
        if (response.body().length != 1) {
            LOGGER.debug("Range probe: expected 1 byte, got {} — fallback (uri={})",
                    response.body().length, uri);
            return Optional.empty();
        }

        // Extract ETag from probe response and reconcile with HEAD ETag
        final Optional<String> probeETag = extractStrongETag(response);

        if (headETag.isPresent() && probeETag.isPresent()
                && !headETag.get().equals(probeETag.get())) {
            LOGGER.warn("Range probe: ETag mismatch (HEAD={} probe={}) — fallback (uri={})",
                    headETag.get(), probeETag.get(), uri);
            return Optional.empty();
        }

        // Prefer probe ETag; fall back to HEAD ETag
        final Optional<String> finalETag = probeETag.isPresent() ? probeETag : headETag;

        // A strong validator is required; without one we cannot guarantee per-segment integrity
        if (finalETag.isEmpty()) {
            LOGGER.debug("Range probe: no strong ETag available — falling back to single-stream (uri={})", uri);
            return Optional.empty();
        }

        return Optional.of(new ResourceIdentity(headContentLength, finalETag));
    }

    // ── Segment request construction ──────────────────────────────────────────

    /**
     * Builds an HTTP GET request with a {@code Range} header for the specified byte range.
     *
     * @param uri   the resource URI
     * @param start inclusive start byte offset (must be >= 0)
     * @param end   inclusive end byte offset (must be >= {@code start})
     * @return an {@link HttpRequest} with {@code Range: bytes=start-end}
     */
    public static HttpRequest rangeRequest(final URI uri, final long start, final long end) {
        return rangeRequest(uri, start, end, Optional.empty());
    }

    /**
     * Builds an HTTP GET request with a {@code Range} header and an optional response timeout.
     *
     * @param uri             the resource URI
     * @param start           inclusive start byte offset (must be >= 0)
     * @param end             inclusive end byte offset (must be >= {@code start})
     * @param responseTimeout optional per-request timeout for receiving response headers
     * @return an {@link HttpRequest} with {@code Range: bytes=start-end}
     */
    public static HttpRequest rangeRequest(final URI uri,
                                            final long start,
                                            final long end,
                                            final Optional<Duration> responseTimeout) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
        reject(start < 0, "start must be >= 0");
        reject(end < start, "end must be >= start");

        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .header("Range", "bytes=" + start + "-" + end);
        responseTimeout.ifPresent(builder::timeout);
        return builder.build();
    }

    /**
     * Builds a range GET request that includes {@code If-Range} and
     * {@code Accept-Encoding: identity} when a strong ETag is available.
     *
     * <p>This is the preferred request form for segmented strategy chunk requests.
     *
     * @param uri             the resource URI
     * @param start           inclusive start byte offset
     * @param end             inclusive end byte offset
     * @param identity        resource identity providing the optional {@code If-Range} ETag
     * @param responseTimeout optional per-request timeout
     * @return a configured {@link HttpRequest}
     */
    public static HttpRequest rangeRequestWithValidator(final URI uri,
                                                         final long start,
                                                         final long end,
                                                         final ResourceIdentity identity,
                                                         final Optional<Duration> responseTimeout) {
        Objects.requireNonNull(uri, "uri must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(responseTimeout, "responseTimeout must not be null");
        reject(start < 0, "start must be >= 0");
        reject(end < start, "end must be >= start");

        final HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .header("Range", "bytes=" + start + "-" + end)
                .header("Accept-Encoding", "identity");
        identity.strongETag().ifPresent(etag -> builder.header("If-Range", etag));
        responseTimeout.ifPresent(builder::timeout);
        return builder.build();
    }

    // ── Segment response validation ───────────────────────────────────────────

    /**
     * Validates the response headers of a segment request against the established
     * resource identity and expected byte range.
     *
     * <p>Must be called only after confirming the status is 206.
     *
     * @param response the HTTP 206 segment response
     * @param chunk    the expected byte range for this segment
     * @param identity the resource identity established during the range probe
     * @throws ResourceIntegrityException if any header invariant is violated
     */
    public static void validateSegmentResponseHeaders(final HttpResponse<InputStream> response,
                                                       final ChunkRange chunk,
                                                       final ResourceIdentity identity)
            throws ResourceIntegrityException {

        // 1. Parse Content-Range
        final String contentRangeHeader = response.headers()
                .firstValue("Content-Range").orElse(null);
        if (contentRangeHeader == null) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": missing Content-Range header in 206 response");
        }
        final Optional<ContentRange> parsed = parseContentRange(contentRangeHeader);
        if (parsed.isEmpty()) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": unparseable Content-Range: " + contentRangeHeader);
        }
        final ContentRange cr = parsed.get();

        // 2. Validate byte offsets
        if (cr.start() != chunk.start()) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": Content-Range start mismatch: expected " + chunk.start()
                    + " got " + cr.start());
        }
        if (cr.end() != chunk.end()) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": Content-Range end mismatch: expected " + chunk.end()
                    + " got " + cr.end());
        }

        // 3. Validate total resource size
        if (cr.total() != identity.contentLength()) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": Content-Range total mismatch: expected " + identity.contentLength()
                    + " got " + cr.total());
        }

        // 4. ETag consistency (only when the response echoes an ETag)
        if (identity.strongETag().isPresent()) {
            final Optional<String> responseETag = extractStrongETag(response);
            if (responseETag.isPresent()
                    && !identity.strongETag().get().equals(responseETag.get())) {
                throw new ResourceIntegrityException("chunk " + chunk.index()
                        + ": ETag changed during segmented transfer: expected "
                        + identity.strongETag().get() + " got " + responseETag.get());
            }
        }
    }

    /**
     * Verifies that the number of bytes received for a segment equals the expected chunk size.
     *
     * <p>Must be called after the read loop exits normally (no exception), before the
     * segment is considered complete.
     *
     * @param received   actual number of bytes received
     * @param chunk      the expected byte range for this segment
     * @throws ResourceIntegrityException if the byte count does not match
     */
    public static void validateSegmentByteCount(final long received, final ChunkRange chunk)
            throws ResourceIntegrityException {
        if (received != chunk.size()) {
            throw new ResourceIntegrityException("chunk " + chunk.index()
                    + ": incomplete segment: expected " + chunk.size()
                    + " bytes, received " + received);
        }
    }

    // ── ETag utilities ────────────────────────────────────────────────────────

    /**
     * Extracts the strong ETag from a response, if present.
     *
     * <p>Weak ETags (prefixed with {@code W/}) are not suitable for {@code If-Range}
     * and are discarded.
     *
     * @param response any HTTP response
     * @return the strong ETag value as it appears in the header (with enclosing quotes),
     *         or empty if absent or weak
     */
    public static Optional<String> extractStrongETag(final HttpResponse<?> response) {
        return response.headers()
                .firstValue("ETag")
                .filter(etag -> etag.length() >= 2 && etag.startsWith("\"") && etag.endsWith("\""));
    }

    // ── Content-Range parsing ─────────────────────────────────────────────────

    /**
     * Parses a {@code Content-Range} header value.
     *
     * <p>Only the {@code bytes start-end/total} form is supported. The
     * {@code bytes * /total} (unsatisfied range) form returns empty.
     *
     * @param headerValue the raw header value
     * @return the parsed range, or empty if the value is null, absent, or unparseable
     */
    public static Optional<ContentRange> parseContentRange(final String headerValue) {
        if (headerValue == null || !headerValue.startsWith("bytes ")) {
            return Optional.empty();
        }
        final String rest = headerValue.substring("bytes ".length()).trim();
        if (rest.startsWith("*")) {
            return Optional.empty(); // unsatisfied range
        }
        final int slash = rest.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        final int dash = rest.indexOf('-');
        if (dash < 0 || dash > slash) {
            return Optional.empty();
        }
        try {
            final long start = Long.parseLong(rest.substring(0, dash).trim());
            final long end   = Long.parseLong(rest.substring(dash + 1, slash).trim());
            final long total = Long.parseLong(rest.substring(slash + 1).trim());
            return Optional.of(new ContentRange(start, end, total));
        } catch (final NumberFormatException e) {
            return Optional.empty();
        }
    }

    // ── Chunk splitting ───────────────────────────────────────────────────────

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
     */
    public static List<ChunkRange> split(final long contentLength, final int chunkCount) {

        reject(contentLength <= 0, "contentLength must be > 0");
        reject(chunkCount <= 0, "chunkCount must be > 0");

        final int actualCount = (int) Math.min(chunkCount, contentLength);
        final long chunkSize  = contentLength / actualCount;

        final List<ChunkRange> chunks = new ArrayList<>(actualCount);

        LOGGER.debug("Splitting {} bytes into {} chunks → actualCount={}, chunkSize={}",
                contentLength, chunkCount, actualCount, chunkSize);

        for (int i = 0; i < actualCount; i++) {
            final long start = (long) i * chunkSize;
            final long end   = (i == actualCount - 1) ? contentLength - 1 : start + chunkSize - 1;
            chunks.add(new ChunkRange(i, start, end));
        }
        return List.copyOf(chunks);
    }
}
