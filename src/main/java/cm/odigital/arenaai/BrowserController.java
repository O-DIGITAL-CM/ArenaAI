package cm.odigital.arenaai;

import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.concurrent.Worker;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.PromptData;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebHistory;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.awt.Desktop;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import javax.net.ssl.SSLHandshakeException;

/**
 * Controls the main window: a slim desktop browser chrome (back / forward /
 * reload / home + address pill + log out + logs) wrapped around the preloaded
 * Arena page. Navigations, load failures, engine errors, popup decisions and
 * detected sign-in problems are all recorded in {@link AppLog}.
 */
public class BrowserController implements Initializable {

    @FXML
    private BorderPane root;
    @FXML
    private Button backButton;
    @FXML
    private Button forwardButton;
    @FXML
    private Button reloadButton;
    @FXML
    private Button homeButton;
    @FXML
    private Button externalButton;
    @FXML
    private Button clearDataButton;
    @FXML
    private Button logsButton;
    @FXML
    private Button updateButton;
    @FXML
    private Label addressPill;
    @FXML
    private ProgressBar loadProgress;
    @FXML
    private StackPane webContainer;
    @FXML
    private StackPane errorOverlay;
    @FXML
    private Label errorDetail;
    @FXML
    private Button retryButton;
    @FXML
    private Button openExternalButton;

    private WebView webView;
    private WebEngine engine;
    private WebHistory history;
    private Tooltip addressTooltip;
    private UpdateManager updateManager;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadProgress.setProgress(0);
        loadProgress.setVisible(false);
        errorOverlay.setVisible(false);
        addressPill.setText(ArenaConfig.HOME_HOST);

