package forge.chronicle;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.assets.FSkinFont;
import forge.assets.FSkinImage;
import forge.deck.Deck;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleRival;
import forge.gui.FThreads;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.toolbox.FButton;
import forge.toolbox.FContainer;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.GuiChoose;
import forge.util.Utils;

/**
 * The kitchen table: who's around today, what beating them pays, and your
 * record against them.
 *
 * This is the mode's effort→reward channel (plan pin 8). Each rival pays once
 * per played day; after that they will still play you as many times as you
 * like, for nothing. That is the shape ADR-0071 recommended and left for the
 * dogfood to judge — income cannot be farmed by replaying one kid, but the
 * games never stop being available, and daily earning capacity grows as the
 * timeline brings more rivals to the table.
 */
public class ChronicleKitchenScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);
    private static final float ROW_HEIGHT = Utils.scale(44);

    private final FLabel lblCash = add(new FLabel.Builder().icon(FSkinImage.QUEST_COINSTACK).font(FSkinFont.get(16)).build());
    private final FButton btnDecks = add(new FButton(caption("lblChronicleYourDecks", "Your decks")));
    private final FScrollPane scroller = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = PADDING;
            for (FDisplayObject child : getChildren()) {
                float childHeight = child instanceof RivalTile ? ((RivalTile) child).preferredHeight() : ROW_HEIGHT;
                child.setBounds(PADDING, y, visibleWidth - 2 * PADDING, childHeight);
                y += childHeight + PADDING;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });

    public ChronicleKitchenScreen() {
        super(caption("lblChronicleKitchenTable", "The Kitchen Table"));
        btnDecks.setCommand(e -> Forge.openScreen(new ChronicleDeckScreen()));
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }

    @Override
    public void onActivate() {
        super.onActivate();
        update();
    }

    private void update() {
        ChronicleController controller = ChronicleHub.controller();
        lblCash.setText(ChronicleHomeScreen.formatCents(controller.getRun().wallet.getCents()));
        scroller.clear();
        List<ChronicleRival> rivals = controller.rivalsToday();
        if (rivals.isEmpty()) {
            scroller.add(new FLabel.Builder()
                    .text(caption("lblChronicleNobodyAround", "Nobody's around yet. Give it a few days."))
                    .font(FSkinFont.get(14)).align(Align.left).build());
        }
        for (ChronicleRival rival : rivals) {
            scroller.add(new RivalTile(rival));
        }
        scroller.revalidate();
    }

    /**
     * Pick a deck, then play. Deliberately blocks on the two things that make a
     * game impossible rather than letting the player walk into a broken match:
     * no decks at all, and a deck the binder no longer backs.
     */
    private void challenge(ChronicleRival rival) {
        ChronicleController controller = ChronicleHub.controller();
        List<Deck> decks = controller.playerDecks();
        if (decks.isEmpty()) {
            FOptionPane.showConfirmDialog(
                    caption("lblChronicleNeedADeck", "You'll need a deck first."),
                    rival.name, caption("lblChronicleBuildOne", "Build one"), caption("lblCancel", "Cancel"),
                    true, confirmed -> {
                        if (confirmed) {
                            Forge.openScreen(new ChronicleDeckScreen());
                        }
                    });
            return;
        }
        List<Deck> playable = new ArrayList<>();
        for (Deck deck : decks) {
            if (controller.deckShortfall(deck).isEmpty()) {
                playable.add(deck);
            }
        }
        if (playable.isEmpty()) {
            FOptionPane.showMessageDialog(
                    caption("lblChronicleAllDecksShort",
                            "Every deck you've built needs cards you've sold. Fix one up first."),
                    rival.name);
            return;
        }
        if (playable.size() == 1) {
            start(rival, playable.get(0));
            return;
        }
        GuiChoose.oneOrNone(caption("lblChronicleWhichDeck", "Which deck?"), playable, chosen -> {
            if (chosen != null) {
                start(rival, chosen);
            }
        });
    }

    /** Deriving the rival's collection and building their deck is real work — do it off the GL thread. */
    private void start(ChronicleRival rival, Deck playerDeck) {
        LoadingOverlay.runBackgroundTask(rival.name + " " + caption("lblChronicleDigsOutADeck", "digs out a deck..."), () -> {
            final ChronicleController.Challenge challenge = ChronicleHub.controller().challenge(rival);
            FThreads.invokeInEdtLater(() -> ChronicleMatch.play(challenge, playerDeck, this::update));
        });
    }

    /** One rival: who they are, what today's game is worth, and how you've done against them. */
    private class RivalTile extends FContainer {
        private final FLabel lblName;
        private final FLabel lblFlavor;
        private final FButton btnPlay;

        RivalTile(ChronicleRival rival) {
            ChronicleController controller = ChronicleHub.controller();
            int day = controller.getRun().timeline.getDayIndex();
            boolean paying = controller.getRun().kitchen.purseAvailable(rival, day);
            int wins = controller.getRun().kitchen.wins(rival.id);
            int losses = controller.getRun().kitchen.losses(rival.id);

            StringBuilder header = new StringBuilder(rival.name);
            if (wins + losses > 0) {
                header.append("   ").append(wins).append('-').append(losses);
            }
            lblName = add(new FLabel.Builder().text(header.toString()).font(FSkinFont.get(17)).align(Align.left).build());
            lblFlavor = add(new FLabel.Builder().text(rival.flavor).font(FSkinFont.get(12)).align(Align.left).build());

            String label = paying
                    ? caption("lblChroniclePlayFor", "Play for") + " "
                            + ChronicleHomeScreen.formatCents(
                                    forge.gamemodes.chronicle.ChronicleKitchen.purseCents(controller.getConfig(), rival))
                    : caption("lblChronicleRematch", "Rematch (no money on it)");
            btnPlay = add(new FButton(label));
            btnPlay.setCommand(e -> challenge(rival));
        }

        float preferredHeight() {
            return Utils.scale(24) + Utils.scale(30) + ROW_HEIGHT + PADDING;
        }

        @Override
        protected void doLayout(float width, float height) {
            float nameHeight = Utils.scale(24);
            float flavorHeight = Utils.scale(30);
            lblName.setBounds(0, 0, width, nameHeight);
            lblFlavor.setBounds(0, nameHeight, width, flavorHeight);
            btnPlay.setBounds(0, nameHeight + flavorHeight, width, ROW_HEIGHT);
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float rowHeight = Utils.scale(30);
        float decksWidth = width * 0.4f;
        lblCash.setBounds(PADDING, startY + PADDING / 2, width - decksWidth - 3 * PADDING, rowHeight);
        btnDecks.setBounds(width - decksWidth - PADDING, startY + PADDING / 2, decksWidth, rowHeight);
        float top = startY + rowHeight + PADDING;
        scroller.setBounds(0, top, width, height - top);
    }
}
