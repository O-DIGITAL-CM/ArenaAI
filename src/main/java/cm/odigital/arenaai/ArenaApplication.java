package cm.odigital.arenaai;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.Preferences;

/**
 * Entry point (JavaFX Application) for the Arena desktop app.
 *
 * <p>Launch flow, Discord-style:</p>
 * <ol>
 *   <li>The website starts loading immediately in a hidden {@link WebView}.</li>
 *   <li>A borderless splash screen shows loading status + tips.</li>
 *   <li>Once the splash has been visible long enough <b>and</b> the page has
 *       settled (loaded or failed), the splash fades out and the main browser
 *       window appears — already on the website, no second load.</li>
 * </ol>
 */
public class ArenaApplication extends Application {

    private static final Preferences PREFS = Preferences.userNodeForPackage(ArenaApplication.class);

    private WebView preloadedWebView;
    private PauseTransition minWait;
    private PauseTransition maxWait;

    @Override
    public void start(Stage primaryStage) throws Exception {
        Runtime runtime = Runtime.getRuntime();
        long maxHeapMiB = runtime.maxMemory() / (1024L * 1024L);
        AppLog.info("JVM resource limits: active processors=" + runtime.availableProcessors()
                + ", max Java heap=" + maxHeapMiB + " MiB");

        // 0 — File-backed cookies (stay logged in). Must precede any WebView creation.
        CookiePersistence.install();

        // 1 — Start loading the website right away (on the FX thread, hidden).
        AppLog.info("Preloading " + ArenaConfig.HOME_URL + " behind splash…");
        preloadedWebView = WebViewFactory.createConfiguredWebView();
        WebEngine engine = preloadedWebView.getEngine();
        engine.load(ArenaConfig.HOME_URL);

        // 2 — Show the Discord-style splash screen.
        FXMLLoader splashLoader = new FXMLLoader(
                Objects.requireNonNull(getClass().getResource("splash-view.fxml"), "splash-view.fxml not found"));
        Parent splashRoot = splashLoader.load();
        SplashController splash = splashLoader.getController();
        splash.bindTo(engine);

        Scene splashScene = new Scene(splashRoot);
        splashScene.setFill(Color.TRANSPARENT);
        splashScene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("css/splash.css"), "css/splash.css not found")
                        .toExternalForm());

        Stage splashStage = new Stage(StageStyle.TRANSPARENT);
        splashStage.setTitle(ArenaConfig.APP_TITLE);
        splashStage.setScene(splashScene);
        splashStage.show();
        splashStage.centerOnScreen();
        splash.playShow();
        AppLog.info("Splash screen shown.");

        // 3 — Transition once BOTH conditions hold: min splash time elapsed
        //     AND page load settled (success or failure). A max timeout
        //     guarantees we never get stuck on the splash.
        Worker<Void> preloadWorker = engine.getLoadWorker();
        AtomicBoolean minElapsed = new AtomicBoolean(false);
        AtomicBoolean engineSettled = new AtomicBoolean(false);
        AtomicBoolean transitioned = new AtomicBoolean(false);

        Runnable tryTransition = () -> {
            if (minElapsed.get() && engineSettled.get() && transitioned.compareAndSet(false, true)) {
                Platform.runLater(() -> showMainWindow(primaryStage, splash, splashStage));
            }
        };

        minWait = new PauseTransition(Duration.millis(ArenaConfig.SPLASH_MIN_VISIBLE_MS));
        minWait.setOnFinished(e -> {
            minElapsed.set(true);
            tryTransition.run();
        });
        minWait.play();

        maxWait = new PauseTransition(Duration.millis(ArenaConfig.SPLASH_MAX_WAIT_MS));
        maxWait.setOnFinished(e -> {
            AppLog.warning("Splash max wait (" + ArenaConfig.SPLASH_MAX_WAIT_MS
                    + "ms) reached; showing main window anyway. Page state: "
                    + preloadWorker.getState() + ", url: " + engine.getLocation());
            minElapsed.set(true);
            engineSettled.set(true);
            tryTransition.run();
        });
        maxWait.play();

        preloadWorker.stateProperty().addListener((obs, oldState, newState) -> {
            if (isSettled(newState)) {
                engineSettled.set(true);
                tryTransition.run();
            }
        });
        if (isSettled(preloadWorker.getState())) {
            engineSettled.set(true);
        }
    }

    private static boolean isSettled(Worker.State state) {
        return state == Worker.State.SUCCEEDED
                || state == Worker.State.FAILED
                || state == Worker.State.CANCELLED;
    }

    /**
     * Builds the main browser window around the preloaded WebView, fades the
     * splash out, then shows the main stage.
     */
    private void showMainWindow(Stage primaryStage, SplashController splash, Stage splashStage) {
        if (minWait != null) {
            minWait.stop();
        }
        if (maxWait != null) {
            maxWait.stop();
        }
        AppLog.info("Splash complete; showing main window. Page state: "
                + preloadedWebView.getEngine().getLoadWorker().getState()
                + ", url: " + preloadedWebView.getEngine().getLocation());
        try {
            FXMLLoader loader = new FXMLLoader(
                    Objects.requireNonNull(getClass().getResource("browser-view.fxml"), "browser-view.fxml not found"));
            Parent root = loader.load();
            BrowserController controller = loader.getController();

            Scene scene = new Scene(root, restoredWidth(), restoredHeight());
            scene.getStylesheets().add(
                    Objects.requireNonNull(getClass().getResource("css/browser.css"), "css/browser.css not found")
                            .toExternalForm());

            primaryStage.setTitle(ArenaConfig.APP_TITLE);
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(ArenaConfig.WINDOW_MIN_WIDTH);
            primaryStage.setMinHeight(ArenaConfig.WINDOW_MIN_HEIGHT);
            primaryStage.setMaximized(isMaximizedRestored());
            primaryStage.setOnCloseRequest(e -> saveWindowBounds(primaryStage));
            installAppIcon(primaryStage);

            // Attach the already-loading/loaded page — no second load.
            controller.setWebView(preloadedWebView);

            splash.playHide(() -> {
                splashStage.close();
                primaryStage.show();
                if (!primaryStage.isMaximized()) {
                    primaryStage.centerOnScreen();
                }
                root.setOpacity(0);
                FadeTransition fadeIn = new FadeTransition(Duration.millis(250), root);
                fadeIn.setFromValue(0);
                fadeIn.setToValue(1);
                fadeIn.play();
            });
        } catch (Exception ex) {
            AppLog.severe("Failed to build main window", ex);
            splashStage.close();
            Platform.exit();
        }
    }

    /** Window icon — silently skipped if the PNG is missing. */
    private void installAppIcon(Stage stage) {
        try (InputStream icon = getClass().getResourceAsStream("images/arena-logo.png")) {
            if (icon != null) {
                stage.getIcons().add(new Image(icon));
            }
        } catch (Exception ignored) {
            // Icon is optional; the OS default is used when absent.
        }
    }

    // --- Window size persistence (best effort) ---

    private static double restoredWidth() {
        try {
            return PREFS.getDouble("window.width", ArenaConfig.WINDOW_WIDTH);
        } catch (Exception e) {
            return ArenaConfig.WINDOW_WIDTH;
        }
    }

    private static double restoredHeight() {
        try {
            return PREFS.getDouble("window.height", ArenaConfig.WINDOW_HEIGHT);
        } catch (Exception e) {
            return ArenaConfig.WINDOW_HEIGHT;
        }
    }

    private static boolean isMaximizedRestored() {
        try {
            return PREFS.getBoolean("window.maximized", false);
        } catch (Exception e) {
            return false;
        }
    }

    private static void saveWindowBounds(Stage stage) {
        try {
            PREFS.putBoolean("window.maximized", stage.isMaximized());
            if (!stage.isMaximized()) {
                PREFS.putDouble("window.width", stage.getWidth());
                PREFS.putDouble("window.height", stage.getHeight());
            }
            AppLog.fine("Window bounds saved (maximized=" + stage.isMaximized() + ").");
        } catch (Exception ignored) {
            // Persistence is a nice-to-have only.
        }
    }
}
