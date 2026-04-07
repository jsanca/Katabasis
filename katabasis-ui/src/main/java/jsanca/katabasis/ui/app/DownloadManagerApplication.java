package jsanca.katabasis.ui.app;

import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.model.DownloadSubmissionResult;
import jsanca.katabasis.core.api.model.DownloadControlResult;
import jsanca.katabasis.core.api.model.DownloadStatus;
import jsanca.katabasis.core.api.event.DownloadEvent;
import jsanca.katabasis.core.api.event.DownloadCancelledEvent;
import jsanca.katabasis.core.api.event.DownloadCompletedEvent;
import jsanca.katabasis.core.api.event.DownloadFailedEvent;
import jsanca.katabasis.core.api.event.DownloadPausedEvent;
import jsanca.katabasis.core.api.event.DownloadProgressEvent;
import jsanca.katabasis.core.api.event.DownloadStartedEvent;
import java.text.DecimalFormat;
import java.awt.Desktop;
import java.io.IOException;
import javafx.application.Platform;
import java.net.URI;
import java.nio.file.Path;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;

/**
 * Entry point for the Katabasis UI application.
 *
 * <p>This iteration introduces the Stitch dark mode styling with Cards.
 *
 * @author jsanca
 */
public final class DownloadManagerApplication extends Application {

    private final static Logger logger = LoggerFactory.getLogger(DownloadManagerApplication.class);
    private final ObservableList<DownloadRow> downloads = FXCollections.observableArrayList();
    private DownloadManager downloadManager;
    private Path downloadDirectory;

    public static void main(final String[] args) {
        launch(args);
    }

