package jsanca.katabasis.ui.app;

import jsanca.katabasis.core.api.manager.DownloadManager;
import jsanca.katabasis.core.api.manager.DownloadManagers;
import jsanca.katabasis.core.api.model.DownloadRequest;
import jsanca.katabasis.core.api.event.DownloadEvent;
import javafx.application.Platform;
import java.net.URI;
import java.nio.file.Path;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import java.io.File;

/**
 * Entry point for the Katabasis UI application.
 *
 * <p>This first UI iteration focuses on the shell of the application:
 * a list of downloads, clipboard-based URL intake, and a contextual menu
 * for future actions such as pause, resume, and cancel.
 *
 * @author jsanca
 */
public final class DownloadManagerApplication extends Application {

    private final ObservableList<DownloadRow> downloads = FXCollections.observableArrayList();
    private DownloadManager downloadManager;
    private Path downloadDirectory;

    /**
     * Launches the JavaFX application.
     *
     * @param args application arguments
     */
    public static void main(final String[] args) {
        launch(args);
    }

    /**
     * Initializes and displays the primary application window.
     *
     * @param primaryStage primary JavaFX stage
     */
    @Override
    public void start(final Stage primaryStage) {
        final Label titleLabel = new Label("Katabasis Download Manager");
        final Label subtitleLabel = new Label(
                "Ready. Copy a URL to the clipboard and click the button to add a download.");

        this.downloadDirectory = resolveDefaultDownloadDirectory();
        final Label downloadDirectoryLabel = new Label(
                "Download folder: " + this.downloadDirectory.toAbsolutePath());

        this.downloadManager = DownloadManagers.createDefault();

        this.downloadManager.addListener(event -> {
            Platform.runLater(() -> handleEvent(event));
        });

        final Button addFromClipboardButton = new Button("Add download from clipboard");
        final Button selectDownloadFolderButton = new Button("Select download folder");
        selectDownloadFolderButton.setOnAction(event -> chooseDownloadDirectory(primaryStage, downloadDirectoryLabel));

        final ListView<DownloadRow> downloadsListView = new ListView<>(downloads);
        downloadsListView.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        downloadsListView.setPlaceholder(new Label("No downloads yet."));
        downloadsListView.setCellFactory(listView -> new DownloadRowCell());

        addFromClipboardButton.setOnAction(event -> addDownloadFromClipboard(subtitleLabel));

        VBox.setVgrow(downloadsListView, Priority.ALWAYS);

        final VBox root = new VBox(
                12,
                titleLabel,
                subtitleLabel,
                downloadDirectoryLabel,
                selectDownloadFolderButton,
                addFromClipboardButton,
                downloadsListView
        );
        root.setPadding(new Insets(16));

        final Scene scene = new Scene(root, 860, 480);

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

        try {
            final DownloadRequest request = new DownloadRequest(
                    URI.create(url),
                    buildTargetPath(url)
            );

            final var result = this.downloadManager.download(request);

            final DownloadRow row = new DownloadRow(url, "PENDING", "Submitted to manager");
            downloads.addFirst(row);

            subtitleLabel.setText("Download submitted: " + abbreviate(url, 90));

        } catch (Exception e) {
            subtitleLabel.setText("Invalid URL: " + e.getMessage());
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
        downloadDirectoryLabel.setText("Download folder: " + this.downloadDirectory.toAbsolutePath());
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

    private static Color colorForStatus(final String status) {
        return switch (status) {
            case "COMPLETED" -> Color.DARKGREEN;
            case "FAILED", "CANCELLED" -> Color.FIREBRICK;
            case "PAUSED" -> Color.DARKORANGE;
            case "IN_PROGRESS" -> Color.DODGERBLUE;
            default -> Color.DIMGRAY;
        };
    }

    private static final class DownloadRow {

        private final String sourceUrl;
        private String status;
        private String statusMessage;

        private DownloadRow(final String sourceUrl, final String status, final String statusMessage) {
            this.sourceUrl = sourceUrl;
            this.status = status;
            this.statusMessage = statusMessage;
        }
    }

    private static final class DownloadRowCell extends ListCell<DownloadRow> {

        private final Label urlLabel = new Label();
        private final Label statusLabel = new Label();
        private final VBox content = new VBox(4, urlLabel, statusLabel);

        private DownloadRowCell() {
            final MenuItem pauseResumeItem = new MenuItem("Pause / Resume");
            final MenuItem cancelItem = new MenuItem("Cancel");
            final ContextMenu contextMenu = new ContextMenu(pauseResumeItem, cancelItem);

            pauseResumeItem.setOnAction(event -> {
                final DownloadRow item = getItem();
                if (item == null) {
                    return;
                }
                item.statusMessage = "Pause / Resume action requested. Core action wiring pending.";
                refreshCell(item);
            });

            cancelItem.setOnAction(event -> {
                final DownloadRow item = getItem();
                if (item == null) {
                    return;
                }
                item.status = "CANCELLED";
                item.statusMessage = "Cancel action requested. Core action wiring pending.";
                refreshCell(item);
            });

            setContextMenu(contextMenu);
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
            setGraphic(content);
        }

        private void refreshCell(final DownloadRow item) {
            urlLabel.setText(item.sourceUrl);
            statusLabel.setText("Status: " + item.status);
            statusLabel.setTextFill(colorForStatus(item.status));
            setTooltip(new javafx.scene.control.Tooltip(item.statusMessage));
        }
    }

    private void handleEvent(final DownloadEvent event) {
        // naive implementation for now: update first row
        if (downloads.isEmpty()) {
            return;
        }

        final DownloadRow row = downloads.get(0);

        row.status = event.getClass().getSimpleName();
        row.statusMessage = event.toString();
    }
}