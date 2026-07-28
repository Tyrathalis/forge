package forge.util.update;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Function;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the playable-fork delta updater (worklist items 1+2): manifest
 * parse/reject rules, the diff decision, and the full
 * fetch-plan-download-verify-apply flow against file:// URLs standing in for
 * the GitHub release and raw hosts - the same code paths the real update
 * takes, minus the network.
 */
public class DeltaUpdateTest {

    private static final String COMMIT = "0123456789abcdef";

    @Test
    public void parsesHeadersAndEntries() throws Exception {
        final DeltaManifest manifest = DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER,
                "#version 2.0.14-PLAYABLE",
                "#commit " + COMMIT,
                "#jar forge-playable.jar",
                "#future-header is skipped, not fatal",
                "AABB\t10\tforge-playable.jar",
                "ccdd\t20\tres/cards/a.txt"));
        Assert.assertEquals(manifest.getVersion(), "2.0.14-PLAYABLE");
        Assert.assertEquals(manifest.getCommit(), COMMIT);
        Assert.assertEquals(manifest.getJarPath(), "forge-playable.jar");
        Assert.assertEquals(manifest.getEntry("forge-playable.jar").sha256(), "aabb", "hashes normalize to lowercase");
        Assert.assertEquals(manifest.getEntry("res/cards/a.txt").size(), 20);
    }

    @Test
    public void rejectsUnsafeAndMalformed() {
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse("not a manifest"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(
                DeltaManifest.HEADER + "\nAA\t1\t../escape"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(
                DeltaManifest.HEADER + "\nAA\tnotanumber\tres/x"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(DeltaManifest.HEADER));
    }

    @Test
    public void sha256MatchesKnownVector() throws Exception {
        final File empty = Files.createTempFile("delta-test", ".bin").toFile();
        empty.deleteOnExit();
        Assert.assertEquals(DeltaManifest.sha256(empty),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    public void endToEndDeltaAgainstFileUrls() throws Exception {
        final File root = Files.createTempDirectory("delta-e2e").toFile();
        final File release = new File(root, "release");     //stands in for the GitHub release assets
        final File rawRepo = new File(root, "raw");         //stands in for raw.githubusercontent.com
        final File install = new File(root, "install");     //the local install: jar + res
        final File staging = new File(root, "staging");

        //the "new build": jar changed, one res file changed, one added, one unchanged
        final byte[] newJar = "new-jar-bytes".getBytes(StandardCharsets.UTF_8);
        write(new File(release, "forge-playable.jar"), newJar);
        final File repoRes = new File(rawRepo, COMMIT + "/forge-gui/res");
        write(new File(repoRes, "cards/changed.txt"), "new card text".getBytes(StandardCharsets.UTF_8));
        write(new File(repoRes, "cards/added.txt"), "brand new".getBytes(StandardCharsets.UTF_8));
        write(new File(repoRes, "cards/same.txt"), "unchanged".getBytes(StandardCharsets.UTF_8));

        //the local install: old jar, stale changed.txt, same.txt identical, no added.txt
        final File localJar = new File(install, "app/forge.jar");
        write(localJar, "old-jar-bytes".getBytes(StandardCharsets.UTF_8));
        write(new File(install, "res/cards/changed.txt"), "old card text".getBytes(StandardCharsets.UTF_8));
        write(new File(install, "res/cards/same.txt"), "unchanged".getBytes(StandardCharsets.UTF_8));

        final String manifestText = String.join("\n",
                DeltaManifest.HEADER,
                "#version test",
                "#commit " + COMMIT,
                "#jar forge-playable.jar",
                line(new File(release, "forge-playable.jar"), "forge-playable.jar"),
                line(new File(repoRes, "cards/changed.txt"), "res/cards/changed.txt"),
                line(new File(repoRes, "cards/added.txt"), "res/cards/added.txt"),
                line(new File(repoRes, "cards/same.txt"), "res/cards/same.txt"));
        write(new File(release, DeltaUpdater.MANIFEST_NAME), manifestText.getBytes(StandardCharsets.UTF_8));

        final String releaseUrl = release.toURI().toURL().toString();
        final String rawUrl = rawRepo.toURI().toURL() + "/";

        final DeltaManifest manifest = DeltaUpdater.fetchManifest(releaseUrl.endsWith("/") ? releaseUrl : releaseUrl + "/");
        Assert.assertNotNull(manifest, "manifest fetch over file://");

        final Function<String, File> resolver = path ->
                path.equals(manifest.getJarPath()) ? localJar : new File(install, path);
        final DeltaUpdater.Plan plan = DeltaUpdater.makePlan(manifest, resolver);
        Assert.assertNotNull(plan);
        Assert.assertEquals(plan.fileCount(), 3, "jar + changed + added; same.txt skipped");
        Assert.assertTrue(plan.jarChanged());

        final String base = releaseUrl.endsWith("/") ? releaseUrl : releaseUrl + "/";
        final int[] lastProgress = {0};
        DeltaUpdater.download(plan, base, rawUrl, staging, (done, total) -> lastProgress[0] = done);
        Assert.assertEquals(lastProgress[0], 3, "progress reached the end");

        DeltaUpdater.applyResFiles(plan, staging, resolver);

        Assert.assertEquals(read(new File(install, "res/cards/changed.txt")), "new card text");
        Assert.assertEquals(read(new File(install, "res/cards/added.txt")), "brand new");
        Assert.assertEquals(read(localJar), "old-jar-bytes", "the running jar is never applied in-process");
        final File stagedJar = new File(staging, "forge-playable.jar");
        Assert.assertTrue(stagedJar.isFile(), "jar staged for the applier");
        Assert.assertEquals(DeltaManifest.sha256(stagedJar), manifest.getEntry("forge-playable.jar").sha256());

        //second pass after the applier would have swapped the jar: nothing left to do
        write(localJar, newJar);
        final DeltaUpdater.Plan again = DeltaUpdater.makePlan(manifest, resolver);
        Assert.assertEquals(again.fileCount(), 0, "converged after apply");

        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void corruptDownloadFailsBeforeAnythingIsApplied() throws Exception {
        final File root = Files.createTempDirectory("delta-corrupt").toFile();
        final File release = new File(root, "release");
        final File install = new File(root, "install");
        write(new File(release, "forge-playable.jar"), "tampered".getBytes(StandardCharsets.UTF_8));
        final String manifestText = String.join("\n",
                DeltaManifest.HEADER,
                "#commit " + COMMIT,
                "#jar forge-playable.jar",
                "0000000000000000000000000000000000000000000000000000000000000000\t8\tforge-playable.jar");
        write(new File(release, DeltaUpdater.MANIFEST_NAME), manifestText.getBytes(StandardCharsets.UTF_8));

        final String base = release.toURI().toURL() + "/";
        final DeltaManifest manifest = DeltaUpdater.fetchManifest(base);
        final File localJar = new File(install, "forge.jar");
        final DeltaUpdater.Plan plan = DeltaUpdater.makePlan(manifest, p -> localJar);
        Assert.assertThrows(IOException.class, () ->
                DeltaUpdater.download(plan, base, base, new File(root, "staging"), null));
        Assert.assertFalse(localJar.exists(), "nothing applied on hash mismatch");
        DeltaUpdater.deleteRecursively(root);
    }

    private static String line(File file, String path) throws IOException {
        return DeltaManifest.sha256(file) + "\t" + file.length() + "\t" + path;
    }

    private static void write(File file, byte[] bytes) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), bytes);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
