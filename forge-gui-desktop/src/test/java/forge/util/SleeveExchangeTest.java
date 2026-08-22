package forge.util;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins what a client will accept when another player offers it a sleeve. This is the receiving
 * half of the sharing design, and the only place peer-controlled bytes are admitted at all, so
 * the rules are: it must be a well-formed key, the bytes must pass the same probe a local import
 * passes, and the content must hash to the name it arrived under. Anything else is dropped.
 *
 * <p>Accepted sleeves land in a session directory, never in the library: another player's image
 * is borrowed for as long as the game lasts and is never quietly added to your own collection.
 */
public class SleeveExchangeTest {

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

    @Test(timeOut = TIMEOUT)
    public void acceptsASleeveThatMatchesItsName() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-exchange-ok").toFile();
        final byte[] bytes = png(360, 500, 4096);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(bytes));

        Assert.assertFalse(SleeveExchange.have(dir, key), "claimed to have it before it arrived");
        Assert.assertNull(SleeveExchange.accept(dir, key, bytes), "a valid offer was refused");
        Assert.assertTrue(SleeveExchange.have(dir, key), "accepted but not found afterwards");
        Assert.assertEquals(SleeveStore.read(dir, key), bytes);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesContentThatDoesNotHashToItsName() throws Exception {
        // The property the whole sharing design rests on: a peer cannot answer "here is sleeve X"
        // with some other picture. Both of these are perfectly valid images on their own.
        final File dir = Files.createTempDirectory("sleeve-exchange-swap").toFile();
        final byte[] promised = png(360, 500, 4096);
        final byte[] delivered = png(360, 500, 8192);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(promised));

        Assert.assertTrue(CustomSleeves.probe(delivered).accepted(), "the swapped image is itself valid");
        Assert.assertNotNull(SleeveExchange.accept(dir, key, delivered), "a swapped image was accepted");
        Assert.assertFalse(SleeveExchange.have(dir, key));
        Assert.assertEquals(dir.list().length, 0, "a refused offer left a file behind");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesWhatALocalImportWouldAlsoRefuse() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-exchange-bad").toFile();

        final byte[] gif = "GIF89a------------------".getBytes(StandardCharsets.UTF_8);
        Assert.assertNotNull(SleeveExchange.accept(dir, CustomSleeves.keyFor(CustomSleeves.sha256Hex(gif)), gif), "GIF");

        final byte[] huge = png(360, 500, CustomSleeves.MAX_BYTES + 1);
        Assert.assertNotNull(SleeveExchange.accept(dir, CustomSleeves.keyFor(CustomSleeves.sha256Hex(huge)), huge), "oversize");

        final byte[] bomb = png(30000, 30000, 4096);
        Assert.assertNotNull(SleeveExchange.accept(dir, CustomSleeves.keyFor(CustomSleeves.sha256Hex(bomb)), bomb), "bomb");

        Assert.assertEquals(dir.list().length, 0, "a refused offer left a file behind");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesMalformedKeys() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-exchange-keys").toFile();
        final byte[] bytes = png(360, 500, 4096);
        for (final String key : new String[] {
                "s:../../etc/passwd", "s:" + "a".repeat(63), "c:Forest", "", null}) {
            Assert.assertNotNull(SleeveExchange.accept(dir, key, bytes), "accepted key: " + key);
        }
        Assert.assertEquals(dir.list().length, 0);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void boundsHowMuchOnePartyCanLeaveBehind() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-exchange-flood").toFile();
        int accepted = 0;
        for (int i = 0; i < SleeveExchange.MAX_SESSION_SLEEVES * 3; i++) {
            final byte[] bytes = png(360, 500, 4096 + i);
            if (SleeveExchange.accept(dir, CustomSleeves.keyFor(CustomSleeves.sha256Hex(bytes)), bytes) == null) {
                accepted++;
            }
        }
        Assert.assertEquals(accepted, SleeveExchange.MAX_SESSION_SLEEVES, "the session cache is unbounded");
        Assert.assertEquals(dir.list().length, SleeveExchange.MAX_SESSION_SLEEVES);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void acceptingTheSameSleeveTwiceIsIdempotent() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-exchange-twice").toFile();
        final byte[] bytes = png(360, 500, 4096);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(bytes));
        Assert.assertNull(SleeveExchange.accept(dir, key, bytes));
        Assert.assertNull(SleeveExchange.accept(dir, key, bytes), "a repeat offer was refused");
        Assert.assertEquals(dir.list().length, 1);
        delete(dir);
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