    @Override
    public void start(final Stage primaryStage) {
        final Label titleLabel = new Label("Katabasis");
        titleLabel.getStyleClass().add("title-label");

        final Label subtitleLabel = new Label("Ready. Copy a URL to the clipboard and click to add.");
        subtitleLabel.getStyleClass().add("subtitle-label");

        this.downloadDirectory = resolveDefaultDownloadDirectory();
        final Label downloadDirectoryLabel = new Label(
                "Folder: " + this.downloadDirectory.toAbsolutePath());
        downloadDirectoryLabel.getStyleClass().add("subtitle-label");

        this.downloadManager = DownloadManagers.createDefault();

        this.downloadManager.addListener(event -> {
            Platform.runLater(() -> handleEvent(event));
        });

        final Button addFromClipboardButton = new Button("Add from Clipboard");
        addFromClipboardButton.getStyleClass().add("primary-cta");

        final Button selectDownloadFolderButton = new Button("Select Folder");
        selectDownloadFolderButton.setOnAction(event -> chooseDownloadDirectory(primaryStage, downloadDirectoryLabel));

        HBox topButtonsBox = new HBox(12, addFromClipboardButton, selectDownloadFolderButton);
        topButtonsBox.setAlignment(Pos.CENTER_LEFT);

        final ListView<DownloadRow> downloadsListView = new ListView<>(downloads);
        downloadsListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        downloadsListView.setPlaceholder(new Label("No downloads yet."));
        downloadsListView.setCellFactory(listView -> new DownloadRowCell());

        addFromClipboardButton.setOnAction(event -> addDownloadFromClipboard(subtitleLabel));

        VBox.setVgrow(downloadsListView, Priority.ALWAYS);

        final VBox root = new VBox(
                16,
                titleLabel,
                subtitleLabel,
                downloadDirectoryLabel,
                topButtonsBox,
                downloadsListView
        );

        root.setOnDragOver(event -> {
            final var dragboard = event.getDragboard();
            if (dragboard.hasFiles()) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });

        root.setOnDragDropped(event -> {
            final var dragboard = event.getDragboard();
            boolean success = false;
            if (dragboard.hasFiles()) {
                final File file = dragboard.getFiles().getFirst();
                importDownloadsFromFile(file.toPath(), subtitleLabel);
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        root.setPadding(new Insets(24));
        root.getStyleClass().add("root");

        final Scene scene = new Scene(root, 860, 600);

        URL stylesheetUrl = getClass().getResource("style.css");
        if (stylesheetUrl != null) {
            scene.getStylesheets().add(stylesheetUrl.toExternalForm());
        } else {
            System.err.println("Warning: Could not find style.css");
        }

        primaryStage.setTitle("Katabasis");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private void addDownloadFromClipboard(final Label subtitleLabel) {
        final Clipboard clipboard = Clipboard.getSystemClipboard();

        if (!clipboard.hasString()) {
            subtitleLabel.setText("Clipboard does not contain text.");
            return;
        }

        final String rawValue = clipboard.getString();
        if (rawValue == null) {
            subtitleLabel.setText("Clipboard is empty.");
            return;
        }

        final String url = rawValue.trim();
        if (url.isEmpty()) {
            subtitleLabel.setText("Clipboard text is blank.");
            return;
        }

        if (submitDownload(url, subtitleLabel)) {
            subtitleLabel.setText("Download submitted: " + abbreviate(url, 90));
        }
    }


    private void importDownloadsFromFile(final Path filePath, final Label subtitleLabel) {
        try {
            final List<String> lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
            int submitted = 0;

            for (final String line : lines) {
                final String url = line == null ? "" : line.trim();
                if (url.isEmpty()) {
                    continue;
                }
                if (submitDownload(url, subtitleLabel)) {
                    submitted++;
                }
            }

            subtitleLabel.setText("Submitted " + submitted + " download(s) from file: " + filePath.getFileName());
        } catch (Exception exception) {
            subtitleLabel.setText("Failed to import downloads: " + exception.getMessage());
        }
    }

    private boolean submitDownload(final String url, final Label subtitleLabel) {
        try {
            final Path targetPath = buildTargetPath(url);
            final DownloadRequest request = new DownloadRequest(
                    URI.create(url),
                    targetPath
            );

            final DownloadSubmissionResult result = this.downloadManager.download(request);

            final DownloadRow row = new DownloadRow(
                    result.downloadId(),
                    url,
                    targetPath.getFileName().toString(),
                    targetPath,
                    null,
                    result.status(),
                    "Submitted to manager",
                    0.0
            );
            downloads.addFirst(row);
            return true;
        } catch (Exception exception) {
            logger.warn("Failed to submit download for URL: {}", url, exception);
            subtitleLabel.setText("Invalid URL: " + url);
            return false;
        }
    }

    private void chooseDownloadDirectory(final Stage owner, final Label downloadDirectoryLabel) {
        final DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Select download folder");

        final File initialDirectory = this.downloadDirectory != null
                ? this.downloadDirectory.toFile()
                : resolveDefaultDownloadDirectory().toFile();

        if (initialDirectory.exists() && initialDirectory.isDirectory()) {
            directoryChooser.setInitialDirectory(initialDirectory);
        }

        final File selectedDirectory = directoryChooser.showDialog(owner);
        if (selectedDirectory == null) {
            return;
        }

        this.downloadDirectory = selectedDirectory.toPath();
        downloadDirectoryLabel.setText("Folder: " + this.downloadDirectory.toAbsolutePath());
    }

    private Path buildTargetPath(final String url) {
        final String fileName = resolveFileName(url);
        return this.downloadDirectory.resolve(fileName);
    }

    private static Path resolveDefaultDownloadDirectory() {
        final Path userHome = Path.of(System.getProperty("user.home"));
        final Path downloadsDirectory = userHome.resolve("Downloads");

        if (downloadsDirectory.toFile().exists() && downloadsDirectory.toFile().isDirectory()) {
            return downloadsDirectory;
        }

        final Path fallbackDirectory = userHome.resolve("katabasis-downloads");
        fallbackDirectory.toFile().mkdirs();
        return fallbackDirectory;
    }

    private static String resolveFileName(final String url) {
        try {
            final URI uri = URI.create(url);
            final String path = uri.getPath();

            if (path == null || path.isBlank() || path.endsWith("/")) {
                return "download-" + System.currentTimeMillis();
            }

            final Path fileName = Path.of(path).getFileName();
            if (fileName == null) {
                return "download-" + System.currentTimeMillis();
            }

            final String resolved = fileName.toString().trim();
            return resolved.isEmpty() ? "download-" + System.currentTimeMillis() : resolved;
        } catch (Exception e) {
            return "download-" + System.currentTimeMillis();
        }
    }

    private static String abbreviate(final String value, final int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private static String styleClassForStatus(final DownloadStatus status) {
        return switch (status) { // switch expression feature
            case COMPLETED -> "status-completed";
            case FAILED, CANCELLED -> "status-failed";
            case PAUSED -> "status-paused";
            case IN_PROGRESS -> "status-in-progress";
            case PENDING -> "status-pending";
        };
    }

    private static String displayLabelForStatus(final DownloadStatus status) {
        return switch (status) {
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            case PAUSED -> "Paused";
            case IN_PROGRESS -> "Downloading";
            case PENDING -> "Pending";
        };
    }

    private record DownloadRow(
            String downloadId,
            String sourceUrl,
            String fileName,
            Path targetPath,
            Path finalPath,
            DownloadStatus status,
            String statusMessage,
            double progress
    ) {
    }

    private final class DownloadRowCell extends ListCell<DownloadRow> {

        private static final String SVG_PLAY = "M8 5v14l11-7z";
        private static final String SVG_PAUSE = "M6 19h4V5H6v14zm8-14v14h4V5h-4z";
        private static final String SVG_CANCEL = "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";

        private final Label urlLabel = new Label();
        private final Label fileNameLabel = new Label();
        private final Label statusLabel = new Label();
        private final ProgressBar progressBar = new ProgressBar(0);

        private final Button toggleButton = createIconButton(SVG_PAUSE);
        private final Button cancelButton = createIconButton(SVG_CANCEL);

        private final VBox cardContainer = new VBox(10);

        private DownloadRowCell() {
            fileNameLabel.getStyleClass().add("card-title");
            urlLabel.getStyleClass().add("subtitle-label");
            urlLabel.setWrapText(true);
            urlLabel.setMaxWidth(Double.MAX_VALUE);
            statusLabel.getStyleClass().add("card-status");
            progressBar.getStyleClass().add("styled-progress-bar");
            progressBar.setMaxWidth(Double.MAX_VALUE);

            cancelButton.getStyleClass().add("cancel-button");

            final VBox headerTextBox = new VBox(4, fileNameLabel, urlLabel);
            HBox.setHgrow(headerTextBox, Priority.ALWAYS);
            HBox.setHgrow(urlLabel, Priority.ALWAYS);

            HBox headerBox = new HBox(12, headerTextBox);

            HBox statusBox = new HBox(12, statusLabel);
            HBox.setHgrow(statusBox, Priority.ALWAYS);
            statusBox.setAlignment(Pos.CENTER_LEFT);

            HBox actionsBox = new HBox(8, toggleButton, cancelButton);
            actionsBox.setAlignment(Pos.CENTER_RIGHT);

            HBox footerBox = new HBox(12, statusBox, actionsBox);
            footerBox.setAlignment(Pos.CENTER);

            cardContainer.getStyleClass().add("download-card");
            cardContainer.getChildren().addAll(headerBox, progressBar, footerBox);

            toggleButton.setOnAction(event -> {
                final DownloadRow item = getItem();
                if (item == null) return;

                final DownloadRow updatedRow;
                if (DownloadStatus.PAUSED == item.status()) {
                    DownloadControlResult res = downloadManager.resume(item.downloadId());
                    updatedRow = new DownloadRow(
                            item.downloadId(),
                            item.sourceUrl(),
                            item.fileName(),
                            item.targetPath(),
                            item.finalPath(),
                            DownloadStatus.IN_PROGRESS,
                            res.message() != null ? res.message() : "Resumed",
                            item.progress()
                    );
                } else {
                    DownloadControlResult res = downloadManager.pause(item.downloadId());
                    updatedRow = new DownloadRow(
                            item.downloadId(),
                            item.sourceUrl(),
                            item.fileName(),
                            item.targetPath(),
                            item.finalPath(),
                            DownloadStatus.PAUSED,
                            res.message() != null ? res.message() : "Paused",
                            item.progress()
                    );
                }

                final int index = downloads.indexOf(item);
                if (index >= 0) {
                    downloads.set(index, updatedRow);
                }
                refreshCell(updatedRow);
            });

            cancelButton.setOnAction(event -> {
                final DownloadRow item = getItem();
                if (item == null) return;
                DownloadControlResult res = downloadManager.cancel(item.downloadId());
                final DownloadRow updatedRow = new DownloadRow(
                        item.downloadId(),
                        item.sourceUrl(),
                        item.fileName(),
                        item.targetPath(),
                        item.finalPath(),
                        DownloadStatus.CANCELLED,
                        res.message() != null ? res.message() : "Cancelled",
                        item.progress()
                );
                final int index = downloads.indexOf(item);
                if (index >= 0) {
                    downloads.set(index, updatedRow);
                }
                refreshCell(updatedRow);
            });

            cardContainer.setOnMouseClicked(event -> {
                final DownloadRow item = getItem();
                if (item == null || item.status() != DownloadStatus.COMPLETED) {
                    return;
                }
                openDownloadLocation(item);
            });

            final MenuItem pauseResumeItem = new MenuItem("Pause / Resume");
            final MenuItem cancelItem = new MenuItem("Cancel");
            final ContextMenu contextMenu = new ContextMenu(pauseResumeItem, cancelItem);

            pauseResumeItem.setOnAction(e -> toggleButton.fire());
            cancelItem.setOnAction(e -> cancelButton.fire());
            setContextMenu(contextMenu);
        }

        private void openDownloadLocation(final DownloadRow item) {
            final Path pathToOpen = item.finalPath() != null ? item.finalPath() : item.targetPath();
            if (pathToOpen == null) {
                return;
            }

            if (!Desktop.isDesktopSupported()) {
                return;
            }

            try {
                final Desktop desktop = Desktop.getDesktop();
                final File targetFile = pathToOpen.toFile();
                final File parentDirectory = targetFile.isDirectory() ? targetFile : targetFile.getParentFile();
                if (parentDirectory != null && parentDirectory.exists()) {
                    desktop.open(parentDirectory);
                }
            } catch (IOException ignored) {
                // Best-effort UX action; failures are intentionally non-fatal.
            }
        }

        private Button createIconButton(String svgData) {
            SVGPath path = new SVGPath();
            path.setContent(svgData);
            path.getStyleClass().add("svg-path");

            Button btn = new Button();
            btn.setGraphic(path);
            btn.getStyleClass().addAll("button", "icon-button");
            return btn;
        }

        private void updateToggleIcon(String svgData) {
            SVGPath path = new SVGPath();
            path.setContent(svgData);
            path.getStyleClass().add("svg-path");
            toggleButton.setGraphic(path);
        }

        @Override
        protected void updateItem(final DownloadRow item, final boolean empty) {
            super.updateItem(item, empty);

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }

            refreshCell(item);
            setGraphic(cardContainer);
        }

        private void refreshCell(final DownloadRow item) {
            fileNameLabel.setText(item.fileName());
            urlLabel.setText(item.sourceUrl());
            statusLabel.setText(displayLabelForStatus(item.status()));

            // Re-apply correct text color class
            statusLabel.getStyleClass().removeAll("status-completed", "status-failed", "status-paused", "status-in-progress", "status-pending");
            statusLabel.getStyleClass().add(styleClassForStatus(item.status()));

            progressBar.setProgress(item.progress());

            progressBar.setStyle("-fx-accent: #00ff88;");
            logger.info("refreshCell status={} progress={}", item.status(), item.progress());

            if (DownloadStatus.PAUSED == item.status() || DownloadStatus.COMPLETED == item.status() || DownloadStatus.FAILED == item.status() || DownloadStatus.CANCELLED == item.status()) {
                updateToggleIcon(DownloadStatus.COMPLETED == item.status() ? "" : SVG_PLAY); // optionally hide if completed, or just show play
            } else {
                updateToggleIcon(SVG_PAUSE);
            }

            if (DownloadStatus.COMPLETED == item.status()) {
               progressBar.setProgress(1.0);
            }

            setTooltip(new javafx.scene.control.Tooltip(item.statusMessage()));
        }
    }

    private void handleEvent(final DownloadEvent event) {

        logger.info("handleEvent: {}", event);

        final String downloadId = switch (event) {
            case DownloadStartedEvent started -> started.info().downloadId();
            case DownloadProgressEvent progress -> progress.info().downloadId();
            case DownloadPausedEvent paused -> paused.info().downloadId();
            case DownloadCompletedEvent completed -> completed.info().downloadId();
            case DownloadFailedEvent failed -> failed.info().downloadId();
            case DownloadCancelledEvent cancelled -> cancelled.downloadInfo().downloadId();
            default -> null;
        };

        if (downloadId == null || downloadId.isBlank()) {
            return;
        }

        final DownloadRow row = findRow(downloadId);
        if (row == null) {
            return;
        }

        final DownloadStatus updatedStatus = resolveStatus(event, row.status());
        final String updatedMessage = buildStatusMessage(event, updatedStatus);
        final double updatedProgress = resolveProgress(event, row.progress());
        final Path updatedFinalPath = resolveFinalPath(event, row.finalPath());

        final DownloadRow updatedRow = new DownloadRow(
                row.downloadId(),
                row.sourceUrl(),
                row.fileName(),
                row.targetPath(),
                updatedFinalPath,
                updatedStatus,
                updatedMessage,
                updatedProgress
        );

        final int index = downloads.indexOf(row);
        if (index >= 0) {
            downloads.set(index, updatedRow);
        }
    }

    private DownloadRow findRow(final String downloadId) {
        for (final DownloadRow row : downloads) {
            if (row.downloadId().equals(downloadId)) {
                return row;
            }
        }
        return null;
    }
    private Path resolveFinalPath(final DownloadEvent event, final Path currentFinalPath) {
        return switch (event) {
            case DownloadCompletedEvent completed -> completed.finalPath();
            default -> currentFinalPath;
        };
    }


    private DownloadStatus resolveStatus(final DownloadEvent event, final DownloadStatus currentStatus) {
        return switch (event) {
            case DownloadCompletedEvent ignored -> DownloadStatus.COMPLETED;
            case DownloadFailedEvent ignored -> DownloadStatus.FAILED;
            case DownloadCancelledEvent ignored -> DownloadStatus.CANCELLED;
            case DownloadPausedEvent ignored -> DownloadStatus.PAUSED;
            case DownloadStartedEvent ignored -> DownloadStatus.IN_PROGRESS;
            case DownloadProgressEvent ignored -> DownloadStatus.IN_PROGRESS;
            default -> currentStatus;
        };
    }

    private double resolveProgress(final DownloadEvent event, final double currentProgress) {
        return switch (event) {
            case DownloadCancelledEvent cancelled -> clampProgress(cancelled.progress());
            case DownloadCompletedEvent ignored -> 1.0d;
            case DownloadPausedEvent paused -> progressFromBytes(paused.downloadedBytes(), paused.totalBytes(), currentProgress);
            case DownloadProgressEvent progress -> progressFromBytes(progress.downloadedBytes(), progress.totalBytes(), currentProgress);
            case DownloadStartedEvent ignored -> currentProgress;
            case DownloadFailedEvent ignored -> currentProgress;
            default -> currentProgress;
        };
    }

    private String buildStatusMessage(final DownloadEvent event, final DownloadStatus status) {
        return switch (event) {
            case DownloadStartedEvent ignored -> displayLabelForStatus(status);
            case DownloadProgressEvent progress -> progressMessage(
                    status,
                    progress.downloadedBytes(),
                    progress.totalBytes(),
                    null
            );
            case DownloadPausedEvent paused -> progressMessage(
                    status,
                    paused.downloadedBytes(),
                    paused.totalBytes(),
                    null
            );
            case DownloadCompletedEvent completed -> displayLabelForStatus(status)
                    + " • " + humanReadableBytes(completed.totalBytes());
            case DownloadFailedEvent failed -> {
                final String errorMessage = failed.errorMessage();
                if (errorMessage != null && !errorMessage.isBlank()) {
                    yield displayLabelForStatus(status) + " • " + errorMessage;
                }
                yield displayLabelForStatus(status);
            }
            case DownloadCancelledEvent cancelled -> progressMessage(
                    status,
                    cancelled.bytesRead(),
                    cancelled.totalBytes(),
                    null
            );
            default -> displayLabelForStatus(status);
        };
    }

    private double progressFromBytes(final long downloadedBytes, final long totalBytes, final double fallback) {
        if (totalBytes <= 0) {
            return fallback;
        }
        return clampProgress((double) downloadedBytes / totalBytes);
    }

    private String progressMessage(
            final DownloadStatus status,
            final long downloadedBytes,
            final long totalBytes,
            final Long bytesPerSecond
    ) {
        final StringBuilder messageBuilder = new StringBuilder(displayLabelForStatus(status));

        if (totalBytes > 0) {
            final double percentage = ((double) downloadedBytes / totalBytes) * 100.0d;
            messageBuilder.append(" • ")
                    .append(new DecimalFormat("0.0").format(percentage))
                    .append("% (")
                    .append(humanReadableBytes(downloadedBytes))
                    .append(" / ")
                    .append(humanReadableBytes(totalBytes))
                    .append(")");
        }

        if (bytesPerSecond != null && bytesPerSecond > 0) {
            messageBuilder.append(" • ")
                    .append(humanReadableBytes(bytesPerSecond))
                    .append("/s");
        }

        return messageBuilder.toString();
    }


    private static double clampProgress(final double progress) {
        if (Double.isNaN(progress)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, progress));
    }

    private static String humanReadableBytes(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }

        final String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unitIndex = -1;
        while (value >= 1024 && unitIndex < units.length - 1) {
            value /= 1024.0d;
            unitIndex++;
        }

        return new DecimalFormat("0.0").format(value) + " " + units[unitIndex];
    }
}
