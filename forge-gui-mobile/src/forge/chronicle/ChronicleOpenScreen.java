package forge.chronicle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.Align;

import forge.gui.FThreads;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.gamemodes.chronicle.SealedItem;
import forge.item.PaperCard;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.toolbox.FButton;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.util.Utils;

/**
 * PLACEHOLDER opening flow: pick a sealed product, then tap through its
 * committed contents one card at a time (skippable). D3 replaces the reveal
 * with the real scene — wrapper tear, flip, rarity staging, batch register —
 * per the reveal-UX bar; this exists so the D2 daily session script runs
 * start to finish.
 */
public class ChronicleOpenScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);

    private final FScrollPane listPane = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = PADDING;
            for (FDisplayObject child : getChildren()) {
                child.setBounds(PADDING, y, visibleWidth - 2 * PADDING, Utils.scale(44));
                y += Utils.scale(44) + PADDING;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });
    private final RevealPane revealPane = add(new RevealPane());
    private final FButton btnSkip = add(new FButton(Forge.getLocalizer().getMessageorUseDefault("lblSkip", "Skip")));

    /** Cards of the pack being revealed; empty = list mode. */
    private final List<PaperCard> revealing = new ArrayList<>();
    private int revealIndex;
    private String revealTitle = "";

    public ChronicleOpenScreen() {
        super(Forge.getLocalizer().getMessageorUseDefault("lblChronicleOpenSealed", "Open Sealed"));
        btnSkip.setCommand(e -> endReveal());
    }

    @Override
    public void onActivate() {
        super.onActivate();
        updateList();
    }

    private void updateList() {
        revealing.clear();
        listPane.clear();
        listPane.setVisible(true);
        revealPane.setVisible(false);
        btnSkip.setVisible(false);

        // One row per (kind, edition) group; opening takes the oldest item of the group.
        Map<String, List<SealedItem>> groups = new LinkedHashMap<>();
        for (SealedItem item : ChronicleHub.controller().getRun().sealed.all()) {
            groups.computeIfAbsent(item.kind + "|" + item.editionCode, k -> new ArrayList<>()).add(item);
        }
        if (groups.isEmpty()) {
            listPane.add(new FLabel.Builder().text(
                    Forge.getLocalizer().getMessageorUseDefault("lblChronicleNothingSealed", "Nothing sealed - visit the store."))
                    .align(Align.center).build());
        }
        for (Map.Entry<String, List<SealedItem>> group : groups.entrySet()) {
            SealedItem first = group.getValue().get(0);
            String productName = productName(first);
            FButton btn = new FButton(productName + "  x" + group.getValue().size());
            btn.setCommand(e -> openItem(first, productName));
            listPane.add(btn);
        }
        listPane.revalidate();
    }

    private String productName(SealedItem item) {
        forge.gamemodes.chronicle.ChronicleRelease release = ChronicleHub.controller().getCalendar().byCode(item.editionCode);
        String set = release == null ? item.editionCode : release.name;
        return set + " " + (item.kind == SealedItem.Kind.STARTER
                ? Forge.getLocalizer().getMessageorUseDefault("lblChronicleStarterDeck", "starter deck")
                : Forge.getLocalizer().getMessageorUseDefault("lblChronicleBoosterPack", "booster pack"));
    }

    private void openItem(SealedItem item, String productName) {
        LoadingOverlay.runBackgroundTask("", () -> {
            final List<PaperCard> cards = ChronicleHub.controller().openSealed(item.itemId);
            FThreads.invokeInEdtLater(() -> {
                revealing.clear();
                revealing.addAll(cards);
                revealIndex = 0;
                revealTitle = productName;
                listPane.setVisible(false);
                revealPane.setVisible(true);
                btnSkip.setVisible(true);
            });
        });
    }

    private void advanceReveal() {
        revealIndex++;
        if (revealIndex >= revealing.size()) {
            endReveal();
        }
    }

    private void endReveal() {
        updateList();
    }

    private class RevealPane extends FDisplayObject {
        @Override
        public void draw(Graphics g) {
            if (revealing.isEmpty() || revealIndex >= revealing.size()) {
                return;
            }
            float w = getWidth();
            float h = getHeight();
            float labelHeight = Utils.scale(24);
            g.drawText(revealTitle + "  (" + (revealIndex + 1) + "/" + revealing.size() + ")",
                    FSkinFont.get(14), FLabel.getInlineLabelColor(), 0, 0, w, labelHeight, false, Align.center, true);

            float cardHeight = h - 2 * labelHeight - 2 * PADDING;
            float cardWidth = cardHeight / FCardPanel.ASPECT_RATIO;
            if (cardWidth > w - 2 * PADDING) {
                cardWidth = w - 2 * PADDING;
                cardHeight = cardWidth * FCardPanel.ASPECT_RATIO;
            }
            CardRenderer.drawCard(g, revealing.get(revealIndex), (w - cardWidth) / 2,
                    labelHeight + PADDING, cardWidth, cardHeight, CardStackPosition.Top);

            g.drawText(Forge.getLocalizer().getMessageorUseDefault("lblChronicleTapToContinue", "Tap to continue"),
                    FSkinFont.get(12), FSkinColor.getStandardColor(com.badlogic.gdx.graphics.Color.GRAY),
                    0, h - labelHeight, w, labelHeight, false, Align.center, true);
        }

        @Override
        public boolean tap(float x, float y, int count) {
            advanceReveal();
            return true;
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        listPane.setBounds(0, startY, width, height - startY);
        float btnHeight = Utils.scale(40);
        revealPane.setBounds(0, startY, width, height - startY - btnHeight - 2 * PADDING);
        btnSkip.setBounds(width / 4, height - btnHeight - PADDING, width / 2, btnHeight);
    }
}
