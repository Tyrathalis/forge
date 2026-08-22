package forge.card;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;

import forge.util.CustomSleeves;
import forge.util.SleeveStore;

/**
 * Brings an image into the custom sleeve store on the libgdx clients, mirroring the desktop
 * importer. Both gate on {@link CustomSleeves#probeSource}, so a file and a link accept exactly
 * the same images on either client - the header is read, and the format allowlist, byte budget and
 * pixel budget are all applied, before stb_image is handed anything.
 *
 * <p>There is no JPEG encoder here - libgdx writes PNG only - so an oversize source is shrunk
 * until its PNG fits the sleeve budget rather than traded down in quality. A sleeve is drawn at a
 * few hundred pixels, so the floor this reaches is still far above what is displayed.
 *
 * <p>Since neither client can open a system file picker, {@link #adoptDropped} is the file path:
 * drop images into the sleeves folder under any name, and they are taken into the library the next
 * time the picker opens. Importing is idempotent - the same image is always the same entry - so a
 * file left in place costs a re-read, never a duplicate.
 */
public final class CustomSleeveImport {
    private CustomSleeveImport() {}

    private static final int MAX_ATTEMPTS = 6;
    private static final double SHRINK = 0.75;
    /** Bounds the work one picker open can trigger, however many files were dropped. */
    private static final int MAX_ADOPTED_PER_SCAN = 16;

    public static SleeveStore.Result fromUrl(final String url) {
        return fromUrl(SleeveStore.directory(), url);
    }

    public static SleeveStore.Result fromUrl(final File dir, final String url) {
        final SleeveStore.Download download = SleeveStore.download(url, CustomSleeves.MAX_SOURCE_BYTES);
        if (download.error != null) {
            return SleeveStore.rejected(download.error);
        }
        return store(dir, download.bytes);
    }

    public static SleeveStore.Result fromFile(final File dir, final File file) {
        if (file == null || !file.isFile()) {
            return SleeveStore.rejected("that file could not be read");
        }
        if (file.length() > CustomSleeves.MAX_SOURCE_BYTES) {
            return SleeveStore.rejected("that file is larger than "
                    + (CustomSleeves.MAX_SOURCE_BYTES / (1024 * 1024)) + " MB");
        }
        try {
            return store(dir, Files.readAllBytes(file.toPath()));
        } catch (final IOException e) {
            return SleeveStore.rejected("could not read that file: " + e.getMessage());
        }
    }

    /**
     * Take any loose images sitting in the sleeves folder into the library. Returns the keys of
     * everything now held, dropped files included.
     */
    public static List<String> adoptDropped(final File dir) {
        final List<String> adopted = new ArrayList<>();
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return adopted;
        }
        int scanned = 0;
        for (final File f : files) {
            if (scanned >= MAX_ADOPTED_PER_SCAN) {
                break;
            }
            if (!f.isFile() || CustomSleeves.stem(CustomSleeves.keyFor(nameWithoutExtension(f))) != null) {
                continue; // already one of ours: its name is its hash
            }
            final String lower = f.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                continue;
            }
            scanned++;
            final SleeveStore.Result result = fromFile(dir, f);
            if (result.key != null) {
                adopted.add(result.key);
            }
        }
        return adopted;
    }

    private static String nameWithoutExtension(final File f) {
        final String name = f.getName();
        final int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static SleeveStore.Result store(final File dir, final byte[] raw) {
        final CustomSleeves.Probe source = CustomSleeves.probeSource(raw);
        if (!source.accepted()) {
            return SleeveStore.rejected(source.rejection);
        }
        if (CustomSleeves.probe(raw).accepted()) {
            return SleeveStore.save(dir, raw); // already a legal sleeve; keep the original bytes
        }
        final byte[] normalized = normalize(raw, source);
        if (normalized == null) {
            return SleeveStore.rejected("that image could not be reduced to "
                    + (CustomSleeves.MAX_BYTES / 1024) + " KB");
        }
        return SleeveStore.save(dir, normalized);
    }

    private static byte[] normalize(final byte[] raw, final CustomSleeves.Probe source) {
        Pixmap decoded = null;
        try {
            decoded = new Pixmap(raw, 0, raw.length);
        } catch (final RuntimeException e) {
            return null;
        }
        Pixmap current = fitWithin(decoded, CustomSleeves.MAX_DIMENSION);
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                final byte[] encoded = encodePng(current);
                if (encoded != null && CustomSleeves.probe(encoded).accepted()) {
                    return encoded;
                }
                final int longEdge = Math.max(current.getWidth(), current.getHeight());
                final int smaller = Math.max(CustomSleeves.MIN_DIMENSION, (int) (longEdge * SHRINK));
                if (smaller >= longEdge) {
                    return null; // cannot get any smaller; stop rather than spin
                }
                final Pixmap next = fitWithin(current, smaller);
                if (current != decoded) {
                    current.dispose();
                }
                current = next;
            }
            return null;
        } finally {
            if (current != decoded) {
                current.dispose();
            }
            decoded.dispose();
        }
    }

    private static Pixmap fitWithin(final Pixmap source, final int max) {
        final int width = source.getWidth();
        final int height = source.getHeight();
        if (width <= max && height <= max) {
            return source;
        }
        final double scale = Math.min(max / (double) width, max / (double) height);
        final int targetWidth = Math.max(1, (int) Math.round(width * scale));
        final int targetHeight = Math.max(1, (int) Math.round(height * scale));
        final Pixmap scaled = new Pixmap(targetWidth, targetHeight, Pixmap.Format.RGBA8888);
        source.setFilter(Pixmap.Filter.BiLinear);
        scaled.drawPixmap(source, 0, 0, width, height, 0, 0, targetWidth, targetHeight);
        return scaled;
    }

    private static byte[] encodePng(final Pixmap pixmap) {
        final PixmapIO.PNG writer = new PixmapIO.PNG();
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.setFlipY(false);
            writer.write(out, pixmap);
            return out.toByteArray();
        } catch (final IOException e) {
            return null;
        } finally {
            writer.dispose();
        }
    }
}