        retryButton.setOnAction(e -> {
            hideError();
            if (engine != null) {
                AppLog.info("Retrying: " + safeBrowserUrl(engine.getLocation()));
                engine.reload();
            }
        });
        openExternalButton.setOnAction(e -> openInSystemBrowser(currentLocation()));
    }

    /**
     * Attaches the WebView that was preloaded behind the splash screen, so the
     * website appears instantly without loading a second time.
     */
    public void setWebView(WebView webView) {
        this.webView = webView;
        this.engine = webView.getEngine();
        this.history = engine.getHistory();

        AppLog.info("Main window attached to preloaded page: " + safeBrowserUrl(engine.getLocation()));
        JsConsoleBridge.install(engine);
        AuthErrorWatcher.watch(engine, this::onAuthErrorDetected);
        CloudflareChallengeWatcher.watch(engine,
                detail -> onCloudflareChallengeDetected(engine, detail));

        // Index 0 keeps the error overlay (declared in FXML) on top.
        webContainer.getChildren().add(0, webView);

        addressTooltip = new Tooltip(safeBrowserUrl(engine.getLocation()));
        engine.locationProperty().addListener((obs, oldLocation, newLocation) ->
                addressTooltip.setText(safeBrowserUrl(newLocation)));
        Tooltip.install(addressPill, addressTooltip);
        addressPill.setOnMouseClicked(e -> copyCurrentUrlToClipboard());

        wireEngine();
        wireToolbar();
        wireShortcuts();
        wireHistoryButtons();
        updateManager = new UpdateManager(updateButton, root, ArenaConfig.APP_VERSION);
        updateButton.setOnAction(e -> updateManager.checkForUpdatesManually());
        updateManager.scheduleAutomaticCheck();

        // The page may already have settled while the splash was showing.
        reflectWorkerState(engine.getLoadWorker().getState());
        updateAddressPill(engine.getLocation());
        updateWindowTitle(engine.getTitle());
    }

    // --- Engine wiring ---

    private void wireEngine() {
        Worker<Void> worker = engine.getLoadWorker();

        loadProgress.progressProperty().bind(worker.progressProperty());
        loadProgress.visibleProperty().bind(worker.runningProperty());

        worker.stateProperty().addListener((obs, oldState, newState) -> reflectWorkerState(newState));

        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            AppLog.info("Navigated to: " + safeBrowserUrl(newLoc));
            updateAddressPill(newLoc);
        });
        engine.titleProperty().addListener((obs, oldTitle, newTitle) -> {
            AppLog.fine("Page title: " + newTitle);
            updateWindowTitle(newTitle);
        });

        // Popup handling: sign-in flows stay in-app (dedicated window sharing
        // the app's cookies), everything else opens in the system browser.
        engine.setCreatePopupHandler(features -> {
            WebView popupView = new WebView();
            popupView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            WebEngine popupEngine = popupView.getEngine();
            AppLog.fine("Popup requested by page; waiting for its URL…");
            popupEngine.locationProperty().addListener(new ChangeListener<>() {
                @Override
                public void changed(ObservableValue<? extends String> obs, String oldLoc, String newLoc) {
                    if (newLoc != null && !newLoc.isBlank() && !"about:blank".equals(newLoc)) {
                        obs.removeListener(this);
                        handlePopup(popupView, newLoc);
                    }
                }
            });
            return popupEngine;
        });

        // JavaScript dialogs → native JavaFX dialogs.
        engine.setOnAlert(event -> {
            AppLog.fine("JS alert shown: " + event.getData());
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(ArenaConfig.APP_TITLE);
            alert.setHeaderText("arena.ai says:");
            alert.setContentText(event.getData());
            alert.showAndWait();
        });
        engine.setConfirmHandler(message -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
            alert.setTitle(ArenaConfig.APP_TITLE);
            alert.setHeaderText("arena.ai asks:");
            Optional<ButtonType> result = alert.showAndWait();
            boolean confirmed = result.isPresent() && result.get() == ButtonType.OK;
            AppLog.fine("JS confirm: \"" + message + "\" -> " + (confirmed ? "OK" : "Cancel"));
            return confirmed;
        });
        engine.setPromptHandler((PromptData data) -> {
            TextInputDialog dialog = new TextInputDialog(data.getDefaultValue());
            dialog.setTitle(ArenaConfig.APP_TITLE);
            dialog.setHeaderText(data.getMessage());
            String answer = dialog.showAndWait().orElse(null);
            AppLog.fine("JS prompt: \"" + data.getMessage() + "\" -> "
                    + (answer != null ? "answered" : "cancelled"));
            return answer;
        });

        engine.setOnError(event -> AppLog.severe("[WebView engine error] " + event.getMessage()
                + " (url: " + safeBrowserUrl(engine.getLocation()) + ")", event.getException()));
    }

    private void reflectWorkerState(Worker.State state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case RUNNING, SCHEDULED -> hideError();
            case SUCCEEDED -> {
                hideError();
                AppLog.info("Loaded: " + safeBrowserUrl(engine.getLocation()));
                updateWindowTitle(engine.getTitle());
            }
            case FAILED -> {
                Throwable ex = engine.getLoadWorker().getException();
                AppLog.severe("Page load failed: " + safeBrowserUrl(engine.getLocation()), ex);
                showError(messageOf(ex));
            }
            case CANCELLED -> {
                // User navigated away mid-load; only show the overlay if nothing loaded.
                if (engine.getLocation() == null || engine.getLocation().isBlank()) {
                    AppLog.warning("Navigation cancelled before anything loaded.");
                    showError("Navigation was cancelled before anything loaded.");
                } else {
                    AppLog.fine("Load cancelled for: " + safeBrowserUrl(engine.getLocation()));
                }
            }
            default -> {
                // READY — nothing to do yet.
            }
        }
    }

    private static String messageOf(Throwable ex) {
        if (isSslHandshakeFailure(ex)) {
            return "The secure connection to Arena failed during TLS negotiation. Check your PC's date and time "
                    + "and install pending Windows updates, then retry. A VPN, proxy, or HTTPS-inspecting network "
                    + "security product can also cause this; try a trusted alternate network or ask its administrator "
                    + "about the certificate. If Arena works in Chrome or Edge but not here, open Logs and share the "
                    + "SSL error details.";
        }
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "The page could not be loaded. Check your internet connection and retry.";
        }
        return "The page could not be loaded (" + ex.getMessage() + "). Check your internet connection and retry.";
    }

    private static boolean isSslHandshakeFailure(Throwable ex) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (current instanceof SSLHandshakeException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("ssl handshake failed")) {
                return true;
            }
        }
        return false;
    }

    // --- Popups ---

    private void handlePopup(WebView popupView, String url) {
        String host = hostOf(url);
        if (ArenaConfig.OPEN_AUTH_POPUPS_IN_APP && ArenaConfig.isAuthPopupHost(host)) {
            AppLog.info("Popup (sign-in) kept in-app: " + url);
            JsConsoleBridge.install(popupView.getEngine());
            CloudflareChallengeWatcher.watch(popupView.getEngine(),
                    detail -> onCloudflareChallengeDetected(popupView.getEngine(), detail));
            Window owner = root.getScene() != null ? root.getScene().getWindow() : null;
            AuthPopupDialog.show(popupView, url, owner, this::offerReloadIfStillSignedOut);
        } else {
            AppLog.info("Popup opened in system browser: " + url);
            openInSystemBrowser(url);
        }
    }

    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    // --- Sign-in and browser-challenge recovery ---

    /**
     * Cloudflare's challenge can render in JavaFX WebView but fail to complete
     * because this embedded WebKit engine is not a full Chrome/Edge browser.
     * Offer a real-browser fallback rather than pretending the checkbox worked.
     */
    private void onCloudflareChallengeDetected(WebEngine challengedEngine, String detail) {
        String challengedUrl = safeBrowserUrl(challengedEngine.getLocation());
        AppLog.warning("Cloudflare verification detected in embedded WebView at "
                + challengedUrl + " — " + detail);

        // Let the splash-to-main transition finish before showing a modal.
        PauseTransition defer = new PauseTransition(Duration.millis(350));
        defer.setOnFinished(e -> {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(ArenaConfig.APP_TITLE);
            alert.setHeaderText("Cloudflare check is not completing in the app");
            alert.setContentText("The embedded JavaFX WebView reported a Cloudflare challenge or block page. "
                    + "Reloading here may repeat the check.\n\n"
                    + "Continue in Chrome or Edge using the system browser. Verification completed there is stored "
                    + "separately and cannot be transferred into the app.");

            ButtonType openBrowser = new ButtonType("Open in browser", ButtonBar.ButtonData.OK_DONE);
            ButtonType dismiss = new ButtonType("Dismiss", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(openBrowser, dismiss);

            Window owner = root.getScene() == null ? null : root.getScene().getWindow();
            if (owner != null) {
                alert.initOwner(owner);
            }

            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == openBrowser) {
                openInSystemBrowser(challengedUrl);
            }
        });
        defer.play();
    }

    /** A page explicitly reports a sign-in failure: log it loudly and guide the user. */
    private void onAuthErrorDetected(String detail) {
        AppLog.severe("Sign-in issue detected on " + safeBrowserUrl(engine.getLocation()) + " — " + detail);
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(ArenaConfig.APP_TITLE);
        alert.setHeaderText("The page reports a sign-in error");
        alert.setContentText(detail
                + "\n\n• Complete the Google sign-in inside the app's sign-in window, not your system browser."
                + "\n• After signing in, close the sign-in window."
                + "\n• Still stuck? Open Logs and look for [JS error] lines.");
        ButtonType openLogs = new ButtonType("Open Logs");
        ButtonType dismiss = new ButtonType("Dismiss", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(openLogs, dismiss);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isPresent() && choice.get() == openLogs) {
            openLogs();
        }
    }

    /**
     * After the sign-in window closes, offer a reload — but only if the page
     * still reports a problem, so unsent chat drafts are never wiped needlessly.
     */
    private void offerReloadIfStillSignedOut() {
        PauseTransition settle = new PauseTransition(Duration.seconds(1.5));
        settle.setOnFinished(e -> {
            String match = AuthErrorWatcher.detect(engine);
            if (match == null) {
                AppLog.info("Sign-in window closed; main page reports no auth errors.");
                return;
            }
            AppLog.info("Main page still reports a sign-in issue after the window closed; offering reload.");
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(ArenaConfig.APP_TITLE);
            confirm.setHeaderText("Still looks signed out");
            confirm.setContentText("The page still reports a sign-in problem. Reload it to pick up the session?"
                    + "\n\n(" + match + ")");
            ButtonType reload = new ButtonType("Reload page");
            ButtonType later = new ButtonType("Not now", ButtonBar.ButtonData.CANCEL_CLOSE);
            confirm.getButtonTypes().setAll(reload, later);
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isPresent() && choice.get() == reload) {
                AppLog.info("Reloading after sign-in window closed: " + safeBrowserUrl(engine.getLocation()));
                engine.reload();
            }
        });
        settle.play();
    }

    // --- Toolbar / navigation ---

    private void wireToolbar() {
        backButton.setOnAction(e -> go(-1));
        forwardButton.setOnAction(e -> go(1));
        reloadButton.setOnAction(e -> {
            AppLog.info("Reload: " + safeBrowserUrl(engine.getLocation()));
            engine.reload();
        });
        homeButton.setOnAction(e -> {
            AppLog.info("Home: " + ArenaConfig.HOME_URL);
            engine.load(ArenaConfig.HOME_URL);
        });
        externalButton.setOnAction(e -> openInSystemBrowser(currentLocation()));
        clearDataButton.setOnAction(e -> clearBrowsingData());
        logsButton.setOnAction(e -> openLogs());
    }

    private void wireHistoryButtons() {
        history.currentIndexProperty().addListener((obs, oldIndex, newIndex) -> updateNavButtons());
        history.getEntries().addListener((ListChangeListener<WebHistory.Entry>) c -> updateNavButtons());
        updateNavButtons();
    }

    private void go(int offset) {
        try {
            int target = history.getCurrentIndex() + offset;
            if (target >= 0 && target < history.getEntries().size()) {
                history.go(offset);
            }
        } catch (IndexOutOfBoundsException ignored) {
            // Already at the edge of the history — nothing to do.
        }
    }

    private void updateNavButtons() {
        int index = history.getCurrentIndex();
        int size = history.getEntries().size();
        backButton.setDisable(index <= 0);
        forwardButton.setDisable(index < 0 || index >= size - 1);
    }

    private void wireShortcuts() {
        root.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene == null) {
                return;
            }
            newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN), engine::reload);
            newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.F5), engine::reload);
            newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.LEFT, KeyCombination.ALT_DOWN), () -> go(-1));
            newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.RIGHT, KeyCombination.ALT_DOWN), () -> go(1));
            newScene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.HOME, KeyCombination.ALT_DOWN),
                    () -> engine.load(ArenaConfig.HOME_URL));
        });
    }

    // --- Address pill / window title ---

    private void updateAddressPill(String location) {
        String host = ArenaConfig.HOME_HOST;
        if (location != null && !location.isBlank()) {
            try {
                String parsed = new URI(location).getHost();
                if (parsed != null && !parsed.isBlank()) {
                    host = parsed;
                }
            } catch (Exception ignored) {
                // Keep the fallback host.
            }
        }
        boolean secure = location != null && location.startsWith("https");
        addressPill.setText((secure ? "🔒 " : "") + host);
    }

    private void copyCurrentUrlToClipboard() {
        String urlToCopy = safeBrowserUrl(currentLocation());
        ClipboardContent content = new ClipboardContent();
        content.putString(urlToCopy);
        Clipboard.getSystemClipboard().setContent(content);
        AppLog.fine("Copied page URL to clipboard: " + urlToCopy);

        String original = addressPill.getText();
        addressPill.setText("✓ Link copied");
        PauseTransition restore = new PauseTransition(Duration.seconds(1.2));
        restore.setOnFinished(e -> addressPill.setText(original));
        restore.play();
    }

    private void updateWindowTitle(String pageTitle) {
        String title = (pageTitle == null || pageTitle.isBlank())
                ? ArenaConfig.APP_TITLE
                : pageTitle + " — " + ArenaConfig.APP_TITLE;
        if (root.getScene() != null && root.getScene().getWindow() instanceof Stage stage) {
            stage.setTitle(title);
        }
    }

    // --- Clear browsing data ---

    /**
     * Logs out everywhere: clears persisted cookies (+ current site storage)
     * after a confirmation, then returns home.
     */
    private void clearBrowsingData() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(ArenaConfig.APP_TITLE);
        confirm.setHeaderText("Log out and clear browsing data?");
        confirm.setContentText("This clears cookies and site data, logging you out of arena.ai.");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }
        CookiePersistence.clearAll();
        try {
            engine.executeScript("try { localStorage.clear(); sessionStorage.clear(); } catch (e) {}");
        } catch (Exception ignored) {
            // Storage may be unavailable on some pages — cookies are the important part.
        }
        AppLog.info("Browsing data cleared by user; returning home.");
        engine.load(ArenaConfig.HOME_URL);
    }

    // --- Logs ---

    /** Opens the current log file (or folder) so errors can actually be seen. */
    private void openLogs() {
        Path file = AppLog.getLogFile();
        Path dir = AppLog.getLogDir();
        AppLog.info("Opening logs (file: " + file + ", dir: " + dir + ")");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                if (file != null && Files.isRegularFile(file)) {
                    Desktop.getDesktop().open(file.toFile());
                    return;
                }
                if (dir != null && Files.isDirectory(dir)) {
                    Desktop.getDesktop().open(dir.toFile());
                    return;
                }
            }
        } catch (Exception ex) {
            AppLog.warning("Could not open log file", ex);
        }
        String location = file != null ? file.toString() : (dir != null ? dir.toString() : "unavailable");
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(location);
            Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception ignored) {
            // Clipboard is best-effort here.
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(ArenaConfig.APP_TITLE);
        alert.setHeaderText("Log location (path copied)");
        alert.setContentText(location);
        alert.showAndWait();
    }

    // --- Error overlay ---

    private void showError(String detail) {
        errorDetail.setText(detail);
        errorOverlay.setVisible(true);
        errorOverlay.toFront();
    }

    private void hideError() {
        errorOverlay.setVisible(false);
    }

    // --- Helpers ---

    private String currentLocation() {
        if (engine != null && engine.getLocation() != null && !engine.getLocation().isBlank()) {
            return engine.getLocation();
        }
        return ArenaConfig.HOME_URL;
    }

    /** Removes one-time Cloudflare challenge tokens before opening or logging a URL. */
    private static String safeBrowserUrl(String url) {
        if (url == null || url.isBlank()) {
            return ArenaConfig.HOME_URL;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("__cf_chl_") && !lower.contains("/cdn-cgi/challenge-platform/")) {
            return url;
        }
        try {
            return new URI(url).resolve("/").toString();
        } catch (Exception ignored) {
            return ArenaConfig.HOME_URL;
        }
    }

    private void openInSystemBrowser(String url) {
        String safeUrl = safeBrowserUrl(url);
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                AppLog.info("Opened in system browser: " + safeUrl);
                Desktop.getDesktop().browse(new URI(safeUrl));
                return;
            }
        } catch (Exception ex) {
            AppLog.warning("Could not open system browser for " + safeUrl, ex);
        }
        // Fallback: copy the sanitized link so the user can paste it into a browser.
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(safeUrl);
            Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception ignored) {
            // Nothing more we can do.
        }
    }
}
