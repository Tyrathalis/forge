package forge.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import forge.localinstance.properties.ForgeConstants;

/**
 * The on-disk library of custom deck sleeves, and the import path that fills it.
 *
 * <p>Sleeves are stored by content hash under {@link ForgeConstants#USER_SLEEVES_DIR}, so the file
 * name is derived from the bytes and never from anything a person or a peer typed. Stored bytes
 * are the exact bytes {@link CustomSleeves#probe} accepted: that is what lets the same blob be
 * handed to another player with its hash as a verifiable name.
 *
 * <p><b>Invariant worth keeping:</b> {@link #download} is only ever called on an address the local
 * user typed or picked. A URL that arrives from another player is never fetched - peers exchange
 * validated bytes in-band, not locations - which is what keeps this feature from turning every
 * client into a beacon aimed at a host someone else chose.
 */
public final class SleeveStore {
    private SleeveStore() {}

    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int READ_CHUNK = 8192;
    /** Bounds the read loop itself, so a stream that never advances still terminates. */
    private static final int MAX_READ_ITERATIONS = 4 * (CustomSleeves.MAX_BYTES / READ_CHUNK) + 64;

    /** A save attempt: a key, or the reason the image was refused. Never both. */
    public static final class Result {
        public final String key;
        public final String error;

        private Result(final String key0, final String error0) {
            key = key0;
            error = error0;
        }
    }

    /** A fetch attempt: the raw bytes, or the reason there are none. Never both. */
    public static final class Download {
        public final byte[] bytes;
        public final String error;

        private Download(final byte[] bytes0, final String error0) {
            bytes = bytes0;
            error = error0;
        }
    }

    public static File directory() {
        return new File(ForgeConstants.USER_SLEEVES_DIR);
    }

    //--- storage

    /**
     * Validate and store an image, returning the key that names it. Saving the same image twice is
     * one entry: the hash is the identity, so the second save simply finds the first.
     */
    public static Result save(final File dir, final byte[] bytes) {
        final CustomSleeves.Probe probe = CustomSleeves.probe(bytes);
        if (!probe.accepted()) {
            return new Result(null, probe.rejection);
        }
        final String hash = CustomSleeves.sha256Hex(bytes);
        final String key = CustomSleeves.keyFor(hash);
        if (fileFor(dir, key) != null) {
            return new Result(key, null);
        }
        try {
            FileUtil.ensureDirectoryExists(dir);
            Files.write(new File(dir, hash + probe.format.extension()).toPath(), bytes);
        } catch (final IOException e) {
            return new Result(null, "could not save the sleeve: " + e.getMessage());
        }
        return new Result(key, null);
    }

    public static Result save(final byte[] bytes) {
        return save(directory(), bytes);
    }

    /** The stored file for a key, or null - for a malformed key, or when nothing is stored. */
    public static File fileFor(final File dir, final String key) {
        final String stem = CustomSleeves.stem(key);
        if (stem == null || dir == null) {
            return null;
        }
        for (final CustomSleeves.Format format : CustomSleeves.Format.values()) {
            final File f = new File(dir, stem + format.extension());
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    public static File fileFor(final String key) {
        return fileFor(directory(), key);
    }

    /**
     * The stored bytes for a key, or null. Re-checks the byte cap on the way out: the store is an
     * ordinary directory a person can drop files into, and everything leaving here may be shared.
     */
    public static byte[] read(final File dir, final String key) {
        final File f = fileFor(dir, key);
        if (f == null || f.length() > CustomSleeves.MAX_BYTES) {
            return null;
        }
        try {
            return Files.readAllBytes(f.toPath());
        } catch (final IOException e) {
            return null;
        }
    }

    public static byte[] read(final String key) {
        return read(directory(), key);
    }

    /** Every well-formed sleeve in the store, newest first. Anything else in the directory is ignored. */
    public static List<String> keys(final File dir) {
        final List<String> keys = new ArrayList<>();
        if (dir == null) {
            return keys;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return keys;
        }
        final List<File> ordered = new ArrayList<>();
        for (final File f : files) {
            if (f.isFile() && CustomSleeves.stem(keyForFile(f)) != null) {
                ordered.add(f);
            }
        }
        ordered.sort(Comparator.comparingLong(File::lastModified).reversed());
        final Set<String> seen = new LinkedHashSet<>();
        for (final File f : ordered) {
            seen.add(keyForFile(f));
        }
        keys.addAll(seen);
        return keys;
    }

    public static List<String> keys() {
        return keys(directory());
    }

    /** The key a stored file would carry, or "" when its name is not one we wrote. */
    private static String keyForFile(final File f) {
        final String name = f.getName();
        final int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "";
        }
        final String extension = name.substring(dot).toLowerCase(Locale.ROOT);
        boolean known = false;
        for (final CustomSleeves.Format format : CustomSleeves.Format.values()) {
            known |= format.extension().equals(extension);
        }
        return known ? CustomSleeves.keyFor(name.substring(0, dot)) : "";
    }

    public static boolean delete(final File dir, final String key) {
        final File f = fileFor(dir, key);
        return f != null && f.delete();
    }

    public static boolean delete(final String key) {
        return delete(directory(), key);
    }

    //--- import

    /**
     * Fetch an image the local user asked for, bounded in time and in bytes. Accepts http, https
     * and file - a local file being exactly what the picker already offers, and what lets this be
     * tested without a network.
     *
     * <p>Never call this with an address supplied by another player. See the class note.
     */
    public static Download download(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return new Download(null, "no address given");
        }
        String current = url.trim();
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            final URI uri;
            try {
                uri = new URI(current);
            } catch (final Exception e) {
                return new Download(null, "not a valid address: " + current);
            }
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme) && !"file".equals(scheme)) {
                return new Download(null, "only http, https and local files are supported");
            }
            try {
                final URLConnection conn = uri.toURL().openConnection();
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                if (conn instanceof HttpURLConnection) {
                    final HttpURLConnection http = (HttpURLConnection) conn;
                    // Redirects are followed by hand so each hop's scheme is checked again
                    http.setInstanceFollowRedirects(false);
                    final int status = http.getResponseCode();
                    if (status >= 300 && status < 400) {
                        final String location = http.getHeaderField("Location");
                        http.disconnect();
                        if (location == null || location.isEmpty()) {
                            return new Download(null, "the server redirected without a destination");
                        }
                        try {
                            current = uri.resolve(location).toString();
                        } catch (final Exception e) {
                            return new Download(null, "the server redirected somewhere unusable");
                        }
                        continue;
                    }
                    if (status != HttpURLConnection.HTTP_OK) {
                        return new Download(null, "the server returned " + status);
                    }
                }
                if (conn.getContentLengthLong() > CustomSleeves.MAX_BYTES) {
                    return new Download(null, "that image is larger than " + CustomSleeves.MAX_BYTES + " bytes");
                }
                try (InputStream in = conn.getInputStream()) {
                    return readBounded(in);
                }
            } catch (final IOException e) {
                return new Download(null, "could not fetch that address: " + e.getMessage());
            }
        }
        return new Download(null, "too many redirects");
    }

    private static Download readBounded(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[READ_CHUNK];
        for (int iteration = 0; iteration < MAX_READ_ITERATIONS; iteration++) {
            final int read = in.read(buffer);
            if (read < 0) {
                return new Download(out.toByteArray(), null);
            }
            if (out.size() + read > CustomSleeves.MAX_BYTES) {
                return new Download(null, "that image is larger than " + CustomSleeves.MAX_BYTES + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return new Download(null, "that image did not finish arriving");
    }
}
