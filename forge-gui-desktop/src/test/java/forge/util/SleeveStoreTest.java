package forge.util;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the custom-sleeve store: what it admits, what it names things, and the two properties the
 * sharing design rests on - that a stored sleeve's bytes are exactly the bytes that were
 * validated, and that a key never becomes a path outside the store.
 */
public class SleeveStoreTest {

    private static final int TIMEOUT = 30_000;

    private static byte[] png(final int width, final int height, final int pad) {
        final byte[] head = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n',
                0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (width >> 24), (byte) (width >> 16), (byte) (width >> 8), (byte) width,
                (byte) (height >> 24), (byte) (height >> 16), (byte) (height >> 8), (byte) height,
                8, 6, 0, 0, 0};
        final byte[] b = new byte[Math.max(head.length, pad)];
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    private static File tempDir(final String name) throws Exception {
        return Files.createTempDirectory(name).toFile();
    }

    @Test(timeOut = TIMEOUT)
    public void savesUnderTheContentHashAndReturnsItsKey() throws Exception {
        final File dir = tempDir("sleeve-save");
        final byte[] bytes = png(360, 500, 4096);
        final SleeveStore.Result r = SleeveStore.save(dir, bytes);
        Assert.assertNull(r.error, r.error);
        Assert.assertEquals(r.key, CustomSleeves.keyFor(CustomSleeves.sha256Hex(bytes)));
        Assert.assertTrue(SleeveStore.fileFor(dir, r.key).isFile(), "no file written");
        Assert.assertEquals(SleeveStore.fileFor(dir, r.key).getName(),
                CustomSleeves.sha256Hex(bytes) + ".png");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void storedBytesAreExactlyTheValidatedBytes() throws Exception {
        // The identity the sharing design rests on: what we hand to a peer is what we probed.
        final File dir = tempDir("sleeve-roundtrip");
        final byte[] bytes = png(360, 500, 8192);
        final SleeveStore.Result r = SleeveStore.save(dir, bytes);
        Assert.assertEquals(SleeveStore.read(dir, r.key), bytes, "stored bytes differ");
        Assert.assertEquals(CustomSleeves.sha256Hex(SleeveStore.read(dir, r.key)),
                CustomSleeves.hashFromKey(r.key), "content no longer matches its key");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void savingTheSameImageTwiceIsOneEntry() throws Exception {
        final File dir = tempDir("sleeve-dedupe");
        final byte[] bytes = png(360, 500, 4096);
        final String first = SleeveStore.save(dir, bytes).key;
        final String second = SleeveStore.save(dir, bytes).key;
        Assert.assertEquals(second, first);
        Assert.assertEquals(SleeveStore.keys(dir).size(), 1, "duplicate entry written");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesWhatTheProbeRefusesAndWritesNothing() throws Exception {
        final File dir = tempDir("sleeve-refuse");
        final SleeveStore.Result huge = SleeveStore.save(dir, png(360, 500, CustomSleeves.MAX_BYTES + 1));
        Assert.assertNotNull(huge.error, "oversize payload accepted");
        Assert.assertNull(huge.key);
        final SleeveStore.Result gif = SleeveStore.save(dir, "GIF89a------------------".getBytes(StandardCharsets.UTF_8));
        Assert.assertNotNull(gif.error, "GIF accepted");
        Assert.assertEquals(dir.list().length, 0, "a refused image left a file behind");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void listingIgnoresAnythingNotWellFormed() throws Exception {
        final File dir = tempDir("sleeve-list");
        final String key = SleeveStore.save(dir, png(360, 500, 4096)).key;
        Files.write(new File(dir, "notes.txt").toPath(), "junk".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "deadbeef.png").toPath(), "short hash".getBytes(StandardCharsets.UTF_8));
        Files.createDirectory(new File(dir, "a".repeat(64) + ".png").toPath());
        final List<String> keys = SleeveStore.keys(dir);
        Assert.assertEquals(keys.size(), 1, "listing admitted junk: " + keys);
        Assert.assertEquals(keys.get(0), key);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void hostileKeysNeverResolveToAPath() throws Exception {
        // A file that really is reachable by traversal, so "null" cannot pass for the wrong
        // reason: without the hex-only chokepoint, "s:../reachable" finds this.
        final File root = tempDir("sleeve-hostile");
        final File dir = new File(root, "store");
        Assert.assertTrue(dir.mkdirs());
        final File reachable = new File(root, "reachable.png");
        Files.write(reachable.toPath(), png(360, 500, 4096));
        Assert.assertTrue(reachable.isFile(), "the traversal target must exist for this test to mean anything");

        for (final String key : new String[] {
                "s:../reachable", "s:../../etc/passwd", "s:..\\..\\windows", "s:" + "a".repeat(63),
                "s:" + "g".repeat(64), "s:aaaa/bbbb", "s:", "c:Forest", null}) {
            Assert.assertNull(SleeveStore.fileFor(dir, key), "resolved a path for: " + key);
            Assert.assertNull(SleeveStore.read(dir, key), "read bytes for: " + key);
        }
        delete(root);
    }

    @Test(timeOut = TIMEOUT)
    public void importStopsAtTheByteCap() throws Exception {
        // file:// stands in for the network here, the same way DeltaUpdateTest does it
        final File dir = tempDir("sleeve-import");
        final File source = new File(dir, "big.png");
        Files.write(source.toPath(), png(360, 500, CustomSleeves.MAX_BYTES * 4));
        final SleeveStore.Download d = SleeveStore.download(source.toURI().toString());
        Assert.assertNotNull(d.error, "an oversize download was not stopped");
        Assert.assertNull(d.bytes);

        final File ok = new File(dir, "ok.png");
        Files.write(ok.toPath(), png(360, 500, 4096));
        final SleeveStore.Download good = SleeveStore.download(ok.toURI().toString());
        Assert.assertNull(good.error, good.error);
        Assert.assertEquals(good.bytes.length, 4096);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void importStopsAtTheCapWhenNoLengthIsDeclared() throws Exception {
        // The guard that matters against a server that lies or simply does not say: a chunked
        // response carries no Content-Length, so only the read loop's own cap can stop it.
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/endless", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0); // 0 => chunked, no Content-Length
                final byte[] chunk = new byte[8192];
                // Just over the cap, not wildly over: a huge body would be stopped by the read
                // loop's iteration backstop instead, and this test is about the byte cap itself.
                for (int i = 0; i < (CustomSleeves.MAX_BYTES / 8192) + 8; i++) {
                    exchange.getResponseBody().write(chunk);
                }
            } catch (final IOException expected) {
                // the client hangs up as soon as it has seen enough; that is the point
            } finally {
                exchange.close();
            }
        });
        server.createContext("/small", exchange -> {
            final byte[] body = png(360, 500, 4096);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            final String base = "http://127.0.0.1:" + server.getAddress().getPort();
            final SleeveStore.Download endless = SleeveStore.download(base + "/endless");
            Assert.assertNotNull(endless.error, "an unbounded response was read to the end");
            Assert.assertNull(endless.bytes);

            final SleeveStore.Download small = SleeveStore.download(base + "/small");
            Assert.assertNull(small.error, small.error);
            Assert.assertEquals(small.bytes.length, 4096, "the happy path over real HTTP");
        } finally {
            server.stop(0);
        }
    }

    @Test(timeOut = TIMEOUT)
    public void importRefusesSchemesWeDoNotSpeak() throws Exception {
        for (final String url : new String[] {"ftp://example.invalid/x.png", "gopher://x", "not a url", ""}) {
            Assert.assertNotNull(SleeveStore.download(url).error, "accepted: " + url);
        }
    }

    private static void delete(final File dir) {
        final File[] kids = dir.listFiles();
        if (kids != null) {
            for (final File k : kids) {
                delete(k);
            }
        }
        dir.delete();
    }
}
