package forge.chronicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import forge.deck.CardPool;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.itemmanager.ColumnDef;
import forge.itemmanager.ItemColumn;
import forge.itemmanager.ItemManagerConfig;
import forge.screens.FScreen;

/**
 * The binder spread: one set as a continuous image grid in collector-number
 * order — the FULL set pool with ownership as decoration (unowned = grayscale
 * silhouette; the gap IS the motivation), owned = art with a quantity pip,
 * NEW glint until viewed. Cards actually drawn while the spread is open are
 * marked seen (badge clears) when the player leaves.
 */
public class ChronicleSpreadScreen extends FScreen {

    private final ChronicleRelease release;
    private final CardManager list = add(new CardManager(false));
    /** NEW cards actually rendered this visit; cleared to seen on the way out. */
    private final Set<PaperCard> viewedNew = new HashSet<>();

    public ChronicleSpreadScreen(ChronicleRelease release) {
        super(release.name);
        this.release = release;

        ChronicleController controller = ChronicleHub.controller();
        ItemManagerConfig config = ItemManagerConfig.CHRONICLE_BINDER;
        Map<ColumnDef, ItemColumn> colOverrides = new HashMap<>();
        ItemColumn.addColOverride(config, colOverrides, ColumnDef.NEW,
                from -> controller.getRun().collection.isNew((PaperCard) from.getKey()) ? 1 : 0,
                from -> {
                    PaperCard card = (PaperCard) from.getKey();
                    if (controller.getRun().collection.isNew(card)) {
                        viewedNew.add(card); //rendered = viewed; badge clears when the spread closes
                        return "NEW";
                    }
                    return "";
                });
        ItemColumn.addColOverride(config, colOverrides, ColumnDef.OWNED,
                from -> controller.getRun().collection.count((PaperCard) from.getKey()),
                from -> controller.getRun().collection.count((PaperCard) from.getKey()));
        list.setup(config, colOverrides);
        list.setImageGroupCaptionFn(name -> {
            int[] completion = controller.getRun().collection.completion(ChronicleHub.setUniverse(release.editionCode));
            return name + " (" + completion[0] + "/" + completion[1] + ")";
        });

        CardPool pool = new CardPool();
        for (PaperCard card : ChronicleHub.setUniverse(release.editionCode)) {
            pool.add(card, 1);
        }
        list.setPool(pool);
        updateCaption();
    }

    private void updateCaption() {
        ChronicleController controller = ChronicleHub.controller();
        int[] completion = controller.getRun().collection.completion(ChronicleHub.setUniverse(release.editionCode));
        setHeaderCaption(release.name + "  " + completion[0] + "/" + completion[1]);
    }

    private void flushSeen() {
        if (!viewedNew.isEmpty()) {
            ChronicleHub.controller().markSeen(new HashSet<>(viewedNew));
            viewedNew.clear();
            updateCaption();
        }
    }

    @Override
    public void onSwitchAway(Consumer<Boolean> canSwitchCallback) {
        flushSeen();
        super.onSwitchAway(canSwitchCallback);
    }

    @Override
    public void onClose(Consumer<Boolean> canCloseCallback) {
        flushSeen();
        super.onClose(canCloseCallback);
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        list.setBounds(0, startY, width, height - startY);
    }
}
