package forge.chronicle;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.assets.ImageCache;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.item.PaperCard;
import forge.screens.FScreen;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.util.Utils;

/**
 * The binder shelf: one tile per released set — booster-art cover (zero new
 * assets: sets already ship booster art), completion bar, NEW-count pip —
 * in release order. Tap a tile to open that set's spread.
 */
public class ChronicleBinderScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);
    private static final float TILE_HEIGHT = Utils.scale(72);

    private final FScrollPane scroller = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = PADDING;
            for (FDisplayObject child : getChildren()) {
                child.setBounds(PADDING, y, visibleWidth - 2 * PADDING, TILE_HEIGHT);
                y += TILE_HEIGHT + PADDING;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });

    public ChronicleBinderScreen() {
        super(Forge.getLocalizer().getMessageorUseDefault("lblChronicleBinder", "Binder"));
    }

    @Override
    public void onActivate() {
        super.onActivate();
        update();
    }

    private void update() {
        scroller.clear();
        ChronicleController controller = ChronicleHub.controller();
        int today = controller.getRun().timeline.getDayIndex();
        for (ChronicleRelease release : controller.getCalendar().all()) {
            if (release.releaseDay <= today) {
                scroller.add(new SetTile(release));
            }
        }
        scroller.revalidate();
    }

    private class SetTile extends FDisplayObject {
        private final ChronicleRelease release;
        private final String artKey;

        SetTile(ChronicleRelease release) {
            this.release = release;
            //resolved via the booster-images list (bare b:CODE keys never fetch); kicks the download
            this.artKey = ChronicleHub.boosterArtKey(release.editionCode);
        }

        @Override
        public void draw(Graphics g) {
            float w = getWidth();
            float h = getHeight();

            //booster-art cover, left
            float artWidth = h * 0.72f;
            Texture art = ImageCache.getInstance().getImage(artKey, true);
            if (art != null && art != ImageCache.getInstance().getDefaultImage()) {
                g.drawImage(art, 0, 0, artWidth, h);
            } else {
                g.fillRect(FSkinColor.getStandardColor(new Color(0.15f, 0.15f, 0.2f, 1f)).getColor(), 0, 0, artWidth, h);
                g.drawText(release.editionCode, FSkinFont.get(14), Color.WHITE, 0, 0, artWidth, h, false, Align.center, true);
            }

            //name + completion, right
            float textX = artWidth + PADDING;
            float textWidth = w - textX;
            ChronicleController controller = ChronicleHub.controller();
            java.util.List<PaperCard> universe = ChronicleHub.setUniverse(release.editionCode);
            int[] completion = controller.getRun().collection.completion(universe);
            int newCount = 0;
            for (PaperCard card : universe) {
                if (controller.getRun().collection.isNew(card)) {
                    newCount++;
                }
            }

            g.drawText(release.name, FSkinFont.get(16), FLabel.getInlineLabelColor(),
                    textX, PADDING, textWidth, h * 0.3f, false, Align.left, false);
            String stats = completion[0] + "/" + completion[1]
                    + (newCount > 0 ? "   " + newCount + " NEW" : "");
            g.drawText(stats, FSkinFont.get(13), FLabel.getInlineLabelColor(),
                    textX, h * 0.38f, textWidth, h * 0.25f, false, Align.left, false);

            //completion bar
            float barY = h * 0.72f;
            float barHeight = Utils.scale(6);
            g.fillRect(Color.DARK_GRAY, textX, barY, textWidth - PADDING, barHeight);
            if (completion[1] > 0 && completion[0] > 0) {
                float fill = (textWidth - PADDING) * completion[0] / completion[1];
                g.fillRect(Color.FOREST, textX, barY, fill, barHeight);
            }
        }

        @Override
        public boolean tap(float x, float y, int count) {
            Forge.openScreen(new ChronicleSpreadScreen(release));
            return true;
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        scroller.setBounds(0, startY, width, height - startY);
    }
}
