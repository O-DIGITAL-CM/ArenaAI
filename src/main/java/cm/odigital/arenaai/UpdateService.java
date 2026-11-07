package cm.odigital.arenaai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the public GitHub Releases page and downloads/verifies the Windows
 * installer for the latest stable Arena release.
 *
 * <p>The installer and its {@code .sha256} sidecar must be attached to every
 * release as {@code Arena-x.y.z.exe} and {@code Arena-x.y.z.exe.sha256}.</p>
 */
public final class UpdateService {

    private static final String REPOSITORY = "O-DIGITAL-CM/ArenaAI";
    private static final URI LATEST_RELEASE_API = URI.create(
            "https://api.github.com/repos/" + REPOSITORY + "/releases/latest");
    private static final String RELEASE_DOWNLOAD_BASE =
            "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final Pattern TAG_PATTERN = Pattern.compile(
            "\"tag_name\"\\s*:\\s*\"(v?\\d+\\.\\d+\\.\\d+)\"");
    private static final Pattern SHA256_PATTERN = Pattern.compile("(?i)\\b([0-9a-f]{64})\\b");
    private static final long MAX_INSTALLER_BYTES = 300L * 1024L * 1024L;
    private static final int MAX_CHECKSUM_BYTES = 4096;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private UpdateService() {
        // Utility class — no instances.
    }

    /** Describes a stable release newer than the running application. */
    public record Release(String version, String tag, URI installerUri, URI checksumUri, URI releasePageUri) {
    }

    /** Callback used by the UI to update its download progress. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    /**
     * The self-updater currently installs the Windows {@code .exe} package.
     */
    public static boolean isSupportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    /**
     * Queries GitHub's latest stable release. Returns empty when the current
     * build is already up to date.
     */
    public static Optional<Release> checkForUpdate(String currentVersion)
            throws IOException, InterruptedException {
        if (!isSupportedPlatform()) {
            throw new IOException("The in-app installer currently supports Windows only.");
        }
        versionParts(currentVersion);

        HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_API)
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "ArenaDesktop/" + currentVersion)
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("GitHub Releases returned HTTP " + response.statusCode() + ".");
        }

        Matcher tagMatcher = TAG_PATTERN.matcher(response.body());
        if (!tagMatcher.find()) {
            throw new IOException("The latest GitHub release does not use the expected vX.Y.Z tag format.");
        }
        String tag = tagMatcher.group(1);
        String latestVersion = tag.startsWith("v") ? tag.substring(1) : tag;
        if (compareVersions(latestVersion, currentVersion) <= 0) {
            return Optional.empty();
        }

        String installerName = "Arena-" + latestVersion + ".exe";
        URI installer = URI.create(RELEASE_DOWNLOAD_BASE + tag + "/" + installerName);
        URI checksum = URI.create(installer + ".sha256");
        URI releasePage = URI.create("https://github.com/" + REPOSITORY + "/releases/tag/" + tag);
        return Optional.of(new Release(latestVersion, tag, installer, checksum, releasePage));
    }

    /**
     * Downloads the release installer to a temporary directory and verifies it
     * against the SHA-256 sidecar before returning its path.
     */
    public static Path downloadAndVerify(Release release, ProgressListener progress)
            throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("Arena-update-");
        Path installer = directory.resolve("Arena-" + release.version() + ".exe");
        boolean verified = false;
        try {
            long downloaded = downloadToFile(release.installerUri(), installer, progress);
            String expectedHash = downloadExpectedHash(release.checksumUri());
            String actualHash = sha256(installer);
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                throw new IOException("The downloaded installer failed its SHA-256 check; it was not launched.");
            }
            if (progress != null) {
                progress.onProgress(downloaded, downloaded);
            }
            verified = true;
            return installer;
        } finally {
            if (!verified) {
                deleteQuietly(installer);
                deleteQuietly(directory);
            }
        }
    }

    private static long downloadToFile(URI uri, Path destination, ProgressListener progress)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "ArenaDesktopUpdater/" + ArenaConfig.APP_VERSION)
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IOException("GitHub could not provide the installer (HTTP "
                    + response.statusCode() + "). Check that the release includes the Windows .exe asset.");
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (totalBytes > MAX_INSTALLER_BYTES) {
            closeQuietly(response.body());
            throw new IOException("The installer is larger than the updater's 300 MB safety limit.");
        }

        long copied = 0;
        if (progress != null) {
            progress.onProgress(0, totalBytes);
        }
        try (InputStream input = response.body();
             OutputStream output = Files.newOutputStream(destination,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                copied += read;
                if (copied > MAX_INSTALLER_BYTES) {
                    throw new IOException("The installer is larger than the updater's 300 MB safety limit.");
                }
                output.write(buffer, 0, read);
                if (progress != null) {
                    progress.onProgress(copied, totalBytes);
                }
            }
        }
        if (totalBytes >= 0 && copied != totalBytes) {
            throw new IOException("The installer download ended before all bytes were received.");
        }
        if (copied == 0) {
            throw new IOException("GitHub returned an empty installer file.");
        }
        return copied;
    }

    private static String downloadExpectedHash(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "text/plain, application/octet-stream")
                .header("User-Agent", "ArenaDesktopUpdater/" + ArenaConfig.APP_VERSION)
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw new IOException("The GitHub release is missing its SHA-256 checksum asset.");
        }
        byte[] bytes;
        try (InputStream input = response.body()) {
            bytes = input.readNBytes(MAX_CHECKSUM_BYTES + 1);
        }
        if (bytes.length > MAX_CHECKSUM_BYTES) {
            throw new IOException("The release checksum file is unexpectedly large.");
        }
        Matcher matcher = SHA256_PATTERN.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            throw new IOException("The release checksum file does not contain a valid SHA-256 hash.");
        }
        return matcher.group(1);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available in this Java runtime.", e);
        }
    }

    private static int compareVersions(String left, String right) throws IOException {
        int[] a = versionParts(left);
        int[] b = versionParts(right);
        for (int i = 0; i < a.length; i++) {
            int comparison = Integer.compare(a[i], b[i]);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int[] versionParts(String version) throws IOException {
        if (version == null || !version.matches("v?\\d+\\.\\d+\\.\\d+")) {
            throw new IOException("Arena version must use the X.Y.Z format.");
        }
        String normalized = version.startsWith("v") ? version.substring(1) : version;
        String[] parts = normalized.split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException e) {
            throw new IOException("Arena version contains an out-of-range number.", e);
        }
    }

    private static void closeQuietly(InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Best effort while reporting the original HTTP error.
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Temporary update files are best-effort cleanup only.
        }
    }
}
