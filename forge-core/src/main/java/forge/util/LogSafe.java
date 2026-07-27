package forge.util;

/**
 * Neutralises text that came from somewhere else before it reaches a log line
 * or a chat broadcast.
 *
 * <p>Log files are read as one record per line, so a value containing a
 * newline can forge an entry: a chat message of
 * {@code "hi\n21:04:11 [INFO ] Server: host granted admin to mallory"} lands
 * in the log looking exactly like something the server said. The same trick
 * works on a name that is echoed into chat. Neither is dramatic on its own,
 * but it makes a log useless as evidence precisely when someone would want to
 * read it.
 *
 * <p>Two shapes, because the right answer differs:
 * {@link #forLog(String)} keeps the escapes visible so a reader can see what
 * was really sent, while {@link #forDisplay(String)} strips the control
 * characters outright since a UI has no use for them.
 */
public final class LogSafe {

    /** Generous for a name or a chat line; short enough to bound a flood. */
    public static final int DEFAULT_MAX_LENGTH = 512;

    private static final String TRUNCATED = "…[truncated]";

    private LogSafe() {
    }

    /**
     * Escape control characters and truncate, for text about to be logged.
     * Newlines become a literal {@code \n} rather than a new record.
     */
    public static String forLog(final String text) {
        return forLog(text, DEFAULT_MAX_LENGTH);
    }

    public static String forLog(final String text, final int maxLength) {
        if (text == null) {
            return null;
        }
        final String clipped = clip(text, maxLength);
        final StringBuilder out = new StringBuilder(clipped.length() + 8);
        for (int i = 0; i < clipped.length(); i++) {
            final char c = clipped.charAt(i);
            switch (c) {
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (isControl(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        if (clipped.length() < text.length()) {
            out.append(TRUNCATED);
        }
        return out.toString();
    }

    /**
     * Drop control characters and truncate, for text about to be shown to
     * other players. A chat line has no legitimate use for a carriage return,
     * and allowing one lets a player draw fake system messages in someone
     * else's chat pane.
     */
    public static String forDisplay(final String text) {
        return forDisplay(text, DEFAULT_MAX_LENGTH);
    }

    public static String forDisplay(final String text, final int maxLength) {
        if (text == null) {
            return null;
        }
        final String clipped = clip(text, maxLength);
        final StringBuilder out = new StringBuilder(clipped.length());
        for (int i = 0; i < clipped.length(); i++) {
            final char c = clipped.charAt(i);
            if (!isControl(c)) {
                out.append(c);
            }
        }
        if (clipped.length() < text.length()) {
            out.append(TRUNCATED);
        }
        return out.toString();
    }

    private static String clip(final String text, final int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    /** C0 controls, DEL, and the C1 range — none belong in a name or a chat line. */
    private static boolean isControl(final char c) {
        return c < 0x20 || (c >= 0x7F && c <= 0x9F);
    }
}
