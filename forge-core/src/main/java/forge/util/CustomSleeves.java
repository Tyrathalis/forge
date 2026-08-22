package forge.util;

import java.security.MessageDigest;

import forge.ImageKeys;

/**
 * Validation and identity for custom deck sleeves - user-supplied images, picked from a file or
 * imported from a URL, saved locally and shared with other players in-band during online play.
 *
 * <p>Everything here is deliberately platform-free: it reads bytes and never decodes them, so one
 * set of rules covers desktop (ImageIO) and mobile (libgdx Pixmap, which is stb_image - native C
 * bundled in the APK and patched only when we rebump libgdx). A custom sleeve is the only path by
 * which a peer's bytes reach an image decoder at all, so the order matters: cap the byte count,
 * identify the format from its magic bytes, then read the dimensions out of the header - all
 * before anything allocates a pixel buffer.
 *
 * <p>The format allowlist is the cheapest guard here. stb_image also parses BMP, TGA, PSD, GIF,
 * HDR and PIC; we accept none of them, which removes most of its parser surface for two magic-byte
 * checks. Note that TGA has no magic bytes at all - it is recognised by elimination - which is its
 * own argument for allowlisting rather than blocklisting.
 *
 * <p>Identity is the SHA-256 of the accepted bytes. That gives content-addressing for free: the
 * key that travels the wire names the bytes rather than a location, so every player verifiably
 * sees the same sleeve, the cache can never be poisoned, and a filename is never attacker-chosen.
 * {@link #hashFromKey} is the chokepoint for that last property and is deliberately strict.
 */
public final class CustomSleeves {
    private CustomSleeves() {}

    /** Generous for a 360x500 sleeve, small enough to be unremarkable on the wire. */
    public static final int MAX_BYTES = 256 * 1024;
    /** Checked against the header, so a small file declaring a huge canvas never allocates one. */
    public static final int MAX_DIMENSION = 1024;
    /** Below this it is not a sleeve, it is a probe for a decoder bug. */
    public static final int MIN_DIMENSION = 16;
    /** A JPEG walk is a loop over attacker-shaped input; it gets a hard bound, not a hope. */
    private static final int MAX_JPEG_SEGMENTS = 1024;

    /**
     * What an import may take <i>in</i>, as opposed to what a sleeve may be. A source is chosen by
     * the local user - a photo, a scan, a download they asked for - and is downscaled and
     * re-encoded until it fits {@link #MAX_BYTES}; refusing it up front for being big would refuse
     * every phone photo. Both import paths use this, so picking a file and pasting its address
     * behave the same way.
     */
    public static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;
    /**
     * Pixels a source may declare, checked from the header before any raster is allocated. A
     * 30000x30000 PNG is a small download and a 3.6 GB decode; 50 MP clears a 48 MP phone camera
     * with room to spare.
     */
    public static final long MAX_SOURCE_PIXELS = 50_000_000L;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int HASH_LENGTH = 64;

    public enum Format {
        PNG(".png"),
        JPEG(".jpg");

        private final String extension;

        Format(final String extension0) {
            extension = extension0;
        }

        public String extension() {
            return extension;
        }
    }

    /** The outcome of inspecting a candidate image: accepted with its shape, or rejected with why. */
    public static final class Probe {
        public final Format format;
        public final int width;
        public final int height;
        /** Null when accepted; otherwise a reason fit for a log line or a dialog. */
        public final String rejection;

        private Probe(final Format format0, final int width0, final int height0, final String rejection0) {
            format = format0;
            width = width0;
            height = height0;
            rejection = rejection0;
        }

        public boolean accepted() {
            return rejection == null;
        }
    }

    private static Probe reject(final String reason) {
        return new Probe(null, 0, 0, reason);
    }

