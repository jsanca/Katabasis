package jsanca.katabasis.core.internal.http;

import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadInfo;
import jsanca.katabasis.core.internal.execution.DownloadExecutionContext;
import jsanca.katabasis.core.internal.model.DownloadTask;
import jsanca.katabasis.core.internal.strategy.DownloadStrategy;
import jsanca.katabasis.core.internal.util.ByteSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static jsanca.katabasis.core.internal.util.Preconditions.reject;

/**
 * Orchestrates HTTP/HTTPS downloads by probing the server for byte-range
 * support and delegating to the appropriate strategy.
 *
 * <p>If the server advertises {@code Accept-Ranges: bytes} and the content
 * length meets {@link DownloadConfig#segmentMinSizeBytes()}, a
 * {@link SegmentedHttpDownloadStrategy} is used; otherwise a plain
 * {@link HttpDownloadStrategy} handles the download.
 *
 * <p>If the probe itself fails (network error, timeout, etc.), the orchestrator
 * falls back silently to {@link HttpDownloadStrategy} so the download is still
 * attempted.
 *
 * @author jsanca &amp; elo
 */
public final class HttpDownloadOrchestrator implements DownloadStrategy {

    private static final Logger log = LoggerFactory.getLogger(HttpDownloadOrchestrator.class);
    private static final int DEFAULT_BUFFER_SIZE = 8 * (int) ByteSize.KB;

    private final HttpClient httpClient;
    private final DownloadConfig config;
    private final int bufferSize;

    /**
     * Creates an orchestrator using an {@link HttpClient} configured from
     * {@link DownloadConfig#httpTimeoutConfig()}.
     *
     * @param config download configuration (used for segmentCount, segmentMinSizeBytes,
     *               and timeout settings)
     */
    public HttpDownloadOrchestrator(final DownloadConfig config) {
        this(HttpClients.createFor(
                     Objects.requireNonNull(config, "config must not be null")
                             .httpTimeoutConfig()),
                config, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Creates an orchestrator with an explicit HTTP client and buffer size.
     *
     * @param httpClient the HTTP client to use
     * @param config     download configuration
     * @param bufferSize stream buffer size in bytes (must be &gt; 0)
     */
    public HttpDownloadOrchestrator(final HttpClient httpClient,
                                     final DownloadConfig config,
                                     final int bufferSize) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        reject(bufferSize <= 0, "bufferSize must be > 0");
        this.bufferSize = bufferSize;
    }

    @Override
    public void download(final DownloadTask task,
                         final DownloadExecutionContext context,
                         final Consumer<DownloadEvent> emitter) {

        DownloadStrategy.validate(task, context, emitter);

        final DownloadInfo info = task.info();
        final DownloadStrategy delegate = resolveDelegate(info);
        delegate.download(task, context, emitter);
    }

    private DownloadStrategy resolveDelegate(final DownloadInfo info) {
        final HttpRangeSupport.RangeResponse headResponse = headProbe(info);
        if (headResponse == null
                || !headResponse.acceptsRanges()
                || headResponse.contentLength() < config.segmentMinSizeBytes()) {
            log.debug("Selecting single-stream strategy for {} (acceptsRanges={}, contentLength={})",
                    info.downloadId(),
                    headResponse != null && headResponse.acceptsRanges(),
                    headResponse != null ? headResponse.contentLength() : 0);
            return new HttpDownloadStrategy(httpClient, bufferSize, config.httpTimeoutConfig());
        }

        // HEAD advertises ranges and size is above threshold: do a range capability probe
        final Optional<ResourceIdentity> identity = rangeCapabilityProbe(info, headResponse);
        if (identity.isEmpty()) {
            log.debug("Range capability probe did not yield a strong identity for {} "
                    + "— using single-stream", info.downloadId());
            return new HttpDownloadStrategy(httpClient, bufferSize, config.httpTimeoutConfig());
        }

        log.debug("Selecting segmented strategy for {} (contentLength={}, segmentCount={}, eTag={})",
                info.downloadId(), identity.get().contentLength(),
                config.segmentCount(), identity.get().strongETag().orElse("<none>"));
        return new SegmentedHttpDownloadStrategy(
                httpClient,
                config.segmentCount(),
                identity.get(),
                bufferSize,
                config.httpTimeoutConfig());
    }

    private HttpRangeSupport.RangeResponse headProbe(final DownloadInfo info) {
        try {
            return HttpRangeSupport.probe(httpClient, info.sourceUri(),
                    config.httpTimeoutConfig().responseTimeout());
        } catch (final Exception e) {
            log.warn("HEAD probe failed for {} — falling back to single-stream",
                    info.downloadId(), e);
            return null;
        }
    }

    private Optional<ResourceIdentity> rangeCapabilityProbe(
            final DownloadInfo info,
            final HttpRangeSupport.RangeResponse headResponse) {
        try {
            return HttpRangeSupport.rangeProbe(
                    httpClient,
                    info.sourceUri(),
                    headResponse.strongETag(),
                    headResponse.contentLength(),
                    config.httpTimeoutConfig().responseTimeout());
        } catch (final Exception e) {
            log.warn("Range capability probe failed for {} — falling back to single-stream",
                    info.downloadId(), e);
            return Optional.empty();
        }
    }
}
