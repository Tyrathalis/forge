package forge.util.update;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        return makePlan(manifest, localFileResolver, false);
    }

    /** sizeOnly = the cheap self-heal probe; see {@link DeltaManifest#diffAgainst(Function, boolean)}. */
    public static Plan makePlan(DeltaManifest manifest, Function<String, File> localFileResolver, boolean sizeOnly)
            throws IOException {
        final List<DeltaManifest.Entry> changed = manifest.diffAgainst(localFileResolver, sizeOnly);
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
                sourceUrl = snapshotBaseUrl + encodePath(entry.path()); //release asset
            } else if (entry.path().startsWith("res/")) {
                sourceUrl = rawBaseUrl + commit + "/"
                        + encodePath(REPO_RES_PREFIX + entry.path().substring("res/".length()));
            } else {
                sourceUrl = snapshotBaseUrl + encodePath(entry.path());
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
     * before the app has loaded assets (i.e. at the splash screen). This method
     * itself never deletes; after a successful apply the caller runs
     * {@link #deleteOrphanedResFiles} so upstream renames/removals don't
     * accumulate (the original leave-extras-alone policy let a stale edition
     * file keep an old set code alive against the current edition using it).
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

    /** Deletion never runs off a manifest without a substantial res tree — the
     *  legacy jar-only bridge view lists one file and must not empty an install. */
    static final int MIN_MANIFEST_RES_FILES = 1000;
    /** Orphans accumulate a handful per release; an implausible count means a
     *  wrong root or a truncated manifest — delete nothing rather than a lot. */
    static final int MAX_ORPHAN_DELETIONS = 500;

    /**
     * Deletes files under resRoot that the manifest's res/ list no longer
     * carries. Upstream renames and removals otherwise accumulate in every
     * install forever — one such leftover, a stale edition file, kept an old
     * set code alive and collided with the current edition using it. The
     * manifest lists the complete res tree, so absence is authoritative.
     *
     * Runs only after a successful apply; failures here are logged, never
     * thrown — cleanup is best-effort, not part of the update. Guards make
     * mass deletion structurally impossible: no deletion off a manifest
     * without a substantial res list ({@link #MIN_MANIFEST_RES_FILES}), no
     * deletion of an implausible orphan count
     * ({@link #MAX_ORPHAN_DELETIONS}), and a path matching a manifest entry
     * case-insensitively is skipped — after a case-only rename, on a
     * case-insensitive filesystem, it IS the manifest file.
     *
     * @return manifest-relative paths actually deleted
     */
    public static List<String> deleteOrphanedResFiles(DeltaManifest manifest, File resRoot) {
        return deleteOrphanedResFiles(manifest, resRoot, null);
    }

    /**
     * keep, when non-null, exempts manifest-relative paths from the sweep —
     * for install layouts that legitimately carry files the manifest cannot
     * know (Android's cardsfolder.zip and res/build.txt).
     */
    public static List<String> deleteOrphanedResFiles(DeltaManifest manifest, File resRoot,
            java.util.function.Predicate<String> keep) {
        final List<String> deleted = new ArrayList<>();
        if (resRoot == null || !resRoot.isDirectory()) {
            return deleted;
        }
        final Set<String> known = new HashSet<>();
        final Set<String> knownLower = new HashSet<>();
        for (final DeltaManifest.Entry entry : manifest.getEntries()) {
            if (entry.path().startsWith("res/")) {
                known.add(entry.path());
                knownLower.add(entry.path().toLowerCase(Locale.ROOT));
            }
        }
        if (known.size() < MIN_MANIFEST_RES_FILES) {
            return deleted;
        }
        final List<String> onDisk = new ArrayList<>();
        collectFilesUnder(resRoot, "res", onDisk);
        final List<String> orphans = new ArrayList<>();
        for (final String rel : onDisk) {
            if (keep != null && keep.test(rel)) {
                continue;
            }
            if (!known.contains(rel) && !knownLower.contains(rel.toLowerCase(Locale.ROOT))) {
                orphans.add(rel);
            }
        }
        if (orphans.size() > MAX_ORPHAN_DELETIONS) {
            System.err.println("Orphan cleanup skipped: " + orphans.size()
                    + " candidates exceeds the safety cap of " + MAX_ORPHAN_DELETIONS);
            return deleted;
        }
        for (final String rel : orphans) {
            final File file = new File(resRoot, rel.substring("res/".length()));
            if (file.delete()) {
                deleted.add(rel);
                //prune directories the deletion emptied, up to (never including) resRoot
                File dir = file.getParentFile();
                while (dir != null && !dir.equals(resRoot) && dir.delete()) {
                    dir = dir.getParentFile();
                }
            } else {
                System.err.println("Orphan cleanup could not delete " + rel);
            }
        }
        return deleted;
    }

    private static void collectFilesUnder(File dir, String relPrefix, List<String> out) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (final File child : children) {
            final String rel = relPrefix + "/" + child.getName();
            if (child.isDirectory()) {
                collectFilesUnder(child, rel, out);
            } else {
                out.add(rel);
            }
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

    /**
     * Percent-encodes each path segment for use in a URL, keeping the '/'
     * separators. The res tree carries 30K+ paths with spaces plus # ' [ ] & !
     * unicode and a literal % - raw concatenation produced URLs GitHub answers
     * with HTTP 400 (or, for '#', silently truncates as a fragment).
     */
    static String encodePath(final String path) {
        final StringBuilder sb = new StringBuilder(path.length() + 16);
        for (final String segment : path.split("/", -1)) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    static String readUrlText(String url) throws IOException {
        final URLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Plain fetch for release assets that are not manifest entries (Android's cardsfolder.zip). */
    public static void downloadFile(String url, File target) throws IOException {
        downloadTo(url, target);
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
