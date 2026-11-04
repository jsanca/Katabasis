package jsanca.katabasis.core.api.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HttpTimeoutConfigTest {

    @Test
    void disabledHasAllEmptyFields() {
        final HttpTimeoutConfig cfg = HttpTimeoutConfig.disabled();
        assertTrue(cfg.connectTimeout().isEmpty());
        assertTrue(cfg.responseTimeout().isEmpty());
        assertTrue(cfg.readInactivityTimeout().isEmpty());
    }

    @Test
    void defaultTimeoutsHasAllFieldsPresent() {
        final HttpTimeoutConfig cfg = HttpTimeoutConfig.defaultTimeouts();
        assertTrue(cfg.connectTimeout().isPresent());
        assertTrue(cfg.responseTimeout().isPresent());
        assertTrue(cfg.readInactivityTimeout().isPresent());
        cfg.connectTimeout().ifPresent(d -> assertTrue(d.toSeconds() > 0));
        cfg.responseTimeout().ifPresent(d -> assertTrue(d.toSeconds() > 0));
        cfg.readInactivityTimeout().ifPresent(d -> assertTrue(d.toSeconds() > 0));
    }

    @Test
    void builderSetsOnlyRequestedFields() {
        final HttpTimeoutConfig cfg = HttpTimeoutConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        assertEquals(Optional.of(Duration.ofSeconds(10)), cfg.connectTimeout());
        assertTrue(cfg.responseTimeout().isEmpty());
        assertTrue(cfg.readInactivityTimeout().isEmpty());
    }

    @Test
    void builderSetsAllFields() {
        final HttpTimeoutConfig cfg = HttpTimeoutConfig.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(30))
                .readInactivityTimeout(Duration.ofSeconds(15))
                .build();
        assertEquals(Optional.of(Duration.ofSeconds(5)), cfg.connectTimeout());
        assertEquals(Optional.of(Duration.ofSeconds(30)), cfg.responseTimeout());
        assertEquals(Optional.of(Duration.ofSeconds(15)), cfg.readInactivityTimeout());
    }

    @Test
    void negativeDurationIsRejectedForConnectTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTimeoutConfig(
                        Optional.of(Duration.ofSeconds(-1)),
                        Optional.empty(),
                        Optional.empty()));
    }

    @Test
    void zeroDurationIsRejectedForResponseTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpTimeoutConfig(
                        Optional.empty(),
                        Optional.of(Duration.ZERO),
                        Optional.empty()));
    }

    @Test
    void copyOfPreservesAllFields() {
        final HttpTimeoutConfig original = HttpTimeoutConfig.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readInactivityTimeout(Duration.ofSeconds(20))
                .build();
        final HttpTimeoutConfig copy = HttpTimeoutConfig.copyOf(original).build();
        assertEquals(original, copy);
    }

    @Test
    void downloadConfigDefaultConfigHasDisabledTimeouts() {
        final DownloadConfig cfg = DownloadConfig.defaultConfig();
        assertEquals(HttpTimeoutConfig.disabled(), cfg.httpTimeoutConfig());
    }

    @Test
    void downloadConfigBuilderPropagatesTimeoutConfig() {
        final HttpTimeoutConfig timeouts = HttpTimeoutConfig.defaultTimeouts();
        final DownloadConfig cfg = DownloadConfig.builder()
                .maxConcurrentDownloads(2)
                .segmentCount(2)
                .segmentMinSizeBytes(1024)
                .httpTimeoutConfig(timeouts)
                .build();
        assertEquals(timeouts, cfg.httpTimeoutConfig());
    }

    @Test
    void downloadConfigCopyOfPreservesTimeoutConfig() {
        final HttpTimeoutConfig timeouts = HttpTimeoutConfig.defaultTimeouts();
        final DownloadConfig original = DownloadConfig.copyOf(DownloadConfig.defaultConfig())
                .httpTimeoutConfig(timeouts)
                .build();
        final DownloadConfig copy = DownloadConfig.copyOf(original).build();
        assertEquals(timeouts, copy.httpTimeoutConfig());
    }
}
