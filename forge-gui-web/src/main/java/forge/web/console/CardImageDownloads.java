package forge.web.console;

import com.google.common.collect.Iterables;
import forge.deck.Deck;
import forge.game.GameFormat;
import forge.gui.FThreads;
import forge.gui.UiCommand;
import forge.gui.download.GuiDownloadFilteredCardImages;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IButton;
import forge.gui.interfaces.IProgressBar;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.model.FModel;
import forge.util.Localizer;
import forge.util.storage.IStorage;
import forge.web.images.WebDownloads;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import static forge.web.console.ConsoleWidgets.heading;

/** The console's window for saving card pictures to this computer ahead of time. */
final class CardImageDownloads {
    private static final Localizer TEXT = Localizer.getInstance();
    /** A rough size for one saved image, used for the size estimate next to the count. */
    private static final double MB_PER_IMAGE = 0.08;
    private static final int TEXT_WIDTH = 400;
    private static final Color GOOD = new Color(0x2f, 0x8f, 0x46);
    private static final Color MUTED = new Color(0x5d, 0x66, 0x73);

    private final JComboBox<Choice> choices = new JComboBox<>();
    private final JLabel cardCount = heading("…");
    private final JLabel savedCount = heading("…");
    private final JLabel neededCount = heading("…");
    private final JLabel downloadState = new JLabel(" ");
    private final JButton downloadButton = new JButton(TEXT.getMessage("lblWebImagesDownload"));
    private final JButton downloadCancel = new JButton(TEXT.getMessage("lblCancel"));
    private final JProgressBar downloadBar = bar();
    private final Consumer<String> showProgress;
    private JDialog window;
    private boolean ready;
    private int downloadPercent = -1;
    private GuiDownloadService running;
    private long downloadStarted;
    /** The number of the latest count for the current choice. A newer count replaces an older one that is still running. */
    private int countRun;

    private record Choice(String name, Predicate<PaperCard> cards) {
        @Override
        public String toString() {
            return name;
        }
    }

    /** showProgress receives the text for the console's button: its plain name, or its name with the progress of a download. */
    CardImageDownloads(final Consumer<String> showProgress) {
        this.showProgress = showProgress;
    }

    /** Forge has loaded its cards. The console is drawn before that, and until then there is nothing to count or download. */
    void ready() {
        SwingUtilities.invokeLater(() -> ready = true);
    }

    /** Opens the window, or brings it to the front. A download in progress continues whether the window is open or not. */
    void show(final JFrame owner) {
        if (!ready) {
            return;
        }
        if (window == null) {
            window = build(owner);
        }
        recount();
        window.setVisible(true);
        window.toFront();
    }

