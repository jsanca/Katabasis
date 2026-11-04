package jsanca.katabasis.core.api.event;

/**
 * Publishes {@link DownloadEvent} instances to interested subscribers.
 *
 * <p>Part of the public API surface, but not wired into the current engine:
 * {@code DefaultDownloadManager} emits events directly to its listeners. This
 * interface is reserved for a future publisher abstraction.
 *
 * @author jsanca &amp; elo
 */
public interface DownloadEventPublisher {
}
