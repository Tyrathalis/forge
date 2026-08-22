package forge.screens.constructed;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.assets.FImage;
import forge.assets.FSkinImage;
import forge.card.CardSleeveImage;
import forge.card.CustomSleeveImport;
import forge.screens.FScreen;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.util.SleeveArt;
import forge.util.SleeveStore;
import forge.util.Utils;

/**
 * Picks one of the player's own custom sleeves, or adds a new one.
 *
 * <p>Neither libgdx client can open a system file picker, so there are two ways in: paste a link,
 * or drop image files into the sleeves folder - this screen takes any it finds when it opens.
 * The folder is named in the empty state so it can be found without reading anything.
 */
public class CustomSleeveSelector extends FScreen {

    public static void show(final String playerName, final String currentKey, final Consumer<String> callback) {
        Forge.openScreen(new CustomSleeveSelector(playerName, currentKey, callback));
    }

    private static final float PADDING = Utils.scale(5);
    private static final int COLUMNS = 4;

    private final String currentKey;
    private final Consumer<String> callback;
    private final FScrollPane scroller = new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(final float visibleWidth, final float visibleHeight) {
            int rowCount = 0;
            float x = PADDING;
            float y = PADDING;
            final float tile = (visibleWidth - (COLUMNS + 1) * PADDING) / COLUMNS;
            for (final FDisplayObject lbl : scroller.getChildren()) {
                if (rowCount == COLUMNS) {
                    x = PADDING;
                    y += tile + PADDING;
                    rowCount = 0;
                }
                lbl.setBounds(x, y, tile, tile);
                x += tile + PADDING;
                rowCount++;
            }
            return new ScrollBounds(visibleWidth, y + tile + PADDING);
        }
    };

    private CustomSleeveSelector(final String playerName, final String currentKey0, final Consumer<String> callback0) {
        super(Forge.getLocalizer().getMessage("lblSelectSleeveForPlayer", playerName));
        currentKey = currentKey0 == null ? "" : currentKey0;
        callback = callback0;
        rebuild();
        add(scroller);
    }

    private void rebuild() {
        scroller.clear();
        // Anything dropped into the folder joins the library the moment the picker opens
        CustomSleeveImport.adoptDropped(SleeveStore.directory());

        addTile(FSkinImage.UNKNOWN, null); // the add tile, first so it is always reachable

        final List<String> keys = SleeveStore.keys();
        for (final String key : keys) {
            addTile(new CardSleeveImage(key, SleeveArt.DEFAULT_OFFSET), key);
        }
        if (keys.isEmpty()) {
            final File dir = SleeveStore.directory();
            FOptionPane.showMessageDialog(
                    Forge.getLocalizer().getMessage("lblNoCustomSleevesYet", dir.getAbsolutePath()),
                    Forge.getLocalizer().getMessage("lblCustomSleeves"));
        }
        scroller.revalidate();
    }

    private void addTile(final FImage image, final String key) {
        final FLabel lbl = new FLabel.Builder().icon(image).iconScaleFactor(0.99f).align(Align.center)
                .iconInBackground(true).selectable(true).selected(key != null && key.equals(currentKey))
                .build();
        lbl.setCommand(e -> {
            if (key == null) {
                promptForLink();
            } else {
                callback.accept(key);
                Forge.back();
            }
        });
        scroller.add(lbl);
    }

    private void promptForLink() {
        FOptionPane.showInputDialog(Forge.getLocalizer().getMessage("lblSleeveImageUrl"), url -> {
            if (url == null || url.trim().isEmpty()) {
                return;
            }
            final SleeveStore.Result result = CustomSleeveImport.fromUrl(url);
            if (result.error != null) {
                FOptionPane.showErrorDialog(result.error,
                        Forge.getLocalizer().getMessage("lblSleeveImportFailed"));
                return;
            }
            callback.accept(result.key);
            Forge.back();
        });
    }

    @Override
    protected void doLayout(final float startY, final float width, final float height) {
        scroller.setBounds(0, startY, width, height - startY);
    }
}
