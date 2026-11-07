package cm.odigital.arenaai;

import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

/**
 * Creates pre-configured {@link WebView} instances for the Arena desktop app.
 */
public final class WebViewFactory {

    private WebViewFactory() {
        // Utility class — no instances.
    }

    /**
     * Creates a WebView tuned for desktop browsing: JavaScript enabled and
     * the WebKit engine's real user agent retained. Spoofing a Chrome version
     * that this WebView does not actually implement can make browser checks
     * (including Cloudflare verification) fail or loop.
     *
     * <p>Must be called on the JavaFX Application Thread.</p>
     */
    public static WebView createConfiguredWebView() {
        WebView webView = new WebView();
        WebEngine engine = webView.getEngine();
        engine.setJavaScriptEnabled(true);
        AppLog.info("Using native WebView user agent: " + engine.getUserAgent());
        webView.setContextMenuEnabled(true);
        webView.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return webView;
    }
}
