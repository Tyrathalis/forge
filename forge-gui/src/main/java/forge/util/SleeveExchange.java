package forge.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import forge.localinstance.properties.ForgeConstants;

/**
 * The receiving half of sleeve sharing: what a client will accept when another player offers it a
 * sleeve, and where borrowed sleeves live while a game lasts.
 *
 * <p>Peers exchange validated bytes, never addresses. A client is therefore never asked to fetch
 * anything from a host somebody else chose, and the only peer-controlled bytes in the system
 * arrive here, where three things must hold before they are written down:
 *
 * <ol>
 *   <li>the key is well formed - 64 hex characters, so it can only ever name a file inside the
 *       session directory;</li>
 *   <li>the bytes pass exactly the probe a local import passes - format allowlist, byte cap and
 *       header dimensions, all before any decoder sees them;</li>
 *   <li>the content hashes to the name it arrived under, so "here is sleeve X" cannot be answered
 *       with some other picture.</li>
 * </ol>
 *
 * <p>Accepted sleeves land in a session directory under the cache, never in the library: a
 * borrowed image is not quietly added to your own collection, and it does not survive a restart to
 * be decoded again. {@link #clearSession()} runs at startup so a crash cannot leave one behind.
 */
public final class SleeveExchange {
    private SleeveExchange() {}

    /** Bounds what a table full of players can leave on your disk in one sitting. */
    public static final int MAX_SESSION_SLEEVES = 32;

    public static File sessionDirectory() {
        return new File(ForgeConstants.CACHE_SLEEVES_SESSION_DIR);
    }

    /** Drop every borrowed sleeve. Called at startup; safe to call at any time. */
    public static void clearSession() {
        clearSession(sessionDirectory());
    }

    public static void clearSession(final File dir) {
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return;
        }
        for (final File f : files) {
            if (f.isFile()) {
                f.delete();
            }
        }
    }

    /** Whether this sleeve is already in hand, either as our own or as one borrowed this session. */
    public static boolean have(final String key) {
        return SleeveStore.fileFor(key) != null;
    }

    /** Whether this sleeve is in the given directory. The no-argument form is the global answer. */
    public static boolean have(final File dir, final String key) {
        return SleeveStore.fileFor(dir, key) != null;
    }

    public static String accept(final String key, final byte[] bytes) {
        return accept(sessionDirectory(), key, bytes);
    }

    /**
     * Take a sleeve offered by another player. Returns null when it was accepted, otherwise the
     * reason it was refused - fit for a log line, and never shown as an error to the person who
     * merely sat down at the table.
     */
    public static String accept(final File sessionDir, final String key, final byte[] bytes) {
        final String hash = CustomSleeves.hashFromKey(key);
        if (hash.isEmpty()) {
            return "not a custom sleeve key";
        }
        if (SleeveStore.fileFor(sessionDir, key) != null) {
            return null; // already in hand; a repeat offer is not an error
        }
        final CustomSleeves.Probe probe = CustomSleeves.probe(bytes);
        if (!probe.accepted()) {
            return probe.rejection;
        }
        if (!CustomSleeves.sha256Hex(bytes).equals(hash)) {
            return "the image does not match the sleeve it claims to be";
        }
        if (countSession(sessionDir) >= MAX_SESSION_SLEEVES) {
            return "too many borrowed sleeves this session";
        }
        try {
            FileUtil.ensureDirectoryExists(sessionDir);
            Files.write(new File(sessionDir, hash + probe.format.extension()).toPath(), bytes);
        } catch (final IOException e) {
            return "could not store the sleeve: " + e.getMessage();
        }
        return null;
    }

    private static int countSession(final File dir) {
        final File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (final File f : files) {
            if (f.isFile()) {
                count++;
            }
        }
        return count;
    }
}
