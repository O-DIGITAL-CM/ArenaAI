package cm.odigital.arenaai;

import javafx.animation.PauseTransition;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.util.Duration;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Detects Cloudflare verification and block pages in the embedded browser.
 *
 * <p>This does not interact with, solve, or bypass a challenge. It only looks
 * for Cloudflare challenge markers after a document loads so the app can tell
 * the user why the embedded page may be stuck and offer the system browser as
 * a fallback.</p>
 */
public final class CloudflareChallengeWatcher {

    private static final String DETECTOR_SCRIPT = """
            (function() {
              try {
                var href = String(location.href || '').toLowerCase();
                var title = String(document.title || '').toLowerCase();
                var body = document.body ? String(document.body.innerText || '').toLowerCase() : '';
                var challengeUrl = href.indexOf('/cdn-cgi/challenge-platform/') >= 0
                  || href.indexOf('__cf_chl_tk=') >= 0;
                var challengeWidget = document.querySelector(
                  'iframe[src*="challenges.cloudflare.com"], .cf-turnstile, '
                  + 'input[name="cf-turnstile-response"], #challenge-form, #challenge-stage, '
                  + '#cf-challenge-running, .cf-browser-verification, [id^="cf-chl-"]'
                );
                var challengeCopy = [
                  'checking your browser',
                  'checking if the site connection is secure',
                  'performing security verification',
                  'verify you are human',
                  "i'm not a robot",
                  'complete the security check'
                ].some(function(marker) { return body.indexOf(marker) >= 0; });
                var cloudflareBlock = title.indexOf('attention required! | cloudflare') >= 0
                  || body.indexOf('sorry, you have been blocked') >= 0
                  || body.indexOf('cloudflare ray id') >= 0
                  || document.querySelector('#cf-error-details') !== null;
                if (challengeUrl || challengeWidget || challengeCopy || cloudflareBlock) {
                  return 'Cloudflare verification or security page detected.';
                }
              } catch (e) {}
              return '';
            })();
            """;

    private CloudflareChallengeWatcher() {
        // Utility class — no instances.
    }

    /**
     * Watches successful navigations and checks again after JavaScript has had
     * time to render challenge widgets. A given URL is reported only once.
     *
     * @param engine WebEngine to inspect
     * @param onDetected called on the FX thread when a Cloudflare challenge is found
     */
    public static void watch(WebEngine engine, Consumer<String> onDetected) {
        Set<String> reportedUrls = new HashSet<>();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                scheduleChecks(engine, reportedUrls, onDetected);
            }
        });
        if (engine.getLoadWorker().getState() == Worker.State.SUCCEEDED) {
            scheduleChecks(engine, reportedUrls, onDetected);
        }
    }

    private static void scheduleChecks(WebEngine engine, Set<String> reportedUrls,
                                      Consumer<String> onDetected) {
        String location = engine.getLocation();
        check(engine, reportedUrls, onDetected, location);

        PauseTransition firstSettle = new PauseTransition(Duration.seconds(2));
        firstSettle.setOnFinished(e -> {
            if (location == null || !location.equals(engine.getLocation())) {
                return;
            }
            check(engine, reportedUrls, onDetected, location);

            PauseTransition lateSettle = new PauseTransition(Duration.seconds(3));
            lateSettle.setOnFinished(late -> {
                if (location.equals(engine.getLocation())) {
                    check(engine, reportedUrls, onDetected, location);
                }
            });
            lateSettle.play();
        });
        firstSettle.play();
    }

    private static void check(WebEngine engine, Set<String> reportedUrls,
                              Consumer<String> onDetected, String location) {
        if (location == null || location.isBlank() || reportedUrls.contains(location)) {
            return;
        }
        String result = detect(engine);
        if (result != null) {
            reportedUrls.add(location);
            onDetected.accept(result);
        }
    }

    /**
     * One-shot inspection of the current document.
     *
     * @return a safe, generic diagnostic if Cloudflare markers are found; otherwise null
     */
    public static String detect(WebEngine engine) {
        try {
            Object result = engine.executeScript(DETECTOR_SCRIPT);
            return result instanceof String message && !message.isBlank() ? message : null;
        } catch (Exception e) {
            return null;
        }
    }
}
