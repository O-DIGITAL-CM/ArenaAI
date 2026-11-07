package cm.odigital.arenaai;

import java.util.Set;

/**
 * Central configuration for the Arena desktop application.
 *
 * <p>The desktop app is a thin wrapper: it shows a Discord-style splash
 * screen on launch, then loads the Arena website in an embedded browser.</p>
 */
public final class ArenaConfig {

    private ArenaConfig() {
        // Utility class — no instances.
    }

    /** Website loaded inside the desktop app after the splash screen. */
    public static final String HOME_URL = "https://arena.ai/";

    /** Host considered "internal". Used for the address pill fallback. */
    public static final String HOME_HOST = "arena.ai";

    /** Base window title. The current page title is prepended to it. */
    public static final String APP_TITLE = "Arena";

    /** Set by the Windows packaging script; falls back to the source/dev version. */
    public static final String APP_VERSION = System.getProperty("arena.app.version", "1.0.4");

    /**
     * Minimum time in milliseconds the splash screen stays visible,
     * even if the website finishes loading sooner.
     */
    public static final long SPLASH_MIN_VISIBLE_MS = 3200;

    /**
     * Maximum time in milliseconds to wait for the website before showing
     * the main window anyway (with an offline/error state if needed).
     */
    public static final long SPLASH_MAX_WAIT_MS = 20_000;

    public static final double WINDOW_WIDTH = 1280;
    public static final double WINDOW_HEIGHT = 800;
    public static final double WINDOW_MIN_WIDTH = 1024;
    public static final double WINDOW_MIN_HEIGHT = 640;

    /**
     * When true, login popups from known identity providers stay inside the
     * app (dedicated sign-in window sharing the app's cookies) instead of
     * opening in the system browser — otherwise the login would complete in
     * the wrong browser and the app would stay logged out.
     */
    public static final boolean OPEN_AUTH_POPUPS_IN_APP = true;

    /** Hosts whose popups are treated as sign-in flows (suffix-matched). */
    public static final Set<String> AUTH_POPUP_HOSTS = Set.of(
            HOME_HOST,
            "accounts.google.com",
            "github.com",
            "login.microsoftonline.com",
            "microsoftonline.com",
            "live.com",
            "appleid.apple.com",
            "facebook.com",
            "x.com",
            "twitter.com",
            "auth0.com",
            "okta.com",
            "supabase.co",
            "amazoncognito.com"
    );

    /**
     * When true, cookie names (never values) are logged whenever stored.
     * FINE level, so they only land in the log file — handy to diagnose
     * lost login sessions without leaking secrets.
     */
    public static final boolean LOG_COOKIE_NAMES = false;

    /**
     * When false, no custom cookie handler is installed and WebView uses its
     * built-in in-memory cookies (nothing persists between launches, and
     * Log out cannot clear HttpOnly cookies). Useful to A/B test whether the
     * persistent store affects a login flow.
     */
    public static final boolean USE_PERSISTENT_COOKIES = true;

    /** True if a popup to {@code host} should be treated as a sign-in flow. */
    public static boolean isAuthPopupHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String lower = host.toLowerCase();
        for (String known : AUTH_POPUP_HOSTS) {
            if (lower.equals(known) || lower.endsWith("." + known)) {
                return true;
            }
        }
        return false;
    }
}
