package forge.gamemodes.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ObjectOutputStream;
import java.nio.file.Files;

import io.netty.handler.codec.serialization.ClassResolvers;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.gamemodes.net.event.SleeveBlobEvent;
import forge.util.CustomSleeves;
import forge.util.SleeveExchange;
import forge.util.SleeveStore;

/**
 * Pins that a custom sleeve actually survives the multiplayer wire, and that what comes off it is
 * checked rather than trusted.
 *
 * <p>The wire runs a class allowlist, so a new event type is a claim that needs proving, not a
 * declaration: {@code SleeveBlobEvent} carries a {@code byte[]}, whose wire name is {@code [B} and
 * matches no package prefix. It is admitted by the primitive-array rule, and this test is what says
 * so out loud - a false reject would strand a game rather than fail quietly.
 */
public class SleeveWireTest {

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

    private static byte[] encode(final Object graph) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new CObjectOutputStream(bytes, false, null, -1, false)) {
            out.writeObject(graph);
        }
        return bytes.toByteArray();
    }

    private static Object decode(final byte[] frame) throws Exception {
        try (CObjectInputStream in = new CObjectInputStream(
                new ByteArrayInputStream(frame), ClassResolvers.cacheDisabled(null), null)) {
            return in.readObject();
        }
    }

    @Test(timeOut = TIMEOUT)
    public void aSleeveSurvivesTheFilteredWireIntact() throws Exception {
        final byte[] image = png(360, 500, 40_000);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(image));

        final Object decoded = decode(encode(new SleeveBlobEvent(key, image)));
        Assert.assertTrue(decoded instanceof SleeveBlobEvent, "the filter rejected the event type");
        final SleeveBlobEvent blob = (SleeveBlobEvent) decoded;
        Assert.assertEquals(blob.getKey(), key);
        Assert.assertEquals(blob.getBytes(), image, "the payload did not survive the wire");
    }

    @Test(timeOut = TIMEOUT)
    public void whatComesOffTheWireIsAcceptedOnItsMerits() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-wire-accept").toFile();
        final byte[] image = png(360, 500, 8192);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(image));

        final SleeveBlobEvent blob = (SleeveBlobEvent) decode(encode(new SleeveBlobEvent(key, image)));
        Assert.assertNull(SleeveExchange.accept(dir, blob.getKey(), blob.getBytes()));
        Assert.assertEquals(SleeveStore.read(dir, key), image, "stored bytes differ from what arrived");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void aSleeveAlteredInFlightIsRefused() throws Exception {
        // Whoever is on the other end - or in the middle - the recipient checks the content against
        // the name it arrived under rather than taking the sender's word for it.
        final File dir = Files.createTempDirectory("sleeve-wire-tamper").toFile();
        final byte[] promised = png(360, 500, 8192);
        final String key = CustomSleeves.keyFor(CustomSleeves.sha256Hex(promised));
        final byte[] substituted = png(360, 500, 12_288);

        final SleeveBlobEvent blob = (SleeveBlobEvent) decode(encode(new SleeveBlobEvent(key, substituted)));
        Assert.assertNotNull(SleeveExchange.accept(dir, blob.getKey(), blob.getBytes()),
                "a substituted image was accepted under another sleeve's name");
        Assert.assertEquals(dir.list().length, 0);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void theEventNamesItsSizeAndNeverItsContent() {
        // Every sent event is written to the network log by toString; a quarter-megabyte of image
        // bytes in a log line is its own kind of failure.
        final byte[] image = png(360, 500, 40_000);
        final String text = new SleeveBlobEvent(CustomSleeves.keyFor(CustomSleeves.sha256Hex(image)), image).toString();
        Assert.assertTrue(text.contains("40000 bytes"), text);
        Assert.assertTrue(text.length() < 200, "the log line carries the payload: " + text.length() + " chars");
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
