package cm.odigital.arenaai;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * Runs the GitHub Releases update check and presents the verified installer
 * flow to the user. All network operations happen off the JavaFX thread.
 */
public final class UpdateManager {

    private static final String DISMISSED_VERSION_KEY = "update.dismissed-version";
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(UpdateManager.class);

    private final Button updateButton;
    private final Parent ownerNode;
    private final String currentVersion;

    private boolean checking;
    private UpdateService.Release availableRelease;

    public UpdateManager(Button updateButton, Parent ownerNode, String currentVersion) {
        this.updateButton = updateButton;
        this.ownerNode = ownerNode;
        this.currentVersion = currentVersion;
    }

    /** Starts a quiet update check shortly after the main window is shown. */
    public void scheduleAutomaticCheck() {
        PauseTransition delay = new PauseTransition(Duration.seconds(2));
        delay.setOnFinished(e -> checkForUpdates(true));
        delay.play();
    }

    /** Called by the toolbar's Update button. */
    public void checkForUpdatesManually() {
        if (availableRelease != null) {
            showUpdateAvailable(availableRelease, false);
        } else {
            checkForUpdates(false);
        }
    }

    private void checkForUpdates(boolean automatic) {
        if (automatic && !isOwnerShowing()) {
            return;
        }
        if (checking) {
            return;
        }
        if (!UpdateService.isSupportedPlatform()) {
            if (!automatic) {
                showMessage(Alert.AlertType.INFORMATION, "Windows updater",
                        "In-app installer updates are currently supported on Windows only. "
                                + "You can view releases on the Arena GitHub page.");
            }
            return;
        }

        checking = true;
        updateButton.setDisable(true);
        updateButton.setText("Checking…");

        Task<Optional<UpdateService.Release>> task = new Task<>() {
            @Override
            protected Optional<UpdateService.Release> call() throws Exception {
                return UpdateService.checkForUpdate(currentVersion);
            }
        };
        task.setOnSucceeded(event -> {
            checking = false;
            updateButton.setDisable(false);
            updateButton.setText("Update");
            Optional<UpdateService.Release> result = task.getValue();
            if (result.isEmpty()) {
                availableRelease = null;
                AppLog.info("Update check complete; Arena " + currentVersion + " is up to date.");
                if (!automatic) {
                    showMessage(Alert.AlertType.INFORMATION, "Arena is up to date",
                            "You are running the latest available version (" + currentVersion + ").");
                }
                return;
            }

            availableRelease = result.get();
            AppLog.info("Arena update available: " + currentVersion + " -> " + availableRelease.version());
            if (automatic && isDismissed(availableRelease.version())) {
                return;
            }
            showUpdateAvailable(availableRelease, automatic);
        });
        task.setOnFailed(event -> {
            checking = false;
            updateButton.setDisable(false);
            updateButton.setText("Update");
            Throwable error = task.getException();
            AppLog.fine("Could not check for updates: " + (error == null ? "unknown error" : error));
            if (!automatic) {
                String message = error == null || error.getMessage() == null
                        ? "GitHub could not be reached. Check your internet connection and try again."
                        : error.getMessage();
                showMessage(Alert.AlertType.WARNING, "Update check failed", message);
            }
        });

        Thread worker = new Thread(task, "arena-update-check");
        worker.setDaemon(true);
        worker.start();
    }

    private void showUpdateAvailable(UpdateService.Release release, boolean automatic) {
        if (automatic && !isOwnerShowing()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Arena Updater");
        alert.setHeaderText("Arena " + release.version() + " is available");
        alert.setContentText("Current version: " + currentVersion
                + "\nNew version: " + release.version()
                + "\n\nThe updater downloads the official Windows installer from GitHub and checks "
                + "its SHA-256 checksum before launching it. Arena will close while the installer runs.\n\n"
                + "If you use the portable app-image, the installer creates/updates a normal Windows installation; "
                + "it does not replace the portable folder.");
        ButtonType install = new ButtonType("Download and install", ButtonBar.ButtonData.OK_DONE);
        ButtonType releasePage = new ButtonType("View GitHub release", ButtonBar.ButtonData.OTHER);
        ButtonType later = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(install, releasePage, later);
        setOwner(alert);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == install) {
            downloadAndInstall(release);
        } else if (choice.isPresent() && choice.get() == releasePage) {
            openReleasePage(release.releasePageUri());
        } else {
            rememberDismissed(release.version());
            if (automatic) {
                AppLog.info("User deferred Arena update " + release.version() + ".");
            }
        }
    }

