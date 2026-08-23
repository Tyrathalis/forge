package forge.chronicle;

import java.util.HashMap;
import java.util.Map;

import forge.deck.Deck;
import forge.deck.FDeckEditor;
import forge.gamemodes.chronicle.ChronicleController;
import forge.item.PaperCard;
import forge.itemmanager.ColumnDef;
import forge.itemmanager.ItemColumn;
import forge.itemmanager.ItemManagerConfig;

/**
 * The stock deck editor, with the columns Chronicle's catalog config requires
 * of whoever uses it.
 *
 * {@code CHRONICLE_DECK_POOL} sorts on {@link ColumnDef#NEW} and shows
 * {@link ColumnDef#OWNED}, and both ship with null sort and display functions —
 * "functions will be set later" — so every consumer has to supply them or
 * {@code ItemColumn}'s constructor throws "A sort function hasn't been set for
 * column New". Quest does it by subclassing the editor and overriding
 * getColOverrides; so does Adventure; so, now, does Chronicle. Pointing a config
 * at a collection is not enough on its own.
 *
 * Supplying them is worth doing rather than dodging: in a collection game the
 * cards you just pulled are the ones you want while building, and how many
 * copies you own is the number that decides what you can build at all — the
 * same treatment the binder spread already gives them.
 */
public class ChronicleDeckEditor extends FDeckEditor {

    public ChronicleDeckEditor(DeckEditorConfig editorConfig, Deck deck) {
        super(editorConfig, deck);
    }

    @Override
    protected Map<ColumnDef, ItemColumn> getColOverrides(ItemManagerConfig config) {
        ChronicleController controller = ChronicleHub.controller();
        if (controller == null || controller.getRun() == null) {
            return null;
        }
        Map<ColumnDef, ItemColumn> colOverrides = new HashMap<>();
        //sort key is the first-pull ordinal, not the binary flag — the same true
        //acquisition order the binder's "Sort: New" walks
        ItemColumn.addColOverride(config, colOverrides, ColumnDef.NEW,
                from -> controller.getRun().acquisitions.firstAcquiredOrdinal((PaperCard) from.getKey()),
                from -> controller.getRun().collection.isNew((PaperCard) from.getKey()) ? "NEW" : "");
        //how many you actually own — the number that decides what you can build
        ItemColumn.addColOverride(config, colOverrides, ColumnDef.OWNED,
                from -> controller.getRun().collection.count((PaperCard) from.getKey()),
                from -> controller.getRun().collection.count((PaperCard) from.getKey()));
        return colOverrides;
    }
}
