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
     * Creates an orchestrator with a default JDK {@link HttpClient}.
     *
     * @param config download configuration (used for segmentCount and segmentMinSizeBytes)
     */
    public HttpDownloadOrchestrator(final DownloadConfig config) {
        this(HttpClients.createDefault(), config, DEFAULT_BUFFER_SIZE);
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
        HttpRangeSupport.RangeResponse rangeResponse = null;
        try {
            rangeResponse = HttpRangeSupport.probe(httpClient, info.sourceUri());
        } catch (Exception e) {
            log.warn("Probe failed for {} — falling back to single-stream download",
                    info.downloadId(), e);
        }

        if (rangeResponse != null
                && rangeResponse.acceptsRanges()
                && rangeResponse.contentLength() >= config.segmentMinSizeBytes()) {

            log.debug("Selecting segmented strategy for {} (contentLength={}, segmentCount={})",
                    info.downloadId(), rangeResponse.contentLength(), config.segmentCount());
            return new SegmentedHttpDownloadStrategy(
                    httpClient,
                    config.segmentCount(),
                    rangeResponse.contentLength(),
                    bufferSize);
        }

        log.debug("Selecting single-stream strategy for {}", info.downloadId());
        return new HttpDownloadStrategy(httpClient, bufferSize);
    }
}