    /**
     * Inspect candidate image bytes without decoding them. Never throws: every malformed,
     * oversized or unrecognised input comes back as a rejection carrying its reason.
     */
    public static Probe probe(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return reject("no image data");
        }
        if (bytes.length > MAX_BYTES) {
            return reject("image is " + bytes.length + " bytes; the limit is " + MAX_BYTES);
        }
        final Probe shape = shapeOf(bytes);
        if (!shape.accepted()) {
            return shape;
        }
        return dimensions(shape.format, shape.width, shape.height);
    }

    /**
     * Inspect bytes offered as an import <i>source</i>: same format allowlist and same
     * header-first discipline as {@link #probe}, but judged against the source budgets, since a
     * source is downscaled into a sleeve rather than used as one. Both clients gate imports on
     * this, so picking a file and pasting a link accept exactly the same set of images.
     */
    public static Probe probeSource(final byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return reject("no image data");
        }
        if (bytes.length > MAX_SOURCE_BYTES) {
            return reject("that image is larger than " + (MAX_SOURCE_BYTES / (1024 * 1024)) + " MB");
        }
        final Probe shape = shapeOf(bytes);
        if (!shape.accepted()) {
            return shape; // unreadable or unrecognised; the reason already says which
        }
        final long pixels = (long) shape.width * shape.height;
        if (shape.width < MIN_DIMENSION || shape.height < MIN_DIMENSION) {
            return reject("that image is smaller than " + MIN_DIMENSION + "x" + MIN_DIMENSION);
        }
        if (pixels > MAX_SOURCE_PIXELS) {
            return reject("that image is " + (pixels / 1_000_000) + " megapixels; the limit is "
                    + (MAX_SOURCE_PIXELS / 1_000_000));
        }
        return new Probe(shape.format, shape.width, shape.height, null);
    }

    /**
     * Format and dimensions with no size judgement at all - the shared half of {@link #probe} and
     * {@link #probeSource}, which differ only in the budgets they hold the answer to.
     */
    private static Probe shapeOf(final byte[] bytes) {
        if (startsWith(bytes, PNG_MAGIC)) {
            return shapePng(bytes);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return shapeJpeg(bytes);
        }
        return reject("unrecognised image format; only PNG and JPEG are accepted");
    }

    private static Probe shapePng(final byte[] b) {
        // signature(8) + chunk length(4) + "IHDR"(4) + width(4) + height(4)
        if (b.length < 24) {
            return reject("truncated PNG header");
        }
        if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
            return reject("malformed PNG: first chunk is not IHDR");
        }
        return shape(Format.PNG, readInt(b, 16), readInt(b, 20));
    }

    private static Probe shapeJpeg(final byte[] b) {
        int pos = 2; // past SOI
        for (int segment = 0; segment < MAX_JPEG_SEGMENTS; segment++) {
            if (pos >= b.length || (b[pos] & 0xFF) != 0xFF) {
                return reject("malformed JPEG: expected a marker");
            }
            while (pos < b.length && (b[pos] & 0xFF) == 0xFF) {
                pos++; // fill bytes are legal before a marker code
            }
            if (pos >= b.length) {
                return reject("truncated JPEG: marker ran off the end");
            }
            final int marker = b[pos] & 0xFF;
            pos++;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
                continue; // standalone markers carry no payload
            }
            if (marker == 0xD9 || marker == 0xDA) {
                return reject("JPEG ended before any frame header"); // EOI, or the scan itself
            }
            if (pos + 1 >= b.length) {
                return reject("truncated JPEG segment");
            }
            final int length = ((b[pos] & 0xFF) << 8) | (b[pos + 1] & 0xFF);
            if (length < 2) {
                // The length counts its own two bytes, so anything under 2 walks backwards.
                return reject("malformed JPEG: segment length " + length);
            }
            if (isFrameHeader(marker)) {
                // length(2) precision(1) height(2) width(2)
                if (pos + 7 > b.length) {
                    return reject("truncated JPEG frame header");
                }
                final int height = ((b[pos + 3] & 0xFF) << 8) | (b[pos + 4] & 0xFF);
                final int width = ((b[pos + 5] & 0xFF) << 8) | (b[pos + 6] & 0xFF);
                return shape(Format.JPEG, width, height);
            }
            pos += length;
        }
        return reject("malformed JPEG: segment list did not terminate");
    }

    /** SOF0-SOF15, excluding the three markers that share the range but are not frame headers. */
    private static boolean isFrameHeader(final int marker) {
        return marker >= 0xC0 && marker <= 0xCF
                && marker != 0xC4  // DHT
                && marker != 0xC8  // JPG
                && marker != 0xCC; // DAC
    }

    /** A parsed header: format and dimensions, no budget applied yet. */
    private static Probe shape(final Format format, final int width, final int height) {
        if (width <= 0 || height <= 0) {
            return reject("malformed image header: " + width + "x" + height);
        }
        return new Probe(format, width, height, null);
    }

    private static Probe dimensions(final Format format, final int width, final int height) {
        if (width <= 0 || height <= 0) {
            return reject("malformed image header: " + width + "x" + height);
        }
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            return reject("image is " + width + "x" + height + "; the minimum is "
                    + MIN_DIMENSION + "x" + MIN_DIMENSION);
        }
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            return reject("image is " + width + "x" + height + "; the maximum is "
                    + MAX_DIMENSION + "x" + MAX_DIMENSION);
        }
        return new Probe(format, width, height, null);
    }

    private static boolean startsWith(final byte[] b, final byte[] magic) {
        if (b.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (b[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    private static int readInt(final byte[] b, final int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16)
                | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    //--- identity

    public static String sha256Hex(final byte[] bytes) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (final byte h : hash) {
                sb.append(Character.forDigit((h >> 4) & 0xF, 16)).append(Character.forDigit(h & 0xF, 16));
            }
            return sb.toString();
        } catch (final Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String keyFor(final String hash) {
        return ImageKeys.CUSTOM_SLEEVE_PREFIX + hash;
    }

    public static boolean isCustomSleeveKey(final String key) {
        return key != null && key.startsWith(ImageKeys.CUSTOM_SLEEVE_PREFIX) && !hashFromKey(key).isEmpty();
    }

    /**
     * The hash a key names, or "" if the key is not a well-formed custom-sleeve key. This is the
     * chokepoint that keeps a wire-supplied key from becoming a path: only 64 lowercase hex
     * characters get through, so no separator, traversal segment or extension ever reaches a
     * filename.
     */
    public static String hashFromKey(final String key) {
        if (key == null || !key.startsWith(ImageKeys.CUSTOM_SLEEVE_PREFIX)) {
            return "";
        }
        final String hash = key.substring(ImageKeys.CUSTOM_SLEEVE_PREFIX.length());
        if (hash.length() != HASH_LENGTH) {
            return "";
        }
        for (int i = 0; i < hash.length(); i++) {
            final char c = hash.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return "";
            }
        }
        return hash;
    }

    public static String fileName(final String hash, final Format format) {
        return hash + format.extension();
    }

    /**
     * The stored filename stem for a key - the hash, no extension, since a sleeve may be stored as
     * either format - or null if the key is not one we will build a path from at all.
     */
    public static String stem(final String key) {
        final String hash = hashFromKey(key);
        return hash.isEmpty() ? null : hash;
    }
}
