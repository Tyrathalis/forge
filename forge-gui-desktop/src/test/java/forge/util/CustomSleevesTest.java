package forge.util;

import java.io.ByteArrayOutputStream;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the custom-sleeve validation core. A custom sleeve is the only path by which another
 * player's bytes reach an image decoder - on mobile a native stb_image bundled in the APK - so
 * these rules run before anything allocates a pixel buffer, and they are the reason the feature
 * is safe to share in-band.
 *
 * <p>Every test carries a timeout: the parsers walk attacker-shaped input, and a test that can
 * hang is worse than a test that fails.
 */
public class CustomSleevesTest {

    private static final int TIMEOUT = 10_000;

    //--- helpers: header-shaped inputs. probe() never decodes, so a valid header plus padding
    //    is exactly as far as it reads.

    private static byte[] png(final int width, final int height) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        writeInt(out, 13);
        out.write('I'); out.write('H'); out.write('D'); out.write('R');
        writeInt(out, width);
        writeInt(out, height);
        out.write(8);    // bit depth
        out.write(6);    // colour type: RGBA
        out.write(0); out.write(0); out.write(0);
        writeInt(out, 0); // CRC placeholder - never checked, we do not decode
        return out.toByteArray();
    }

    private static byte[] jpeg(final int width, final int height) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xFF); out.write(0xD8);                       // SOI
        out.write(0xFF); out.write(0xE0);                       // APP0, skipped by length
        out.write(0); out.write(16);
        out.write(new byte[14], 0, 14);
        out.write(0xFF); out.write(0xC0);                       // SOF0
        out.write(0); out.write(17);
        out.write(8);                                           // sample precision
        out.write((height >> 8) & 0xFF); out.write(height & 0xFF);
        out.write((width >> 8) & 0xFF); out.write(width & 0xFF);
        out.write(new byte[9], 0, 9);
        out.write(0xFF); out.write(0xDA);                       // SOS
        return out.toByteArray();
    }

    private static void writeInt(final ByteArrayOutputStream out, final int v) {
        out.write((v >> 24) & 0xFF); out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF); out.write(v & 0xFF);
    }

    private static byte[] pad(final byte[] head, final int total) {
        final byte[] b = new byte[total];
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    //--- accepted shapes

    @Test(timeOut = TIMEOUT)
    public void acceptsPngAndReadsItsDimensions() {
        final CustomSleeves.Probe p = CustomSleeves.probe(png(360, 500));
        Assert.assertTrue(p.accepted(), p.rejection);
        Assert.assertEquals(p.format, CustomSleeves.Format.PNG);
        Assert.assertEquals(p.width, 360);
        Assert.assertEquals(p.height, 500);
    }

    @Test(timeOut = TIMEOUT)
    public void acceptsJpegAndReadsItsDimensions() {
        final CustomSleeves.Probe p = CustomSleeves.probe(jpeg(640, 480));
        Assert.assertTrue(p.accepted(), p.rejection);
        Assert.assertEquals(p.format, CustomSleeves.Format.JPEG);
        Assert.assertEquals(p.width, 640);
        Assert.assertEquals(p.height, 480);
    }

    //--- the guards

    @Test(timeOut = TIMEOUT)
    public void rejectsPayloadsOverTheByteCap() {
        final byte[] big = pad(png(360, 500), CustomSleeves.MAX_BYTES + 1);
        Assert.assertFalse(CustomSleeves.probe(big).accepted(), "over-cap payload accepted");
        Assert.assertTrue(CustomSleeves.probe(pad(png(360, 500), CustomSleeves.MAX_BYTES)).accepted(),
                "a payload exactly at the cap is legal");
    }

    @Test(timeOut = TIMEOUT)
    public void rejectsDimensionsOverTheCapBeforeAnythingAllocates() {
        final int over = CustomSleeves.MAX_DIMENSION + 1;
        Assert.assertFalse(CustomSleeves.probe(png(over, 500)).accepted(), "wide PNG accepted");
        Assert.assertFalse(CustomSleeves.probe(png(360, over)).accepted(), "tall PNG accepted");
        Assert.assertFalse(CustomSleeves.probe(jpeg(over, 500)).accepted(), "wide JPEG accepted");
        Assert.assertFalse(CustomSleeves.probe(jpeg(360, over)).accepted(), "tall JPEG accepted");
        // the decompression bomb: a tiny file declaring a huge canvas
        Assert.assertFalse(CustomSleeves.probe(png(30000, 30000)).accepted(), "bomb accepted");
    }

    @Test(timeOut = TIMEOUT)
    public void rejectsFormatsOutsidePngAndJpeg() {
        // stb_image also parses these; every one it parses is parser surface we decline to expose
        Assert.assertFalse(CustomSleeves.probe("GIF89a-----------------".getBytes()).accepted(), "GIF");
        Assert.assertFalse(CustomSleeves.probe("BM------------------".getBytes()).accepted(), "BMP");
        Assert.assertFalse(CustomSleeves.probe("8BPS----------------".getBytes()).accepted(), "PSD");
        Assert.assertFalse(CustomSleeves.probe(new byte[] {0, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0}).accepted(), "TGA");
        Assert.assertFalse(CustomSleeves.probe("#?RADIANCE\n-------".getBytes()).accepted(), "HDR");
    }

    @Test(timeOut = TIMEOUT)
    public void rejectsEmptyAndTruncatedInput() {
        Assert.assertFalse(CustomSleeves.probe(null).accepted(), "null");
        Assert.assertFalse(CustomSleeves.probe(new byte[0]).accepted(), "empty");
        final byte[] full = png(360, 500);
        final byte[] cut = new byte[12];
        System.arraycopy(full, 0, cut, 0, 12);
        Assert.assertFalse(CustomSleeves.probe(cut).accepted(), "truncated PNG header");
        Assert.assertFalse(CustomSleeves.probe(new byte[] {(byte) 0xFF, (byte) 0xD8}).accepted(), "bare SOI");
    }

    @Test(timeOut = TIMEOUT)
    public void jpegSegmentWalkAlwaysTerminates() {
        // Shapes built to stall a naive scanner: fill bytes, zero-length segments, a segment
        // length that points backwards, and a stream that is nothing but marker bytes.
        final byte[] allFF = new byte[4096];
        java.util.Arrays.fill(allFF, (byte) 0xFF);
        allFF[0] = (byte) 0xFF; allFF[1] = (byte) 0xD8;
        Assert.assertFalse(CustomSleeves.probe(allFF).accepted(), "marker soup accepted");

        final byte[] zeroLen = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0};
        Assert.assertFalse(CustomSleeves.probe(zeroLen).accepted(), "zero-length segment accepted");

        final byte[] backwards = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 1, 0, 0, 0, 0};
        Assert.assertFalse(CustomSleeves.probe(backwards).accepted(), "backward segment accepted");
    }

    //--- the source gate: what an import may take in, as opposed to what a sleeve may be

    @Test(timeOut = TIMEOUT)
    public void acceptsAsASourceWhatItRefusesAsASleeve() {
        // The distinction the file and link paths got wrong: a phone photo is not a legal sleeve
        // and must still be a legal import, because importing is what turns it into one.
        final byte[] photo = pad(jpeg(3024, 4032), 3_000_000);
        Assert.assertFalse(CustomSleeves.probe(photo).accepted(), "a 3024x4032 photo is not a sleeve");
        final CustomSleeves.Probe source = CustomSleeves.probeSource(photo);
        Assert.assertTrue(source.accepted(), "a 3024x4032 photo must be an acceptable source: " + source.rejection);
        Assert.assertEquals(source.width, 3024);
        Assert.assertEquals(source.height, 4032);
    }

    @Test(timeOut = TIMEOUT)
    public void theSourceGateKeepsTheSameFormatAllowlist() {
        Assert.assertFalse(CustomSleeves.probeSource("GIF89a-----------------".getBytes()).accepted(), "GIF");
        Assert.assertFalse(CustomSleeves.probeSource("BM------------------".getBytes()).accepted(), "BMP");
        Assert.assertFalse(CustomSleeves.probeSource(new byte[0]).accepted(), "empty");
    }

    @Test(timeOut = TIMEOUT)
    public void refusesSourcesThatWouldDecodeIntoAHugeRaster() {
        // A bomb is a small file declaring an enormous canvas, so the byte budget cannot catch it
        final byte[] bomb = png(30000, 30000);
        Assert.assertTrue(bomb.length < CustomSleeves.MAX_SOURCE_BYTES, "a bomb is a small file");
        final CustomSleeves.Probe probe = CustomSleeves.probeSource(bomb);
        Assert.assertFalse(probe.accepted(), "a 900 megapixel source was accepted");
        Assert.assertTrue(probe.rejection.contains("megapixel"), probe.rejection);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesSourcesOverTheSourceByteBudget() {
        Assert.assertFalse(CustomSleeves.probeSource(
                pad(png(360, 500), CustomSleeves.MAX_SOURCE_BYTES + 1)).accepted());
    }

    //--- identity: the key travels the wire and becomes a filename, so it must not become a path

    @Test(timeOut = TIMEOUT)
    public void keysRoundTripThroughTheirHash() {
        final String hash = CustomSleeves.sha256Hex(png(360, 500));
        Assert.assertEquals(hash.length(), 64);
        final String key = CustomSleeves.keyFor(hash);
        Assert.assertTrue(CustomSleeves.isCustomSleeveKey(key));
        Assert.assertEquals(CustomSleeves.hashFromKey(key), hash);
        Assert.assertEquals(CustomSleeves.sha256Hex(png(360, 500)), hash, "same bytes, same identity");
        Assert.assertNotEquals(CustomSleeves.sha256Hex(png(360, 501)), hash, "different bytes, different identity");
    }

    @Test(timeOut = TIMEOUT)
    public void hostileKeysNeverBecomeFilenames() {
        final String[] hostile = {
                "s:../../../../etc/passwd",
                "s:..\\..\\windows\\system32",
                "s:" + "a".repeat(63),          // too short
                "s:" + "a".repeat(65),          // too long
                "s:" + "g".repeat(64),          // not hex
                "s:aaaa/bbbb",
                "s:",
                "s:.",
        };
        for (final String key : hostile) {
            Assert.assertEquals(CustomSleeves.hashFromKey(key), "", "accepted hostile key: " + key);
            Assert.assertNull(CustomSleeves.stem(key), "built a filename from: " + key);
        }
    }
}
