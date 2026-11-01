package forge.web.console;

import forge.util.Localizer;
import forge.web.ServerTraffic;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import javax.swing.JComponent;

/** The last five minutes of bytes through the port. Bytes sent are stacked by kind, and bytes received are drawn as a dashed line. */
final class TrafficGraph extends JComponent {
    private static final Localizer TEXT = Localizer.getInstance();
    static final int SAMPLE_MILLIS = 1000;
    static final int HEIGHT = 130;
    static final Color BACKGROUND = new Color(0x10, 0x14, 0x1c);
    static final Color LABEL = new Color(0xc8, 0xd1, 0xdb);
    /** Indexed by {@link ServerTraffic.Kind}, and stacked in that order from the bottom. */
    static final String[] KIND_NAMES = {TEXT.getMessage("lblGame"),
            TEXT.getMessage("lblWebSleevesCardArt"), TEXT.getMessage("lblAudio"),
            TEXT.getMessage("lblWebConsoleKindPage")};
    static final Color[] KIND_COLOURS = {new Color(0xf5, 0xc4, 0x51), new Color(0x4f, 0x86, 0xe8),
            new Color(0xa9, 0xc4, 0xff), new Color(0x4a, 0x52, 0x60)};
    private static final ServerTraffic.Kind[] KINDS = ServerTraffic.Kind.values();
    private static final int SAMPLES = 300;
    /** The smallest scale, so that a few bytes on an empty server are not drawn as tall peaks. */
    private static final long SMALLEST_SCALE = 1024;
    private static final Color GRID = new Color(0x2a, 0x31, 0x3c);
    private static final Color IN = new Color(0xe8, 0xee, 0xf4);
    private static final BasicStroke DASHED = new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
            10f, new float[] {5f, 3f}, 0f);

    private final long[] in = new long[SAMPLES];
    private final long[][] out = new long[KINDS.length][SAMPLES];
    /** The index of the next sample, which is also the oldest sample still shown. */
    private int next;
    private ServerTraffic counter;
    private long receivedBefore;
    private final long[] sentBefore = new long[KINDS.length];

    TrafficGraph() {
        setPreferredSize(new Dimension(400, HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, HEIGHT));
        setAlignmentX(0f);
    }

    /** The counters only grow, so a sample is the difference from the last reading. A new server starts from zero. */
    void sample(final ServerTraffic now) {
        final long received = now == null ? 0 : now.received();
        final long[] sent = new long[KINDS.length];
        for (final ServerTraffic.Kind kind : KINDS) {
            sent[kind.ordinal()] = now == null ? 0 : now.sent(kind);
        }
        if (now != counter) {
            counter = now;
            receivedBefore = received;
            System.arraycopy(sent, 0, sentBefore, 0, sent.length);
        }
        in[next] = received - receivedBefore;
        receivedBefore = received;
        for (int k = 0; k < sent.length; k++) {
            out[k][next] = sent[k] - sentBefore[k];
            sentBefore[k] = sent[k];
        }
        next = (next + 1) % SAMPLES;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        final int w = getWidth();
        final int h = getHeight();
        g2.setColor(BACKGROUND);
        g2.fillRect(0, 0, w, h);

        // stacked[k][i] is everything the first k + 1 kinds sent in sample i, oldest sample first
        final long[][] stacked = new long[KINDS.length][SAMPLES];
        final long[] received = new long[SAMPLES];
        long top = SMALLEST_SCALE;
        for (int i = 0; i < SAMPLES; i++) {
            final int at = (next + i) % SAMPLES;
            long sum = 0;
            for (int k = 0; k < KINDS.length; k++) {
                sum += out[k][at];
                stacked[k][i] = sum;
            }
            received[i] = in[at];
            top = Math.max(top, Math.max(sum, in[at]));
        }
        final int plotTop = 38;
        final int plotHeight = h - plotTop - 4;
        g2.setColor(GRID);
        g2.drawLine(0, plotTop, w, plotTop);
        g2.drawLine(0, h - 4, w, h - 4);
        final int[] xs = xs();
        // Paint the tallest layer first. Each lower layer then covers the part of the layer above that is not that layer's own kind
        for (int k = KINDS.length - 1; k >= 0; k--) {
            g2.setColor(KIND_COLOURS[k]);
            g2.fillPolygon(xs, ys(stacked[k], top, plotTop, plotHeight), SAMPLES + 2);
        }
        g2.setColor(IN);
        g2.setStroke(DASHED);
        g2.drawPolyline(xs, ys(received, top, plotTop, plotHeight), SAMPLES);

        g2.setFont(getFont().deriveFont(11f));
        g2.setColor(LABEL);
        g2.drawString(TEXT.getMessage("lblWebConsoleInOut", rate(received[SAMPLES - 1]), rate(stacked[KINDS.length - 1][SAMPLES - 1])), 8, 14);
        final String scale = TEXT.getMessage("lblWebConsoleScale", rate(top));
        g2.drawString(scale, w - 8 - g2.getFontMetrics().stringWidth(scale), 14);
        int x = 8;
        for (int k = 0; k < KINDS.length; k++) {
            x = swatch(g2, x, 30, KIND_COLOURS[k], KIND_NAMES[k]) + 14;
        }
        g2.setColor(IN);
        g2.setStroke(DASHED);
        g2.drawLine(x, 26, x + 16, 26);
        g2.setColor(LABEL);
        g2.drawString(TEXT.getMessage("lblWebConsoleReceived"), x + 22, 30);
        g2.dispose();
    }

    /** Draws a filled square in a kind's colour, then its name. Returns the x where the next entry can start. */
    static int swatch(final Graphics2D g2, final int x, final int baseline, final Color colour, final String name) {
        g2.setColor(colour);
        g2.fillRect(x, baseline - 9, 9, 9);
        g2.setColor(LABEL);
        g2.drawString(name, x + 14, baseline);
        return x + 14 + g2.getFontMetrics().stringWidth(name);
    }

    /** Each sample's x, then the plot's two bottom corners. The same points can then close the area under a line. */
    private int[] xs() {
        final int[] xs = new int[SAMPLES + 2];
        for (int i = 0; i < SAMPLES; i++) {
            xs[i] = i * (getWidth() - 1) / (SAMPLES - 1);
        }
        xs[SAMPLES] = xs[SAMPLES - 1];
        xs[SAMPLES + 1] = xs[0];
        return xs;
    }

    /** Each sample's y, then the plot's floor twice, to go with xs. */
    private static int[] ys(final long[] values, final long top, final int plotTop, final int plotHeight) {
        final int[] ys = new int[SAMPLES + 2];
        for (int i = 0; i < SAMPLES; i++) {
            ys[i] = plotTop + plotHeight - (int) (values[i] * plotHeight / top);
        }
        ys[SAMPLES] = plotTop + plotHeight;
        ys[SAMPLES + 1] = plotTop + plotHeight;
        return ys;
    }

    private static String rate(final long bytesPerSecond) {
        return bytes(bytesPerSecond) + "/s";
    }

    static String bytes(final long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