    private void downloadAndInstall(UpdateService.Release release) {
        Label status = new Label("Connecting to GitHub…");
        ProgressBar progress = new ProgressBar(-1);
        progress.setMaxWidth(Double.MAX_VALUE);

        VBox content = new VBox(10, status, progress);
        content.setPadding(new Insets(4, 0, 0, 0));

        Alert dialog = new Alert(Alert.AlertType.INFORMATION);
        dialog.setTitle("Arena Updater");
        dialog.setHeaderText("Downloading Arena " + release.version());
        dialog.getDialogPane().setContent(content);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL);
        setOwner(dialog);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                updateMessage("Downloading the GitHub release asset…");
                Path installer = UpdateService.downloadAndVerify(release, (downloaded, total) -> {
                    if (total > 0) {
                        updateProgress(downloaded, total);
                        updateMessage("Downloaded " + formatBytes(downloaded) + " of " + formatBytes(total));
                    } else {
                        updateProgress(-1, 1);
                        updateMessage("Downloaded " + formatBytes(downloaded));
                    }
                });
                updateMessage("SHA-256 verified. Starting the Windows installer…");
                return installer;
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        status.textProperty().bind(task.messageProperty());

        dialog.setOnCloseRequest(event -> {
            if (task.isRunning()) {
                task.cancel(true);
            }
        });
        task.setOnSucceeded(event -> {
            dialog.close();
            launchInstaller(task.getValue(), release);
        });
        task.setOnFailed(event -> {
            dialog.close();
            updateButton.setDisable(false);
            Throwable error = task.getException();
            AppLog.warning("Arena update download or checksum verification failed.", error);
            String detail = error == null || error.getMessage() == null
                    ? "The installer could not be downloaded or verified."
                    : error.getMessage();
            showMessage(Alert.AlertType.ERROR, "Update could not be installed", detail);
        });
        task.setOnCancelled(event -> {
            dialog.close();
            updateButton.setDisable(false);
            AppLog.info("Arena update download cancelled.");
        });

        updateButton.setDisable(true);
        Thread worker = new Thread(task, "arena-update-download");
        worker.setDaemon(true);
        worker.start();
        dialog.show();
    }

    private void launchInstaller(Path installer, UpdateService.Release release) {
        try {
            new ProcessBuilder(installer.toAbsolutePath().toString()).start();
            AppLog.info("Started SHA-256-verified GitHub installer for Arena " + release.version());
            Platform.exit();
        } catch (IOException e) {
            AppLog.warning("Could not start the Arena update installer at " + installer, e);
            updateButton.setDisable(false);
            showMessage(Alert.AlertType.ERROR, "Could not start the installer",
                    "The downloaded installer passed SHA-256 verification but Windows could not launch it.\n\n"
                            + installer + "\n\n" + e.getMessage());
        }
    }

    private void openReleasePage(URI uri) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return;
            }
        } catch (Exception e) {
            AppLog.warning("Could not open GitHub release page: " + uri, e);
        }
        showMessage(Alert.AlertType.INFORMATION, "GitHub release", uri.toString());
    }

    private void showMessage(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle("Arena Updater");
        alert.setHeaderText(title);
        alert.setContentText(message);
        setOwner(alert);
        alert.showAndWait();
    }

    private boolean isOwnerShowing() {
        if (ownerNode.getScene() == null) {
            return false;
        }
        Window owner = ownerNode.getScene().getWindow();
        return owner != null && owner.isShowing();
    }

    private void setOwner(Alert alert) {
        if (ownerNode.getScene() != null) {
            Window owner = ownerNode.getScene().getWindow();
            if (owner != null) {
                alert.initOwner(owner);
            }
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static boolean isDismissed(String version) {
        try {
            return version.equals(PREFERENCES.get(DISMISSED_VERSION_KEY, ""));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void rememberDismissed(String version) {
        try {
            PREFERENCES.put(DISMISSED_VERSION_KEY, version);
        } catch (RuntimeException e) {
            AppLog.fine("Could not remember deferred update version: " + e.getMessage());
        }
    }
}
