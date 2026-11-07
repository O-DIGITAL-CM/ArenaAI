package cm.odigital.arenaai;

import javafx.application.Application;

import java.security.Security;

public class Launcher {
    public static void main(String[] args) {
        AppLog.setup();
        AppLog.info("Starting " + ArenaConfig.APP_TITLE + " " + ArenaConfig.APP_VERSION
                + " (Java " + System.getProperty("java.version")
                + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ")");
        AppLog.info("EC/TLS provider available: " + (Security.getProvider("SunEC") != null));
        Application.launch(ArenaApplication.class, args);
    }
}
