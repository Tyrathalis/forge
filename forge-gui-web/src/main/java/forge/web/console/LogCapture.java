package forge.web.console;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/** Copies what the process prints into the console's text pane, where errors and warnings are coloured. */
final class LogCapture {
    /** Enough lines to see what just happened without holding a whole session's output. */
    private static final int MAX_LINES = 300;
    private static final Pattern THROWN = Pattern.compile("^[\\w.$]+(Exception|Error)(:.*)?$");
    private static final Pattern LINE_END = Pattern.compile("(?<=\n)");
    // Red for errors and the stack traces under them, amber for warnings. Other lines use the log's own colour
    private static final SimpleAttributeSet PLAIN = new SimpleAttributeSet();
    private static final SimpleAttributeSet ERROR = colour(new Color(0xff, 0x6b, 0x5c));
    private static final SimpleAttributeSet WARNING = colour(new Color(0xf2, 0xc3, 0x44));

    private final StringBuilder pending = new StringBuilder();
    private final JTextPane text;
    private final Runnable onError;
    /** The colour of the last error or warning written. The stack trace under it is drawn in the same colour. */
    private SimpleAttributeSet carried = PLAIN;

    LogCapture(final JTextPane text, final Runnable onError) {
        this.text = text;
        this.onError = onError;
    }

    /** Everything the process prints is also copied to the pane. This is the log a player can actually see. */
    void captureOutput() {
        System.setOut(tee(System.out));
        System.setErr(tee(System.err));
    }

    private PrintStream tee(final PrintStream original) {
        // var keeps the anonymous type, so drain() can be called
        final var copyOf = new ByteArrayOutputStream() {
            void drain() {
                final String chunk = toString(StandardCharsets.UTF_8);
                reset();
                if (!chunk.isEmpty()) {
                    synchronized (pending) {
                        pending.append(chunk);
                    }
                }
            }
        };
        return new PrintStream(new OutputStream() {
            @Override
            public void write(final int b) {
                original.write(b);
                copyOf.write(b);
                if (b == '\n') {
                    copyOf.drain();
                }
            }

            // Card loading prints tens of thousands of lines. Writing one byte at a time is too slow for that
            @Override
            public void write(final byte[] bytes, final int off, final int len) {
                original.write(bytes, off, len);
                copyOf.write(bytes, off, len);
                copyOf.drain();
            }
        }, true, StandardCharsets.UTF_8);
    }

    boolean flush() {
        final String chunk;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return false;
            }
            chunk = pending.toString();
            pending.setLength(0);
        }
        final StyledDocument doc = text.getStyledDocument();
        try {
            final String[] lines = LINE_END.split(chunk);
            for (int i = 0; i < lines.length; i++) {
                final SimpleAttributeSet style = styleOf(lines[i]);
                if (lines[i].contains("[ERROR]")) {
                    onError.run();
                }
                // A line that trim() would remove at once is not inserted
                if (i >= lines.length - MAX_LINES) {
                    doc.insertString(doc.getLength(), lines[i], style);
                }
            }
        } catch (final BadLocationException e) {
            text.setText(chunk);
        }
        trim();
        text.setCaretPosition(doc.getLength());
        return true;
    }

    private static SimpleAttributeSet colour(final Color c) {
        final SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, c);
        return set;
    }

    /** A stack trace line keeps the colour of the error or warning above it. tinylog marks those lines as [ERROR] or [WARN ]. */
    private SimpleAttributeSet styleOf(final String line) {
        if (line.contains("[ERROR]")) {
            return carried = ERROR;
        }
        if (line.contains("[WARN")) {
            return carried = WARNING;
        }
        final String bare = line.strip();
        final boolean thrown = THROWN.matcher(bare).matches();
        final boolean trace = thrown || line.startsWith("\t") || line.startsWith(" ") || bare.startsWith("Caused by")
                || bare.startsWith("at ") || bare.startsWith("...");
        if (carried != PLAIN && (trace || bare.isEmpty())) {
            return carried;
        }
        return carried = thrown ? ERROR : PLAIN;
    }

    private void trim() {
        final StyledDocument doc = text.getStyledDocument();
        final Element root = doc.getDefaultRootElement();
        final int lines = root.getElementCount();
        if (lines <= MAX_LINES) {
            return;
        }
        try {
            doc.remove(0, root.getElement(lines - MAX_LINES - 1).getEndOffset());
        } catch (final BadLocationException e) {
            text.setText("");
        }
    }
}