    private JDialog build(final JFrame owner) {
        final JDialog dialog = new JDialog(owner, TEXT.getMessage("lblWebImagesTitle"), false);
        final JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.PAGE_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        choices.addItem(new Choice(TEXT.getMessage("lblWebImagesMyDecks"), null));
        choices.addItem(new Choice(TEXT.getMessage("lblWebImagesAllCards"), c -> true));
        for (final GameFormat format : FModel.getFormats().getFilterList()) {
            // A named format selects the cards its rules allow. A format without a name selects the printings in its sets
            choices.addItem(new Choice(format.getName(), format.getName() == null ? format.getFilterPrinted() : format.getFilterRules()));
        }
        choices.setMaximumSize(choices.getPreferredSize());
        choices.addActionListener(e -> recount());
        final JPanel pick = row();
        pick.add(plain(new JLabel(TEXT.getMessage("lblCards") + "  ")));
        pick.add(choices);
        pick.add(Box.createHorizontalGlue());
        // Each count sits under its heading and is as wide as its own text, so a long count is never cut off
        final JPanel counts = row();
        final List<String> heads = List.of("lblCards", "lblWebEditorSaved", "lblWebImagesToDownload");
        final List<JLabel> values = List.of(cardCount, savedCount, neededCount);
        for (int i = 0; i < heads.size(); i++) {
            final JLabel head = plain(new JLabel(TEXT.getMessage(heads.get(i))));
            head.setForeground(MUTED);
            final JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.PAGE_AXIS));
            column.add(head);
            column.add(Box.createVerticalStrut(2));
            column.add(values.get(i));
            if (i > 0) {
                counts.add(Box.createHorizontalStrut(28));
            }
            counts.add(column);
        }
        counts.add(Box.createHorizontalGlue());
        downloadButton.addActionListener(e -> download());
        downloadCancel.addActionListener(e -> stopDownload());
        downloadCancel.setVisible(false);
        body.add(section(TEXT.getMessage("lblWebImagesOptional"), TEXT.getMessage("lblWebImagesDownloadTitle"),
                TEXT.getMessage("lblWebImagesDownloadAbout"), downloadBar, pick, counts,
                line(downloadState, downloadCancel, downloadButton)));
        body.add(Box.createVerticalStrut(12));
        final JButton close = new JButton(TEXT.getMessage("lblClose"));
        close.addActionListener(e -> dialog.setVisible(false));
        final JPanel foot = row();
        foot.add(Box.createHorizontalGlue());
        foot.add(close);
        body.add(foot);

        dialog.getContentPane().add(body);
        dialog.pack();
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        return dialog;
    }

    /** One tool section: its name, what it does, its state and its buttons. */
    private static JPanel section(final String tag, final String title, final String about, final JProgressBar bar,
            final JComponent... rows) {
        final JPanel section = new JPanel();
        section.setLayout(new BoxLayout(section, BoxLayout.PAGE_AXIS));
        section.setAlignmentX(0f);
        section.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xc9, 0xce, 0xd6)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        final JLabel heading = new JLabel("<html><span style='color:#8a929c;font-size:9px'>" + tag.toUpperCase()
                + "</span>&nbsp;&nbsp;<b>" + title + "</b></html>");
        heading.setFont(heading.getFont().deriveFont(Font.PLAIN, 14f));
        section.add(heading);
        section.add(Box.createVerticalStrut(6));
        section.add(wrapped(about));
        // While the tool is working, the bar is shown above the last row, which holds the buttons
        for (int i = 0; i < rows.length; i++) {
            section.add(Box.createVerticalStrut(8));
            if (i == rows.length - 1) {
                section.add(bar);
                section.add(Box.createVerticalStrut(6));
            }
            section.add(rows[i]);
        }
        return section;
    }

    private static JLabel wrapped(final String text) {
        final JLabel label = plain(new JLabel("<html><div style='width:" + TEXT_WIDTH + "px'>" + text + "</div></html>"));
        label.setAlignmentX(0f);
        return label;
    }

    private static JPanel line(final JLabel state, final JButton cancel, final JButton action) {
        final JPanel line = row();
        line.add(state);
        line.add(Box.createHorizontalGlue());
        line.add(cancel);
        line.add(Box.createHorizontalStrut(8));
        line.add(action);
        return line;
    }

    private static JPanel row() {
        final JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.LINE_AXIS));
        row.setAlignmentX(0f);
        return row;
    }

    private static JProgressBar bar() {
        final JProgressBar bar = new JProgressBar();
        bar.setStringPainted(true);
        bar.setAlignmentX(0f);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        bar.setVisible(false);
        return bar;
    }

    /** The look and feel makes labels bold by default. The window's body text is not bold. */
    private static JLabel plain(final JLabel label) {
        label.setFont(label.getFont().deriveFont(Font.PLAIN));
        return label;
    }

    /** The cards a choice covers. "My decks" reads every stored deck, so the count calls this off the window's thread. */
    private static Predicate<PaperCard> cardsOf(final Choice choice) {
        return choice == null ? c -> false : choice.cards() != null ? choice.cards() : inMyDecks();
    }

    /** Counts the chosen cards and how many of their images are saved. The count runs off the window's thread. */
    private void recount() {
        if (running != null) {
            return;
        }
        final int run = ++countRun;
        for (final JLabel l : List.of(cardCount, savedCount, neededCount)) {
            l.setText("…");
        }
        downloadButton.setEnabled(false);
        downloadState.setText(" ");
        final Choice choice = (Choice) choices.getSelectedItem();
        FThreads.invokeInBackgroundThread(() -> {
            final Predicate<PaperCard> cards = cardsOf(choice);
            int total = 0;
            int saved = 0;
            for (final PaperCard card : Iterables.concat(FModel.getMagicDb().getCommonCards().getAllCards(),
                    FModel.getMagicDb().getVariantCards().getAllCards())) {
                if (cards.test(card)) {
                    total++;
                    if (card.hasImage()) {
                        saved++;
                    }
                }
            }
            final int t = total;
            final int s = saved;
            SwingUtilities.invokeLater(() -> {
                if (run != countRun) {
                    return;
                }
                final NumberFormat n = NumberFormat.getIntegerInstance();
                final int needed = t - s;
                cardCount.setText(n.format(t));
                savedCount.setText(n.format(s));
                final long mb = Math.max(1, Math.round(needed * MB_PER_IMAGE));
                // Above a gigabyte, show gigabytes to one decimal place: "6.8 GB" instead of "6,777 MB"
                final String size = mb < 1000 ? TEXT.getMessage("lblWebImagesMB", n.format(mb))
                        : TEXT.getMessage("lblWebImagesGB", String.format("%.1f", mb / 1000.0));
                neededCount.setText(needed == 0 ? "0" : TEXT.getMessage("lblWebImagesToDownloadSize", n.format(needed), size));
                downloadButton.setText(TEXT.getMessage("lblWebImagesDownloadN", n.format(needed)));
                downloadButton.setEnabled(needed > 0);
                downloadState.setText(needed == 0 && t > 0 ? TEXT.getMessage("lblWebImagesAllSaved") : " ");
                downloadState.setForeground(GOOD);
                window.pack();
            });
        });
    }

    private void download() {
        downloadButton.setEnabled(false);
        choices.setEnabled(false);
        downloadCancel.setVisible(true);
        downloadBar.setVisible(true);
        downloadBar.setIndeterminate(true);
        downloadBar.setString(TEXT.getMessage("lblWebImagesPreparing"));
        downloadState.setText(TEXT.getMessage("lblWebImagesKeepPlaying"));
        downloadState.setForeground(MUTED);
        window.pack();
        running = new GuiDownloadFilteredCardImages(cardsOf((Choice) choices.getSelectedItem()));
        downloadStarted = 0;
        // Once the service has listed what is missing, its start command runs at once. The count shown earlier was the confirmation
        running.initialize(new WebDownloads.Text(""), new WebDownloads.Text(""), new Bar(), new Starter(), this::finished, null, null);
    }

    private void stopDownload() {
        if (running != null) {
            running.setCancel(true);
        }
        finished();
    }

    private void finished() {
        SwingUtilities.invokeLater(() -> {
            running = null;
            downloadPercent = -1;
            showButtonProgress();
            downloadCancel.setVisible(false);
            downloadBar.setVisible(false);
            choices.setEnabled(true);
            recount();
        });
    }

    /** Shows "2,417 of 3,830, 2 min left", based on how fast the images have arrived so far. */
    private void showDownloadProgress(final int done, final int total) {
        if (downloadStarted == 0) {
            downloadStarted = System.currentTimeMillis();
        }
        downloadBar.setIndeterminate(false);
        downloadBar.setMaximum(total);
        downloadBar.setValue(done);
        final NumberFormat n = NumberFormat.getIntegerInstance();
        final long elapsed = System.currentTimeMillis() - downloadStarted;
        final long left = done > 0 ? elapsed * (total - done) / done / 60_000 : -1;
        downloadBar.setString(left < 0 ? TEXT.getMessage("lblWebNOfM", n.format(done), n.format(total))
                : TEXT.getMessage("lblWebImagesProgressLeft", n.format(done), n.format(total), Math.max(1, left)));
        downloadPercent = total == 0 ? -1 : done * 100 / total;
        showButtonProgress();
        if (total > 0 && done >= total) {
            finished();
        }
    }

    /** The console's button shows the progress of a running download, so the host can find it again after closing the window. */
    private void showButtonProgress() {
        showProgress.accept(downloadPercent < 0 ? TEXT.getMessage("lblWebImagesButton") : TEXT.getMessage("lblWebImagesButtonProgress", downloadPercent));
    }

    /** Every printing in this player's saved Constructed, Commander, Oathbreaker, Brawl and Tiny Leaders decks. */
    private static Predicate<PaperCard> inMyDecks() {
        final Set<PaperCard> cards = Collections.newSetFromMap(new ConcurrentHashMap<>());
        final var decks = FModel.getDecks();
        for (final IStorage<Deck> store : List.of(decks.getConstructed(), decks.getCommander(), decks.getOathbreaker(),
                decks.getBrawl(), decks.getTinyLeaders())) {
            addAll(store, cards);
        }
        return cards::contains;
    }

    private static void addAll(final IStorage<Deck> store, final Set<PaperCard> into) {
        for (final Deck deck : store) {
            for (final Map.Entry<PaperCard, Integer> e : deck.getAllCardsInASinglePool()) {
                into.add(e.getKey());
            }
        }
        for (final IStorage<Deck> folder : store.getFolders()) {
            addAll(folder, into);
        }
    }

    /** Shows the service's progress in the window's own words. */
    private final class Bar implements IProgressBar {
        private volatile int maximum;

        @Override public void setDescription(final String s0) { }
        @Override public void setValue(final int progress) { SwingUtilities.invokeLater(() -> showDownloadProgress(progress, maximum)); }
        @Override public void reset() { }
        @Override public void setShowETA(final boolean b0) { }
        @Override public void setShowCount(final boolean b0) { }
        @Override public void setPercentMode(final boolean percentMode0) { }
        @Override public int getMaximum() { return maximum; }
        @Override public void setMaximum(final int maximum0) { maximum = maximum0; }
    }

    /** Replaces the service's Start button. It runs the start and close commands as soon as the service hands each one over. */
    private static final class Starter implements IButton {
        @Override public void setCommand(final UiCommand command) { command.run(); }
        @Override public void setEnabled(final boolean b0) { }
        @Override public boolean isEnabled() { return true; }
        @Override public String getText() { return ""; }
        @Override public void setText(final String text0) { }
        @Override public boolean isSelected() { return false; }
        @Override public void setSelected(final boolean b0) { }
        @Override public boolean requestFocusInWindow() { return false; }
        @Override public void setImage(final FSkinProp color) { }
        @Override public void setTextColor(final int r, final int g, final int b) { }
        @Override public boolean isVisible() { return false; }
        @Override public void setVisible(final boolean b0) { }
        @Override public String getToolTipText() { return ""; }
        @Override public void setToolTipText(final String s0) { }
    }
}
