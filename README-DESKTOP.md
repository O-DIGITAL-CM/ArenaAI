# Arena — Windows Desktop App (JavaFX)

A Windows desktop wrapper for **https://arena.ai/** built with JavaFX:

1. **Discord-style splash screen** on launch — borderless window, logo, rotating
   tips, live loading status and progress bar.
2. The website **preloads behind the splash**, then the splash fades out and the
   main window appears **already on arena.ai** (no second load).
3. The main window is a slim desktop browser: back / forward / reload / home,
   address pill, thin load-progress bar, offline/error overlay, sign-in popups
   kept in-app, other pop-ups opened in your system browser.
4. **Automatic sign-in error detection**: if a page reports an auth failure
   (e.g. `Auth session missing`), the app logs it and shows guidance.

> Note: the original `HelloApplication` / `hello-view.fxml` sample files are
> still in the repo but are no longer used. The entry point is now
> `Launcher` → `ArenaApplication`.

## Run it (Windows, dev mode)

Prerequisites: **JDK 21+** on `PATH` (Maven itself is not required —
the repo ships the `mvnw` wrapper).

```powershell
.\mvnw.cmd javafx:run
```

## Project map

| File | Role |
|---|---|
| `src/main/java/cm/odigital/arenaai/Launcher.java` | `main()` entry — sets up logging, launches `ArenaApplication` (also the `jpackage` entry) |
| `.../ArenaApplication.java` | App orchestration: preload site → splash → fade to main window; remembers window size |
| `.../ArenaConfig.java` | One place for the URL, version, timings, window defaults and auth-popup hosts |
| `.../SplashController.java` | Splash logic: tips rotation, status text, progress binding, fade in/out |
| `.../BrowserController.java` | Main window: toolbar, history, address pill, error overlay, shortcuts, JS dialogs, pop-up handling, log-out, logs, sign-in recovery |
| `.../UpdateManager.java` | Startup/manual update checks, download progress, installer prompt and launch |
| `.../UpdateService.java` | Queries the public GitHub Releases API, downloads the Windows installer, verifies SHA-256 |
| `.../WebViewFactory.java` | Builds the pre-configured `WebView` (JS on, native WebKit user agent retained) |
| `.../CookiePersistence.java` | Installs the file-backed cookie handler; `clearAll()` for log-out |
| `.../PersistentCookieStore.java` | `CookieStore` that saves cookies to disk on every change and reloads them on launch |
| `.../AppPaths.java` | Resolves the per-user data dir (`%APPDATA%\Arena`) |
| `.../AppLog.java` | Central logging: console + rotating files under `%APPDATA%\Arena\logs` |
| `.../JsConsoleBridge.java` | Captures page JS console output + uncaught JS errors into the log |
| `.../AuthPopupDialog.java` | In-app sign-in window for login popups (shares the app's cookies) |
| `.../AuthErrorWatcher.java` | Scans loaded pages for known sign-in failure signatures |
| `src/main/resources/cm/odigital/arenaai/splash-view.fxml` | Splash layout (borderless card) |
| `src/main/resources/cm/odigital/arenaai/browser-view.fxml` | Main window layout (toolbar + `WebView` container + error overlay) |
| `.../css/splash.css`, `.../css/browser.css` | Discord-ish dark themes |
| `.../images/arena-logo.png` | App logo (splash + window icon). Replace with your own art anytime |
| `package-windows.ps1` | Windows packaging; installer builds get a SHA-256 sidecar and a stable upgrade identity |

## Change the website / splash timing

Everything lives in
`src/main/java/cm/odigital/arenaai/ArenaConfig.java`:

```java
public static final String HOME_URL = "https://arena.ai/";
public static final long SPLASH_MIN_VISIBLE_MS = 3200;  // min splash time
public static final long SPLASH_MAX_WAIT_MS = 20_000;   // never stuck longer
```

The splash closes when **both** are true: the minimum time elapsed **and**
the page finished loading (or failed) — the max timeout guarantees the app
never gets stuck on the splash, even offline.

## Build a Windows installer / portable app

```powershell
# Portable folder (no extra tools needed) -> target\dist\Arena\Arena.exe
.\package-windows.ps1

# Real installers (require WiX Toolset v3 on PATH: https://wixtoolset.org)
.\package-windows.ps1 -Type msi
.\package-windows.ps1 -Type exe -AppVersion 1.0.1
```

What the script does: `mvnw clean package` → `mvnw javafx:jlink`
(trimmed runtime in `target\app`) → `jpackage` with Start-Menu entry,
desktop shortcut and install-dir chooser. `-AppVersion` is also passed into the
application so the updater compares the version actually installed. Installer
builds use a stable Windows upgrade UUID; do not change it between releases.

## Updating from GitHub Releases

- Builds made before the updater was added cannot update themselves. Install the
  first updater-enabled build once using its Windows installer; subsequent
  releases can be installed from inside the app.
- The app checks the public `O-DIGITAL-CM/ArenaAI` GitHub Releases page after
  startup. Use the **Update** toolbar button to check manually. No GitHub token
  is needed.
- When a newer stable release is found, the app offers **Download and install**.
  It downloads the Windows `.exe` installer, verifies the attached SHA-256 file,
  launches the installer, then closes Arena. It never installs without the
  user's confirmation.
- To publish an update, build a Windows installer with the matching version,
  for example:

  ```powershell
  .\package-windows.ps1 -Type exe -AppVersion 1.0.1
  ```

  Create a GitHub Release tagged `v1.0.1` and attach both
  `target\dist\Arena-1.0.1.exe` and the generated
  `target\dist\Arena-1.0.1.exe.sha256`. The tag and `-AppVersion` must match
  (`vX.Y.Z` / `X.Y.Z`); the updater expects those exact asset names. The release
  must be published (not a draft or prerelease) for the `/releases/latest` API
  endpoint to return it.
- The in-app installer is currently Windows-only. Updating from a portable
  `app-image` launches a normal Windows installer; it does not replace the
  portable folder. Users of portable builds should download the new portable
  package manually.

## App icon for the installer

`jpackage` on Windows needs **`.ico`** format. To use the bundled logo:

1. Convert `src/main/resources/cm/odigital/arenaai/images/arena-logo.png`
   to `.ico` (e.g. with GIMP, IrfanView, or an online PNG→ICO converter).
2. Save it as `src/main/resources/arena-icon.ico`.
3. Re-run `.\package-windows.ps1` — it picks the icon up automatically.

(The in-app splash + window icon already use the PNG directly.)

## Shortcuts

| Keys | Action |
|---|---|
| `F5` / `Ctrl+R` | Reload |
| `Alt+Left` / `Alt+Right` | Back / forward |
| `Alt+Home` | Go to arena.ai home |
| Click address pill | Copy current page URL |

## Browsing data & privacy

- **Cookies persist** in `%APPDATA%\Arena\cookies.dat` (on other OSes:
  `~/.arena/cookies.dat`), so you **stay logged in** to arena.ai between
  launches. `Max-Age` cookies are aged by the time spent away; expired ones
  are dropped on load.
- The cookie file is a plain binary file, **not encrypted** — anyone with
  access to your Windows user account could read your session cookies.
- The **Log out** toolbar button (with confirmation) clears all cookies +
  the current site's local/session storage and returns to the home page.
- Cache, history and `localStorage` are otherwise in-memory and vanish on exit.
- Cookies are normalized to Netscape (Version 0) format when sent: the JDK
  otherwise serializes modern `Max-Age` cookies as RFC 2965
  (`$Version=1; …`), which current servers fail to parse — this used to make
  arena.ai's own API routes report a missing session.

## Logs & diagnosing errors (e.g. Google sign-in)

- Click **Logs** in the toolbar to open the current log file directly.
  Files live at `%APPDATA%\Arena\logs\arena-0.log` (`arena-1.log`… are older
  rotations, 1 MB × 5). Errors are also printed to the console when running
  via `.\mvnw.cmd javafx:run`.
- What gets logged: app lifecycle, every navigation, page-load failures with
  stack traces, WebView engine errors, **page JavaScript console output and
  uncaught JS errors** (tagged `[JS …]`), popup decisions, sign-in window
  events, uncaught exceptions.
- To diagnose the Google-login error: reproduce the sign-in, open **Logs**,
  and look for `[JS error]`, `Page load failed` and `Popup (sign-in)` lines —
  they show what the page itself reported.
- Sign-in popups (Google, GitHub, Microsoft, … — see `AUTH_POPUP_HOSTS` in
  `ArenaConfig`) now open in an **in-app sign-in window** sharing the app's
  cookies, instead of the system browser where the login couldn't complete.
  Set `OPEN_AUTH_POPUPS_IN_APP = false` to restore the old behavior.
- **Automatic detection:** loaded pages (main window and sign-in window) are
  scanned for known sign-in failure signatures (`Auth session missing`,
  `disallowed_useragent`, …). On a match the app logs a SEVERE entry and —
  in the main window — shows a guidance dialog with an Open Logs shortcut.
  After the sign-in window closes, the app offers a reload, but only if the
  page still reports a problem (so unsent chat drafts are never wiped).
- To trace cookie/session issues without leaking secrets, set
  `LOG_COOKIE_NAMES = true` in `ArenaConfig`: cookie names (never values)
  are then logged to the file at FINE level — both when stored (`Cookie
  stored: …`) and when sent with each request (`Cookies for <url> -> …`).
  If a request shows `(none)` where a session cookie is expected, the store
  isn't sending it; if no `Cookies for` lines appear at all, WebView isn't
  consulting the custom store.
- To A/B test the persistent store itself, set `USE_PERSISTENT_COOKIES =
  false`: the app then uses WebView's built-in in-memory cookies. If login
  works with the flag off but fails with it on, the bug is in our store.
- If login fails with "Auth session missing" right after launch, first try
  **Log out** + retry: a stale restored session cookie may be poisoning the
  flow (the cookie file only tracks `Max-Age`, not server-side expiry).

## Good to know / limitations

- The embedded browser is JavaFX `WebView` (WebKit-based), **not** full
  Chrome/Edge: most of arena.ai works fine, but some cutting-edge web features,
  DRM video or exotic CSS may render differently than in Chrome/Edge. The app
  keeps WebKit's real user agent instead of advertising an older Chrome version
  the embedded engine does not actually implement.
- Cloudflare verification can appear inside the app but may not finish in the
  embedded WebKit engine. The app detects common Cloudflare challenge pages and
  offers to open that page in the system browser. Cookies are separate between
  the app and the system browser, so verification completed in Chrome/Edge may
  not carry back into the app; if the challenge keeps looping, continue using
  Arena in the system browser for that session. The `↗` toolbar button remains
  available to open any current page externally.
- `target="_blank"` links and `window.open(...)` pop-ups open in the system
  browser, **except sign-in flows**, which stay in-app (see above).
- JavaScript `alert` / `confirm` / `prompt` are mapped to native dialogs.
- Downloads: `WebView` has no download manager — file downloads triggered
  by the site may not work inside the app; use the system browser for those.
- No build was run in this environment (Java/Maven excluded) — first run
  `.\mvnw.cmd javafx:run` on a machine with JDK 21 to verify.
