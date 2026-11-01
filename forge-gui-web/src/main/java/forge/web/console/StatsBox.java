package forge.web.console;

import forge.util.Localizer;
import forge.web.ServerTraffic;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import javax.swing.JComponent;

/** The server window's totals, drawn under the traffic graph in its colours. It shows the uptime, the number of players and every byte so far. */
final class StatsBox extends JComponent {
    private static final Localizer TEXT = Localizer.getInstance();
    private static final String[] NAMES = {TEXT.getMessage("lblWebConsoleUp"),
            TEXT.getMessage("lblPlayers"), TEXT.getMessage("lblWebConsoleReceived")};
    /** Uptime, players and bytes received, then bytes sent of each kind, then total bytes sent. */
    private String[] values = new String[NAMES.length + TrafficGraph.KIND_NAMES.length + 1];

    StatsBox() {
        setPreferredSize(new Dimension(210, TrafficGraph.HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, TrafficGraph.HEIGHT));
        setAlignmentX(0f);
        show(null, 0);
    }

    void show(final ServerTraffic traffic, final int players) {
        final String[] now = new String[values.length];
        now[0] = traffic == null ? "—" : uptime(System.currentTimeMillis() - traffic.started());
        now[1] = String.valueOf(players);
        now[2] = traffic == null ? "—" : TrafficGraph.bytes(traffic.received());
        long sent = 0;
        for (final ServerTraffic.Kind kind : ServerTraffic.Kind.values()) {
            final long bytes = traffic == null ? 0 : traffic.sent(kind);
            now[NAMES.length + kind.ordinal()] = traffic == null ? "—" : TrafficGraph.bytes(bytes);
            sent += bytes;
        }
        now[now.length - 1] = traffic == null ? "—" : TrafficGraph.bytes(sent);
        values = now;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        final Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(TrafficGraph.BACKGROUND);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setFont(getFont().deriveFont(11f));
        for (int i = 0; i < values.length; i++) {
            final int baseline = 16 + i * 15;
            g2.setColor(TrafficGraph.LABEL);
            if (i < NAMES.length) {
                g2.drawString(NAMES[i], 10, baseline);
            } else if (i == values.length - 1) {
                g2.drawString(TEXT.getMessage("lblWebConsoleSentTotal"), 24, baseline);
            } else {
                final int k = i - NAMES.length;
                TrafficGraph.swatch(g2, 10, baseline, TrafficGraph.KIND_COLOURS[k], TEXT.getMessage("lblWebConsoleSentKind", TrafficGraph.KIND_NAMES[k]));
            }
            g2.drawString(values[i], getWidth() - 10 - g2.getFontMetrics().stringWidth(values[i]), baseline);
        }
        g2.dispose();
    }

    private static String uptime(final long millis) {
        final long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
