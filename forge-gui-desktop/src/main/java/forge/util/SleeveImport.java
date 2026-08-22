package forge.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * Brings an image the local user picked - from disk or from a link they typed - into the custom
 * sleeve store, resizing and re-encoding it until it fits the limits {@link CustomSleeves} will
 * admit. A phone photo is several thousand pixels wide and megabytes long; the sleeve that comes
 * out of it is at most 1024 on its long edge and under 256 KiB.
 *
 * <p>This runs on the importer's own machine, on bytes the importer chose, which is why decoding
 * here is unremarkable - it is the same act as opening the file in any viewer. The bytes that
 * leave this class have been through the probe, so what is stored (and later shared) is already
 * within every bound a recipient will check.
 */
public final class SleeveImport {
    private SleeveImport() {}

    private static final float[] JPEG_QUALITIES = {0.85f, 0.7f, 0.55f};
    /** Bounded: shrink-and-retry is a loop over an image that may simply not compress. */
    private static final int MAX_ATTEMPTS = 6;
    private static final double SHRINK = 0.75;

    public static SleeveStore.Result fromFile(final File file) {
        return fromFile(SleeveStore.directory(), file);
    }

    public static SleeveStore.Result fromUrl(final String url) {
        return fromUrl(SleeveStore.directory(), url);
    }

    public static SleeveStore.Result fromFile(final File dir, final File file) {
        if (file == null || !file.isFile()) {
            return SleeveStore.rejected("that file could not be read");
        }
        if (file.length() > CustomSleeves.MAX_SOURCE_BYTES) {
            // No point decoding something that large just to discover it is a poster
            return SleeveStore.rejected("that file is larger than "
                    + (CustomSleeves.MAX_SOURCE_BYTES / (1024 * 1024)) + " MB");
        }
        try {
            return store(dir, Files.readAllBytes(file.toPath()));
        } catch (final IOException e) {
            return SleeveStore.rejected("could not read that file: " + e.getMessage());
        }
    }

    public static SleeveStore.Result fromUrl(final File dir, final String url) {
        // The source budget, not the sleeve budget: a link to a 4 MB photo is downscaled just as
        // a picked 4 MB photo is. These two paths used to disagree, and a link was refused for
        // being exactly the size a file was accepted at.
        final SleeveStore.Download download = SleeveStore.download(url, CustomSleeves.MAX_SOURCE_BYTES);
        if (download.error != null) {
            return SleeveStore.rejected(download.error);
        }
        return store(dir, download.bytes);
    }

    private static SleeveStore.Result store(final File dir, final byte[] raw) {
        final Prepared prepared = prepare(raw);
        return prepared.error != null ? SleeveStore.rejected(prepared.error) : SleeveStore.save(dir, prepared.bytes);
    }

    private static final class Prepared {
        private final byte[] bytes;
        private final String error;

        private Prepared(final byte[] bytes0, final String error0) {
            bytes = bytes0;
            error = error0;
        }
    }

    private static Prepared prepare(final byte[] raw) {
        if (CustomSleeves.probe(raw).accepted()) {
            return new Prepared(raw, null); // already within every bound; keep the original bytes
        }
        final long pixels = sourcePixels(raw);
        if (pixels < 0) {
            return new Prepared(null, "that does not look like an image we can read");
        }
        if (pixels > CustomSleeves.MAX_SOURCE_PIXELS) {
            // Checked from the header: a bomb is a small file that becomes a huge raster, so this
            // has to happen before the decode, not after it
            return new Prepared(null, "that image is " + (pixels / 1_000_000) + " megapixels; the limit is "
                    + (CustomSleeves.MAX_SOURCE_PIXELS / 1_000_000));
        }
        final BufferedImage decoded = decode(raw);
        if (decoded == null) {
            return new Prepared(null, "that does not look like an image we can read");
        }
        if (decoded.getWidth() < CustomSleeves.MIN_DIMENSION || decoded.getHeight() < CustomSleeves.MIN_DIMENSION) {
            return new Prepared(null, "that image is smaller than "
                    + CustomSleeves.MIN_DIMENSION + "x" + CustomSleeves.MIN_DIMENSION);
        }
        BufferedImage image = fitWithin(decoded, CustomSleeves.MAX_DIMENSION);
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            final byte[] encoded = attempt == 0
                    ? encodePng(image)
                    : encodeJpeg(image, JPEG_QUALITIES[Math.min(attempt - 1, JPEG_QUALITIES.length - 1)]);
            if (encoded != null && CustomSleeves.probe(encoded).accepted()) {
                return new Prepared(encoded, null);
            }
            if (attempt >= JPEG_QUALITIES.length) {
                final int longEdge = Math.max(image.getWidth(), image.getHeight());
                final int smaller = Math.max(CustomSleeves.MIN_DIMENSION, (int) (longEdge * SHRINK));
                if (smaller >= longEdge) {
                    break; // cannot get any smaller; stop rather than spin
                }
                image = fitWithin(image, smaller);
            }
        }
        return new Prepared(null, "that image could not be compressed under "
                + (CustomSleeves.MAX_BYTES / 1024) + " KB");
    }

    /** Width x height straight from the header, without decoding, or -1 if nothing can read it. */
    private static long sourcePixels(final byte[] raw) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            if (in == null) {
                return -1;
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return -1;
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return (long) reader.getWidth(0) * reader.getHeight(0);
            } finally {
                reader.dispose();
            }
        } catch (final IOException | RuntimeException e) {
            return -1;
        }
    }

    private static BufferedImage decode(final byte[] raw) {
        try {
            return ImageIO.read(new ByteArrayInputStream(raw));
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static BufferedImage fitWithin(final BufferedImage source, final int max) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (width <= max && height <= max) {
            return source;
        }
        final double scale = Math.min(max / (double) width, max / (double) height);
        final int targetWidth = Math.max(1, (int) Math.round(width * scale));
        final int targetHeight = Math.max(1, (int) Math.round(height * scale));
        final BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    private static byte[] encodePng(final BufferedImage image) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            return ImageIO.write(image, "png", out) ? out.toByteArray() : null;
        } catch (final IOException e) {
            return null;
        }
    }

    private static byte[] encodeJpeg(final BufferedImage image, final float quality) {
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            return null;
        }
        final ImageWriter writer = writers.next();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(flatten(image), null, null), param);
            stream.flush();
            return out.toByteArray();
        } catch (final IOException e) {
            return null;
        } finally {
            writer.dispose();
        }
    }

    /** JPEG has no alpha channel, so compose onto white before handing it to the writer. */
    private static BufferedImage flatten(final BufferedImage image) {
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        final BufferedImage opaque = new BufferedImage(image.getWidth(), image.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = opaque.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();
        return opaque;
    }
}
