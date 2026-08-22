package forge.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.imageio.ImageIO;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Pins the import path: whatever a person picks, what lands in the store is something the probe
 * would admit. The interesting case is the ordinary one - a phone photo, several thousand pixels
 * wide and megabytes long, which has to come out at most 1024 on its long edge and under the byte
 * cap without the user being told to go and resize it first.
 */
public class SleeveImportTest {

    private static final int TIMEOUT = 60_000;

    private static File writeImage(final File dir, final String name, final int w, final int h) throws Exception {
        final BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        // A gradient plus noise: a flat fill would compress to nothing and prove less
        for (int x = 0; x < w; x += 8) {
            for (int y = 0; y < h; y += 8) {
                g.setColor(new Color((x * 7) % 256, (y * 13) % 256, ((x + y) * 3) % 256));
                g.fillRect(x, y, 8, 8);
            }
        }
        g.dispose();
        final File f = new File(dir, name);
        ImageIO.write(image, name.endsWith(".png") ? "png" : "jpg", f);
        return f;
    }

    @Test(timeOut = TIMEOUT)
    public void aPhoneSizedPhotoBecomesALegalSleeve() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-import-big").toFile();
        final File source = writeImage(dir, "photo.jpg", 3024, 4032);
        Assert.assertTrue(source.length() > CustomSleeves.MAX_BYTES,
                "the source must exceed the cap for this test to mean anything");

        final SleeveStore.Result result = SleeveImport.fromFile(dir, source);
        Assert.assertNull(result.error, result.error);

        final byte[] stored = SleeveStore.read(dir, result.key);
        final CustomSleeves.Probe probe = CustomSleeves.probe(stored);
        Assert.assertTrue(probe.accepted(), "stored sleeve would be refused: " + probe.rejection);
        Assert.assertTrue(probe.width <= CustomSleeves.MAX_DIMENSION
                && probe.height <= CustomSleeves.MAX_DIMENSION, probe.width + "x" + probe.height);
        Assert.assertTrue(stored.length <= CustomSleeves.MAX_BYTES, stored.length + " bytes");
        Assert.assertEquals(CustomSleeves.sha256Hex(stored), CustomSleeves.hashFromKey(result.key));
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void anImageAlreadyWithinBoundsIsStoredByteForByte() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-import-small").toFile();
        final File source = writeImage(dir, "sleeve.png", 360, 500);
        final byte[] original = Files.readAllBytes(source.toPath());
        if (original.length > CustomSleeves.MAX_BYTES) {
            return; // the generated noise happened to be incompressible; nothing to assert here
        }
        final SleeveStore.Result result = SleeveImport.fromFile(dir, source);
        Assert.assertNull(result.error, result.error);
        Assert.assertEquals(SleeveStore.read(dir, result.key), original,
                "an image already within bounds was re-encoded rather than kept");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void aLinkToAnOversizeImageDownscalesJustLikeAPickedFile() throws Exception {
        // Reported from play: a picked file of this size was downscaled happily while the same
        // image behind a link was refused as "too large". The two paths must agree.
        final File dir = Files.createTempDirectory("sleeve-import-parity").toFile();
        final File source = writeImage(dir, "big.jpg", 3024, 4032);
        Assert.assertTrue(source.length() > CustomSleeves.MAX_BYTES,
                "the source must exceed the sleeve cap for this test to mean anything");

        final SleeveStore.Result viaLink = SleeveImport.fromUrl(dir, source.toURI().toString());
        Assert.assertNull(viaLink.error, "a link to an oversize image was refused: " + viaLink.error);

        final File other = Files.createTempDirectory("sleeve-import-parity2").toFile();
        final SleeveStore.Result viaFile = SleeveImport.fromFile(other, source);
        Assert.assertNull(viaFile.error, viaFile.error);
        Assert.assertEquals(viaLink.key, viaFile.key, "the same image took two different shapes");
        delete(other);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesASourceThatWouldDecodeIntoAHugeRaster() throws Exception {
        // A decompression bomb is a small file that declares an enormous canvas. The header check
        // has to refuse it before anything allocates that raster.
        final File dir = Files.createTempDirectory("sleeve-import-bomb").toFile();
        final File bomb = new File(dir, "bomb.png");
        Files.write(bomb.toPath(), bombPng(30000, 30000));
        Assert.assertTrue(bomb.length() < CustomSleeves.MAX_SOURCE_BYTES, "a bomb is a small file");
        final SleeveStore.Result result = SleeveImport.fromFile(dir, bomb);
        Assert.assertNotNull(result.error, "a 900 megapixel source was accepted");
        Assert.assertTrue(result.error.contains("megapixel"), result.error);
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void refusesWhatCannotBecomeASleeve() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-import-bad").toFile();

        final File tiny = writeImage(dir, "tiny.png", 8, 8);
        Assert.assertNotNull(SleeveImport.fromFile(dir, tiny).error, "an 8x8 image was accepted");

        final File notAnImage = new File(dir, "notes.txt");
        Files.write(notAnImage.toPath(), "this is not an image".getBytes(StandardCharsets.UTF_8));
        Assert.assertNotNull(SleeveImport.fromFile(dir, notAnImage).error, "a text file was accepted");

        Assert.assertNotNull(SleeveImport.fromFile(dir, new File(dir, "absent.png")).error, "a missing file");
        delete(dir);
    }

    @Test(timeOut = TIMEOUT)
    public void importsFromALink() throws Exception {
        final File dir = Files.createTempDirectory("sleeve-import-url").toFile();
        final File source = writeImage(dir, "linked.png", 500, 500);
        final SleeveStore.Result result = SleeveImport.fromUrl(dir, source.toURI().toString());
        Assert.assertNull(result.error, result.error);
        Assert.assertTrue(CustomSleeves.probe(SleeveStore.read(dir, result.key)).accepted());
        delete(dir);
    }

    /** A PNG header declaring an enormous canvas, with no image data behind it. */
    private static byte[] bombPng(final int w, final int h) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}, 0, 8);
        final byte[] ihdr = {0, 0, 0, 13, 'I', 'H', 'D', 'R',
                (byte) (w >> 24), (byte) (w >> 16), (byte) (w >> 8), (byte) w,
                (byte) (h >> 24), (byte) (h >> 16), (byte) (h >> 8), (byte) h,
                8, 2, 0, 0, 0, 0, 0, 0, 0};
        out.write(ihdr, 0, ihdr.length);
        return out.toByteArray();
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
