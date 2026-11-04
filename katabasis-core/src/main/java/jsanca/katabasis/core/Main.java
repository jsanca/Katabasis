package jsanca.katabasis.core;


import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;

import java.net.URI;
import java.nio.file.Path;

/**
 * Simple entry point to test the DownloadManager.
 * @author jsanca &amp; elo
 */
public class Main {

    /**
     * Runs a single download from the command line.
     *
     * <p>Usage: {@code java Main <url> <targetPath>}. The process stays alive
     * for ten seconds after submission so the asynchronous download can finish.
     *
     * @param args the source URL and the local target path
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.err.println("Usage: java Main <url> <targetPath>");
            return;
        }

        final String urlToDownload = args[0];
        final String localPathToDownload = args[1];
        final DownloadManager manager = DownloadManagers.createDefault();

        manager.addListener(System.out::println);

        final DownloadRequest request = DownloadRequest.builder()
                .sourceUri(URI.create(urlToDownload))
                .targetPath(Path.of(localPathToDownload))
                .build();

        final DownloadSubmissionResult result = manager.download(request);
        System.out.println("Result: " + result);

        // Prevent main thread from exiting immediately (simple approach)
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
