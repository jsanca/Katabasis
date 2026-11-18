package jsanca.katabasis.storage.json.api;

import jsanca.katabasis.core.api.manager.CheckpointSourceProvider;
import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.manager.DownloadStateStore;
import jsanca.katabasis.core.api.model.DownloadConfig;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadSnapshot;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests that drive a real DownloadManager through a live HTTP server
 * with CheckpointCoordinator attached, verifying that snapshots are persisted
 * and contain accurate recovery metadata.
 */
class CheckpointCoordinatorE2ETest {

    @TempDir
    Path tempDir;

    @TempDir
    Path checkpointDir;

    // ── minimal in-process HTTP/1.1 server ────────────────────────────────────

    private static final class TestHttpServer implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final byte[] content;
        volatile int perWriteDelayMs = 0;
        private volatile boolean closed = false;

        TestHttpServer(final byte[] content) throws IOException {
            this.content = content;
            this.serverSocket = new ServerSocket(0);
            Thread.ofVirtual().start(this::acceptLoop);
        }

        int port() { return serverSocket.getLocalPort(); }

        URI uri() { return URI.create("http://localhost:" + port() + "/file"); }

        private void acceptLoop() {
            while (!closed) {
                try {
                    final Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> handleConnection(socket));
                } catch (IOException e) {
                    if (!closed) { throw new UncheckedIOException(e); }
                }
            }
        }

        private void handleConnection(final Socket socket) {
            try (socket) {
                final InputStream in = socket.getInputStream();
                final String requestLine = readLine(in);
                if (requestLine == null || requestLine.isBlank()) { return; }
                while (!readLine(in).isEmpty()) { /* consume headers */ }

                final OutputStream out = socket.getOutputStream();

                if (requestLine.startsWith("HEAD")) {
                    final String headers = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: " + content.length + "\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(headers.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    return;
                }

                final String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Length: " + content.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                out.write(headers.getBytes(StandardCharsets.US_ASCII));

                if (perWriteDelayMs <= 0) {
                    out.write(content);
                } else {
                    final int piece = 512;
                    for (int offset = 0; offset < content.length; offset += piece) {
                        final int toWrite = Math.min(piece, content.length - offset);
                        out.write(content, offset, toWrite);
                        out.flush();
                        try { Thread.sleep(perWriteDelayMs); } catch (InterruptedException ignored) {}
                    }
                }
                out.flush();
            } catch (IOException ignored) {
                // normal on client disconnect during cancellation
            }
        }

        private static String readLine(final InputStream in) throws IOException {
            final StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\r') { in.read(); break; }
                if (c == '\n') { break; }
                sb.append((char) c);
            }
            return sb.toString();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
        }
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void completedDownloadDeletesCheckpointFromStore() throws Exception {
        final byte[] content = new byte[32 * 1024];
        Arrays.fill(content, (byte) 'Z');

        final DownloadStateStore store = DownloadStateStores.fileStore(checkpointDir);

        try (TestHttpServer server = new TestHttpServer(content)) {
            final DownloadManager manager = DownloadManagers.createDefault(
                    DownloadConfig.defaultConfig(),
                    snap -> { try { store.save(snap); } catch (Exception ignored) {} });
            final CheckpointCoordinator coordinator = CheckpointCoordinator.create(
                    manager, (CheckpointSourceProvider) manager, store,
                    Duration.ofMillis(100), ev -> {});

            final CountDownLatch completedLatch = new CountDownLatch(1);
            manager.addListener(event -> {
                if (event instanceof DownloadCompletedEvent) { completedLatch.countDown(); }
            });

            final Path target = tempDir.resolve("output.bin");
            final var submission = manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri())
                    .targetPath(target)
                    .build());
            final String downloadId = submission.downloadId();

            assertTrue(completedLatch.await(10, TimeUnit.SECONDS),
                    "Download should complete within 10 seconds");

            coordinator.close();
            manager.close();

            final Optional<DownloadSnapshot> snap = store.findById(downloadId);
            assertTrue(snap.isEmpty(),
                    "Completed download checkpoint should be deleted from store");
        }
    }

    @Test
    void shutdownWithActiveDownloadPersistsSnapshotWithConfirmedBytes() throws Exception {
        final int contentSize = 128 * 1024;
        final byte[] content = new byte[contentSize];
        Arrays.fill(content, (byte) 'X');

        final DownloadStateStore store = DownloadStateStores.fileStore(checkpointDir);

        try (TestHttpServer server = new TestHttpServer(content)) {
            server.perWriteDelayMs = 10; // throttle so the manager is closed mid-download

            final DownloadManager manager = DownloadManagers.createDefault(
                    DownloadConfig.defaultConfig(),
                    snap -> { try { store.save(snap); } catch (Exception ignored) {} });
            final CheckpointCoordinator coordinator = CheckpointCoordinator.create(
                    manager, (CheckpointSourceProvider) manager, store,
                    Duration.ofMillis(50), ev -> {});

            final CountDownLatch progressLatch = new CountDownLatch(1);
            manager.addListener(event -> {
                if (event instanceof DownloadProgressEvent p && p.downloadedBytes() > 0) {
                    progressLatch.countDown();
                }
            });

            final Path target = tempDir.resolve("partial.bin");
            final var submission = manager.download(DownloadRequest.builder()
                    .sourceUri(server.uri())
                    .targetPath(target)
                    .build());
            final String downloadId = submission.downloadId();

            // Wait until at least one progress event confirms bytes are flowing.
            assertTrue(progressLatch.await(10, TimeUnit.SECONDS),
                    "Should receive at least one progress event");

            // Flush current state via coordinator, then close manager (triggers shutdown snapshot).
            coordinator.close();
            manager.close();

            final Optional<DownloadSnapshot> snap = store.findById(downloadId);
            assertTrue(snap.isPresent(), "Snapshot should be persisted after shutdown");

            final DownloadSnapshot s = snap.get();
            assertTrue(s.confirmedBytes() > 0,
                    "confirmedBytes should be > 0 after partial download; got " + s.confirmedBytes());
            assertEquals(contentSize, s.expectedTotalBytes(),
                    "expectedTotalBytes should match content length");
            assertNotEquals(DownloadStatus.COMPLETED, s.status(),
                    "Status should not be COMPLETED for an interrupted download");
        }
    }

}
