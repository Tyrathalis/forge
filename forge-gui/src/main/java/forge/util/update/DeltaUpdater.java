package forge.util.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Delta update against a playable-fork release (worklist items 1+2): fetch the
 * release manifest, diff it against the local install, download only what
 * changed, verify hashes, and apply. res/ files come from the raw-file host at
 * the release's commit (the res tree IS the repo tree, so per-file delta needs
 * no extra hosting); the launcher jar is a release asset. res/ applies in
 * place - the updater runs at the splash screen, before assets load. The jar
 * is staged for {@link UpdateApplier}, because a running jar cannot be
 * replaced on every platform.
 *
 * All hosts are parameters so the whole flow smoke-tests against file:// URLs.
 */
public final class DeltaUpdater {
    public static final String MANIFEST_NAME = "manifest.txt";
    public static final String STAGING_DIR_NAME = ".update-staging";
    /** Where the install's res/ lives in the repo tree, for raw fetches. */
    private static final String REPO_RES_PREFIX = "forge-gui/res/";
    private static final int MAX_DOWNLOAD_FILES = 20000; //sanity cap, not a tuning knob

    public record Plan(DeltaManifest manifest, List<DeltaManifest.Entry> changed, long totalBytes, boolean jarChanged) {
        public int fileCount() {
            return changed.size();
        }
    }

    /** Null if the release publishes no manifest - callers fall back to the full package. */
    public static DeltaManifest fetchManifest(String snapshotBaseUrl) {
        try {
            return DeltaManifest.parse(readUrlText(snapshotBaseUrl + MANIFEST_NAME));
        } catch (final Exception ex) {
            System.err.println("Delta update unavailable, falling back to full package: " + ex);
            return null;
        }
    }

    /** Null when the delta is implausible (runaway change count) - fall back. */
    public static Plan makePlan(DeltaManifest manifest, Function<String, File> localFileResolver) throws IOException {
        final List<DeltaManifest.Entry> changed = manifest.diffAgainst(localFileResolver);
        long totalBytes = 0;
        boolean jarChanged = false;
        for (final DeltaManifest.Entry entry : changed) {
            totalBytes += entry.size();
            if (entry.path().equals(manifest.getJarPath())) {
                jarChanged = true;
            }
        }
        if (changed.size() > MAX_DOWNLOAD_FILES) {
            return null;
        }
        return new Plan(manifest, changed, totalBytes, jarChanged);
    }

    /**
     * Downloads every changed file into stagingDir (manifest-relative layout),
     * verifying each against its manifest hash. progress receives
     * (filesDone, totalFiles). Throws on any failure - the caller falls back;
     * nothing has been applied yet at that point.
     */
    public static void download(Plan plan, String snapshotBaseUrl, String rawBaseUrl, File stagingDir,
            BiConsumer<Integer, Integer> progress) throws IOException {
        final String commit = plan.manifest().getCommit();
        int done = 0;
        for (final DeltaManifest.Entry entry : plan.changed()) {
            final String sourceUrl;
            if (entry.path().equals(plan.manifest().getJarPath())) {
                sourceUrl = snapshotBaseUrl + entry.path(); //release asset
            } else if (entry.path().startsWith("res/")) {
                sourceUrl = rawBaseUrl + commit + "/" + REPO_RES_PREFIX + entry.path().substring("res/".length());
            } else {
                sourceUrl = snapshotBaseUrl + entry.path();
            }
            final File staged = new File(stagingDir, entry.path());
            if (staged.getParentFile() != null && !staged.getParentFile().isDirectory()
                    && !staged.getParentFile().mkdirs()) {
                throw new IOException("Could not create " + staged.getParentFile());
            }
            downloadTo(sourceUrl, staged);
            final String actual = DeltaManifest.sha256(staged);
            if (!actual.equals(entry.sha256())) {
                throw new IOException("Hash mismatch for " + entry.path() + " from " + sourceUrl);
            }
            done++;
            if (progress != null) {
                progress.accept(done, plan.fileCount());
            }
        }
    }

    /**
     * Moves every staged non-jar file into place via the resolver. Only safe
     * before the app has loaded assets (i.e. at the splash screen). Files not
     * listed in the manifest are deliberately never deleted - user-modified or
     * extra res files are left alone.
     */
    public static void applyResFiles(Plan plan, File stagingDir, Function<String, File> localFileResolver) throws IOException {
        for (final DeltaManifest.Entry entry : plan.changed()) {
            if (entry.path().equals(plan.manifest().getJarPath())) {
                continue; //the jar is applied by UpdateApplier after exit
            }
            final File staged = new File(stagingDir, entry.path());
            final File target = localFileResolver.apply(entry.path());
            if (target == null) {
                throw new IOException("No local target for " + entry.path());
            }
            if (target.getParentFile() != null && !target.getParentFile().isDirectory()
                    && !target.getParentFile().mkdirs()) {
                throw new IOException("Could not create " + target.getParentFile());
            }
            Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursively(File dir) {
        final File[] children = dir.listFiles();
        if (children != null) {
            for (final File child : children) {
                deleteRecursively(child);
            }
        }
        dir.delete();
    }

    static String readUrlText(String url) throws IOException {
        final URLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void downloadTo(String url, File target) throws IOException {
        final URLConnection conn = open(url);
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(target)) {
            in.transferTo(out);
        }
    }

    private static URLConnection open(String url) throws IOException {
        final URLConnection conn = new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "Forge Playable Updater");
        if (conn instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(true);
            final int status = http.getResponseCode();
            if (status >= 400) {
                throw new IOException("HTTP " + status + " for " + url);
            }
        }
        return conn;
    }

    private DeltaUpdater() {
    }
}
