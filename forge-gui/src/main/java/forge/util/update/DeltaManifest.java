package forge.util.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The per-file manifest a playable-fork release publishes next to its package
 * (playable-fork worklist items 1+2). Plain text, one file per line:
 *
 * <pre>
 * #forge-playable-manifest v1
 * #version 2.0.14-PLAYABLE
 * #commit 0123abcd...
 * #jar forge-playable.jar
 * &lt;sha256&gt;\t&lt;size&gt;\t&lt;path&gt;
 * </pre>
 *
 * Paths are relative to the install root; the launcher jar is the manifest
 * entry named by the #jar header, everything else lives under res/. Kept
 * deliberately not-JSON: line-per-file diffs cleanly and needs no parser.
 *
 * <p><b>Bridge format (the 07.31 URL-encoding incident):</b> parsers shipped
 * through 07.29 build raw-file URLs without percent-encoding, so any delta
 * touching a path with a space aborts - those clients can only ever complete a
 * jar-only plan (the jar is a release asset; its URL needs no encoding). A
 * bridge manifest therefore lists ONLY the jar as a plain entry, and carries
 * the full file list on {@code #2 }-prefixed lines, which old parsers skip as
 * unknown headers. This parser prefers the {@code #2 } set when present, so a
 * fixed client sees the full delta while a legacy client hops the jar first
 * and completes res/ on its next boot (the self-heal pass).
 */
public final class DeltaManifest {
    public static final String HEADER = "#forge-playable-manifest v1";
    /** Prefix of full-entry lines in a bridge manifest; invisible to ≤07.29 parsers. */
    public static final String V2_ENTRY_PREFIX = "#2 ";

    public record Entry(String sha256, long size, String path) {
    }

    private final String version;
    private final String commit;
    private final String jarPath;
    private final Map<String, Entry> entries;

    private DeltaManifest(String version, String commit, String jarPath, Map<String, Entry> entries) {
        this.version = version;
        this.commit = commit;
        this.jarPath = jarPath;
        this.entries = entries;
    }

    public String getVersion() {
        return version;
    }

    /** The fork commit the release was cut from - the ref for raw-file fetches. */
    public String getCommit() {
        return commit;
    }

    /** Manifest path of the launcher jar entry (also the release asset name). */
    public String getJarPath() {
        return jarPath;
    }

    public Iterable<Entry> getEntries() {
        return entries.values();
    }

    public Entry getEntry(String path) {
        return entries.get(path);
    }

    public static DeltaManifest parse(String text) throws IOException {
        String version = null, commit = null, jarPath = null;
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final Map<String, Entry> v2Entries = new LinkedHashMap<>();
        boolean headerSeen = false;
        for (final String rawLine : text.split("\n")) {
            final String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.equals(HEADER)) {
                    headerSeen = true;
                } else if (line.startsWith("#version ")) {
                    version = line.substring("#version ".length()).strip();
                } else if (line.startsWith("#commit ")) {
                    commit = line.substring("#commit ".length()).strip();
                } else if (line.startsWith("#jar ")) {
                    jarPath = line.substring("#jar ".length()).strip();
                } else if (line.startsWith(V2_ENTRY_PREFIX)) {
                    addEntry(line.substring(V2_ENTRY_PREFIX.length()), v2Entries);
                }
                continue; //unknown # lines are future headers - skip, don't fail
            }
            addEntry(line, entries);
        }
        if (!headerSeen) {
            throw new IOException("Not a playable-fork delta manifest");
        }
        if (entries.isEmpty() && v2Entries.isEmpty()) {
            throw new IOException("Empty delta manifest");
        }
        //a bridge manifest's plain entries are the legacy jar-only hop; the #2 set is the truth
        return new DeltaManifest(version, commit, jarPath, v2Entries.isEmpty() ? entries : v2Entries);
    }

    private static void addEntry(final String line, final Map<String, Entry> into) throws IOException {
        final String[] parts = line.split("\t");
        if (parts.length != 3) {
            throw new IOException("Malformed manifest line: " + line);
        }
        final long size;
        try {
            size = Long.parseLong(parts[1]);
        } catch (final NumberFormatException ex) {
            throw new IOException("Malformed manifest size: " + line);
        }
        final String path = parts[2];
        if (isUnsafePath(path)) {
            throw new IOException("Refusing unsafe manifest path: " + path);
        }
        into.put(path, new Entry(parts[0].toLowerCase(), size, path));
    }

    /** Traversal must be judged per path SEGMENT - real res files (wastetown..tmx)
     *  contain ".." as a substring and are legitimate. */
    private static boolean isUnsafePath(final String path) {
        if (path.isBlank() || path.startsWith("/") || path.contains("\\")) {
            return true;
        }
        for (final String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Files whose local copy is missing or differs. The resolver maps a
     * manifest path to its local file - the jar and res/ live under different
     * roots in a real install. Size is checked first; sha256 only when sizes
     * agree, so an unchanged install costs one hash pass and a changed file
     * costs none.
     */
    public List<Entry> diffAgainst(Function<String, File> localFileResolver) throws IOException {
        return diffAgainst(localFileResolver, false);
    }

    /**
     * sizeOnly skips hashing when sizes agree - the cheap consistency probe the
     * self-heal boot pass uses (a full hash pass over ~2GB of res is too slow
     * to run speculatively; a same-size content change is caught by the next
     * release's normal, hash-verified delta).
     */
    public List<Entry> diffAgainst(Function<String, File> localFileResolver, boolean sizeOnly) throws IOException {
        final List<Entry> changed = new ArrayList<>();
        for (final Entry entry : entries.values()) {
            final File local = localFileResolver.apply(entry.path());
            if (local == null || !local.isFile() || local.length() != entry.size()
                    || (!sizeOnly && !sha256(local).equals(entry.sha256()))) {
                changed.add(entry);
            }
        }
        return changed;
    }

    public static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IOException(ex); //JVMs are required to ship SHA-256
        }
        try (InputStream in = new FileInputStream(file)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        final StringBuilder hex = new StringBuilder();
        for (final byte b : digest.digest()) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
