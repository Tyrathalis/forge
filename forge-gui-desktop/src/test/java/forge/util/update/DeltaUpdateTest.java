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
                DeltaManifest.HEADER + "\nAA\t1\tres/../escape"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(
                DeltaManifest.HEADER + "\nAA\t1\t/etc/passwd"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(
                DeltaManifest.HEADER + "\nAA\t1\tres\\escape"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(
                DeltaManifest.HEADER + "\nAA\tnotanumber\tres/x"));
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(DeltaManifest.HEADER));
    }

    @Test
    public void dotsInsideFilenamesAreNotTraversal() throws Exception {
        //the res tree really contains res/adventure/common/maps/map/aerie/wastetown..tmx;
        //a substring ".." check rejected it and aborted every live delta plan
        final DeltaManifest manifest = DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER,
                "AA\t1\tres/adventure/common/maps/map/aerie/wastetown..tmx",
                "BB\t2\tres/cards/some..card.txt"));
        Assert.assertNotNull(manifest.getEntry("res/adventure/common/maps/map/aerie/wastetown..tmx"));
        Assert.assertNotNull(manifest.getEntry("res/cards/some..card.txt"));
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

    @Test
    public void pathEncodingIsSegmentWiseAndExact() {
        Assert.assertEquals(DeltaUpdater.encodePath("res/adventure/Realm of Legends/x.dck"),
                "res/adventure/Realm%20of%20Legends/x.dck");
        Assert.assertEquals(DeltaUpdater.encodePath("res/a#b.txt"), "res/a%23b.txt", "# would truncate as a fragment");
        Assert.assertEquals(DeltaUpdater.encodePath("res/100%.txt"), "res/100%25.txt", "literal % must not double-decode");
        Assert.assertEquals(DeltaUpdater.encodePath("res/año.txt"), "res/a%C3%B1o.txt", "UTF-8 percent-encoding");
        Assert.assertEquals(DeltaUpdater.encodePath("res/wastetown..tmx"), "res/wastetown..tmx", "dots pass through");
        Assert.assertEquals(DeltaUpdater.encodePath("res/a [v2] (old) & new!.txt"),
                "res/a%20%5Bv2%5D%20%28old%29%20%26%20new%21.txt");
    }

    @Test
    public void bridgeManifestPrefersV2EntriesAndValidatesThem() throws Exception {
        //bridge manifest: plain entry = the legacy jar-only hop; "#2 " lines = the
        //full list, invisible to ≤07.29 parsers, preferred by this one
        final DeltaManifest bridge = DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER,
                "#commit " + COMMIT,
                "#jar forge-playable.jar",
                "AA\t10\tforge-playable.jar",
                "#2 AA\t10\tforge-playable.jar",
                "#2 BB\t20\tres/adventure/Realm of Legends/deck.dck"));
        int n = 0;
        for (@SuppressWarnings("unused") final DeltaManifest.Entry e : bridge.getEntries()) {
            n++;
        }
        Assert.assertEquals(n, 2, "the #2 set is the truth for this parser");
        Assert.assertNotNull(bridge.getEntry("res/adventure/Realm of Legends/deck.dck"));
        //v2 lines get the same safety validation as plain ones
        Assert.assertThrows(IOException.class, () -> DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER, "AA\t1\tres/ok", "#2 AA\t1\tres/../escape")));
    }

    @Test
    public void sizeOnlyDiffSkipsContentButNotSizeOrPresence() throws Exception {
        final File root = Files.createTempDirectory("delta-sizeonly").toFile();
        final File sameSize = new File(root, "res/samesize.txt");
        write(sameSize, "AAAA".getBytes(StandardCharsets.UTF_8));
        final DeltaManifest manifest = DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER,
                "#commit " + COMMIT,
                "1111111111111111111111111111111111111111111111111111111111111111\t4\tres/samesize.txt",
                "2222222222222222222222222222222222222222222222222222222222222222\t9\tres/missing.txt"));
        final Function<String, File> resolver = path -> new File(root, path);
        Assert.assertEquals(manifest.diffAgainst(resolver, false).size(), 2, "hash pass sees the content mismatch");
        Assert.assertEquals(manifest.diffAgainst(resolver, true).size(), 1, "size-only pass trusts equal sizes");
        Assert.assertEquals(manifest.diffAgainst(resolver, true).get(0).path(), "res/missing.txt");
        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void hostileFilenamesSurviveHttpDownload() throws Exception {
        //v7 field failure: raw.githubusercontent.com answered HTTP 400 for
        //.../res/adventure/Realm of Legends/decks/legends/nicol_bolas.dck - the URL
        //builder never percent-encoded paths, and the res tree carries 30K+ paths
        //with spaces plus # ' [ ] ( ) & ! , unicode and one literal %. file:// URLs
        //are too lenient to catch this; a real HTTP server rejects raw spaces in
        //the request line just like GitHub does, so this runs the production
        //download flow over actual HTTP.
        final String[] hostile = {
                "res/adventure/Realm of Legends/decks/legends/nicol_bolas.dck",
                "res/cards/wastetown..tmx",
                "res/cards/sharp#name [v2] (old) & more!.txt",
                "res/cards/año’s card, 100%.txt",
        };
        final File root = Files.createTempDirectory("delta-http").toFile();
        final File docroot = new File(root, "docroot");
        final File repoRes = new File(docroot, "raw/" + COMMIT + "/forge-gui/res");
        final StringBuilder manifestText = new StringBuilder(String.join("\n",
                DeltaManifest.HEADER,
                "#version test",
                "#commit " + COMMIT,
                "#jar forge-playable.jar"));
        final File jar = new File(docroot, "release/forge-playable.jar");
        write(jar, "jar-bytes".getBytes(StandardCharsets.UTF_8));
        manifestText.append("\n").append(line(jar, "forge-playable.jar"));
        for (final String path : hostile) {
            final File f = new File(repoRes, path.substring("res/".length()));
            write(f, ("content of " + path).getBytes(StandardCharsets.UTF_8));
            manifestText.append("\n").append(line(f, path));
        }
        write(new File(docroot, "release/" + DeltaUpdater.MANIFEST_NAME),
                manifestText.toString().getBytes(StandardCharsets.UTF_8));

        final com.sun.net.httpserver.HttpServer server =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            //getRequestURI().getPath() percent-DECODES - the file lookup sees real names
            final File f = new File(docroot, exchange.getRequestURI().getPath().substring(1));
            if (f.isFile()) {
                final byte[] bytes = Files.readAllBytes(f.toPath());
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            final DeltaManifest manifest = DeltaUpdater.fetchManifest(base + "/release/");
            Assert.assertNotNull(manifest, "manifest fetch over http");
            final File install = new File(root, "install"); //empty: everything is in the plan
            final Function<String, File> resolver = path ->
                    path.equals(manifest.getJarPath()) ? new File(install, "forge.jar") : new File(install, path);
            final DeltaUpdater.Plan plan = DeltaUpdater.makePlan(manifest, resolver);
            Assert.assertEquals(plan.fileCount(), 1 + hostile.length);

            final File staging = new File(root, "staging");
            DeltaUpdater.download(plan, base + "/release/", base + "/raw/", staging, null);
            for (final String path : hostile) {
                Assert.assertEquals(read(new File(staging, path)), "content of " + path);
            }
        } finally {
            server.stop(0);
            DeltaUpdater.deleteRecursively(root);
        }
    }

    //--- orphan cleanup (the never-delete policy's falsification: a stale edition
    //file kept an old set code alive and collided with the current edition) ------

    /** A manifest whose res/ list clears the deletion floor, padded with absent-on-disk filler. */
    private static DeltaManifest bigResManifest(String... realResLines) throws IOException {
        final StringBuilder sb = new StringBuilder(String.join("\n",
                DeltaManifest.HEADER, "#commit " + COMMIT, "#jar forge-playable.jar",
                "AA\t1\tforge-playable.jar"));
        for (final String line : realResLines) {
            sb.append("\n").append(line);
        }
        for (int i = 0; i < DeltaUpdater.MIN_MANIFEST_RES_FILES; i++) {
            sb.append("\nBB\t1\tres/filler/f").append(i).append(".txt");
        }
        return DeltaManifest.parse(sb.toString());
    }

    @Test
    public void orphanedResFilesAreDeletedAfterApply() throws Exception {
        final File root = Files.createTempDirectory("delta-orphan").toFile();
        final File resRoot = new File(root, "res");
        final File kept = new File(resRoot, "editions/Current Set.txt");
        final File orphanEdition = new File(resRoot, "editions/Renamed Away.txt");
        final File orphanNested = new File(resRoot, "cardsfolder/upcoming/old_name.txt");
        write(kept, "keep".getBytes(StandardCharsets.UTF_8));
        write(orphanEdition, "stale".getBytes(StandardCharsets.UTF_8));
        write(orphanNested, "stale".getBytes(StandardCharsets.UTF_8));
        final File outsideRes = new File(root, "res-sync.txt"); //sibling of res/, never in scope
        write(outsideRes, "stamp".getBytes(StandardCharsets.UTF_8));

        final DeltaManifest manifest = bigResManifest(line(kept, "res/editions/Current Set.txt"));
        final java.util.List<String> deleted = DeltaUpdater.deleteOrphanedResFiles(manifest, resRoot);

        Assert.assertTrue(kept.isFile(), "manifest-listed file kept");
        Assert.assertTrue(outsideRes.isFile(), "files outside res/ are out of scope");
        Assert.assertFalse(orphanEdition.exists(), "orphaned edition file deleted");
        Assert.assertFalse(orphanNested.exists(), "orphaned nested file deleted");
        Assert.assertFalse(orphanNested.getParentFile().exists(), "emptied dirs pruned");
        Assert.assertTrue(resRoot.isDirectory(), "res root itself never pruned");
        Assert.assertEqualsNoOrder(deleted.toArray(),
                new String[] {"res/editions/Renamed Away.txt", "res/cardsfolder/upcoming/old_name.txt"});
        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void jarOnlyBridgeManifestNeverDeletes() throws Exception {
        //the legacy bridge view lists ONLY the jar: driving deletion off it would
        //empty the whole res tree. The floor guard must make that impossible.
        final File root = Files.createTempDirectory("delta-orphan-bridge").toFile();
        final File resRoot = new File(root, "res");
        final File anything = new File(resRoot, "cards/a.txt");
        write(anything, "x".getBytes(StandardCharsets.UTF_8));
        final DeltaManifest jarOnly = DeltaManifest.parse(String.join("\n",
                DeltaManifest.HEADER, "#commit " + COMMIT, "#jar forge-playable.jar",
                "AA\t1\tforge-playable.jar"));
        Assert.assertEquals(DeltaUpdater.deleteOrphanedResFiles(jarOnly, resRoot).size(), 0);
        Assert.assertTrue(anything.isFile(), "nothing deleted under a jar-only manifest");
        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void implausibleOrphanCountDeletesNothing() throws Exception {
        final File root = Files.createTempDirectory("delta-orphan-cap").toFile();
        final File resRoot = new File(root, "res");
        for (int i = 0; i <= DeltaUpdater.MAX_ORPHAN_DELETIONS; i++) {
            write(new File(resRoot, "bulk/o" + i + ".txt"), "x".getBytes(StandardCharsets.UTF_8));
        }
        final DeltaManifest manifest = bigResManifest();
        Assert.assertEquals(DeltaUpdater.deleteOrphanedResFiles(manifest, resRoot).size(), 0,
                "over-cap candidate set deletes nothing at all");
        Assert.assertTrue(new File(resRoot, "bulk/o0.txt").isFile());
        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void caseVariantOfManifestEntryIsNotAnOrphan() throws Exception {
        //after a case-only upstream rename, a case-insensitive filesystem (macOS
        //default) resolves old and new name to the SAME file - deleting the
        //"orphan" would delete the manifest file itself
        final File root = Files.createTempDirectory("delta-orphan-case").toFile();
        final File resRoot = new File(root, "res");
        final File onDisk = new File(resRoot, "editions/some set.txt");
        write(onDisk, "x".getBytes(StandardCharsets.UTF_8));
        final DeltaManifest manifest = bigResManifest("CC\t1\tres/editions/Some Set.txt");
        Assert.assertEquals(DeltaUpdater.deleteOrphanedResFiles(manifest, resRoot).size(), 0);
        Assert.assertTrue(onDisk.isFile(), "case variant of a manifest path survives");
        DeltaUpdater.deleteRecursively(root);
    }

    @Test
    public void missingResRootIsANoOp() throws Exception {
        final DeltaManifest manifest = bigResManifest();
        Assert.assertEquals(DeltaUpdater.deleteOrphanedResFiles(manifest,
                new File("/nonexistent/res")).size(), 0);
        Assert.assertEquals(DeltaUpdater.deleteOrphanedResFiles(manifest, null).size(), 0);
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
