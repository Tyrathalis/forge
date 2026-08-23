package forge.chronicle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import forge.Forge;
import forge.assets.FSkinImage;
import forge.deck.CardPool;
import forge.gamemodes.chronicle.ChronicleAcquisitionLog;
import forge.gamemodes.chronicle.ChronicleCalendar;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.gamemodes.chronicle.ChronicleRival;
import forge.gamemodes.chronicle.SealedItem;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.itemmanager.ColumnDef;
import forge.itemmanager.ItemColumn;
import forge.itemmanager.ItemManager;
import forge.itemmanager.ItemManagerConfig;
import forge.menu.FDropDownMenu;
import forge.menu.FMenuItem;
import forge.screens.FScreen;
import forge.toolbox.FOptionPane;

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
        //sort key = true acquisition order (first-pull ordinal; 0 = never pulled),
        //not the binary NEW flag — "Sort: New" walks the run's opening history
        ItemColumn.addColOverride(config, colOverrides, ColumnDef.NEW,
                from -> controller.getRun().acquisitions.firstAcquiredOrdinal((PaperCard) from.getKey()),
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
        //tap a card -> provenance: owned cards answer "when did I pull this?",
        //every card answers "where can I find one?" (the collector loop's two questions)
        list.setContextMenuBuilder(new ItemManager.ContextMenuBuilder<PaperCard>() {
            @Override
            public void buildMenu(FDropDownMenu menu, PaperCard card) {
                if (ChronicleHub.controller().getRun().collection.count(card) > 0) {
                    menu.addItem(new FMenuItem(caption("lblChronicleOpeningHistory", "Opening history"),
                            FSkinImage.QUEST_BOOK, e -> showOpeningHistory(card)));
                }
                menu.addItem(new FMenuItem(caption("lblChronicleWhereToFind", "Where to find"),
                        FSkinImage.QUEST_MAP, e -> showWhereToFind(card)));
            }
        });
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

    /** The provenance journal read back: every opening that contained this printing, oldest first. */
    private void showOpeningHistory(PaperCard card) {
        ChronicleController controller = ChronicleHub.controller();
        ChronicleAcquisitionLog log = controller.getRun().acquisitions;
        StringBuilder sb = new StringBuilder();
        long ordinal = log.firstAcquiredOrdinal(card);
        if (ordinal > 0) {
            sb.append(caption("lblChronicleCollectionNumber", "Pull #")).append(ordinal)
              .append(' ').append(caption("lblChronicleOfYourCollection", "of your collection")).append('\n');
        }
        sb.append(caption("lblChronicleCopiesOwned", "Copies owned:")).append(' ')
          .append(controller.getRun().collection.count(card)).append('\n');
        List<ChronicleAcquisitionLog.Entry> events = log.eventsFor(card);
        if (events.isEmpty()) {
            //openings from before the journal existed (or trades, someday) leave no record
            sb.append('\n').append(caption("nlChronicleNoRecord", "Acquired before you kept records."));
        } else {
            for (ChronicleAcquisitionLog.Entry event : events) {
                sb.append('\n').append(caption("lblChronicleDay", "Day")).append(' ').append(event.dayIndex + 1)
                  .append(" - ").append(originLabel(event));
                int copies = ChronicleAcquisitionLog.copiesIn(event, card);
                if (copies > 1) {
                    sb.append("  x").append(copies);
                }
            }
        }
        FOptionPane.showMessageDialog(sb.toString(), card.getName());
    }

    /** Which products yield this printing, and whether the LGS still shelves them today. */
    private void showWhereToFind(PaperCard card) {
        ChronicleController controller = ChronicleHub.controller();
        ChronicleCalendar calendar = controller.getCalendar();
        int today = controller.getRun().timeline.getDayIndex();
        List<SealedItem.Kind> sources = ChronicleAcquisitionLog.sourcesFor(card, calendar);
        StringBuilder sb = new StringBuilder();
        if (sources.isEmpty()) {
            sb.append(caption("nlChronicleNoSources", "No product on the timeline yields this printing."));
        } else {
            ChronicleRelease source = calendar.byCode(card.getEdition());
            for (SealedItem.Kind kind : sources) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(productLabel(card.getEdition(), kind)).append(" - ");
                if (source.inPrintOn(today)) {
                    sb.append(caption("lblChronicleInPrint", "in print now"));
                } else if (today < source.releaseDay) {
                    sb.append(caption("lblChronicleNotYetReleased", "not yet released"));
                } else {
                    sb.append(caption("lblChronicleOutOfPrint", "out of print"));
                }
            }
        }
        FOptionPane.showMessageDialog(sb.toString(), card.getName());
    }

    /**
     * Where an entry's cards came from — or went. The journal records ante both
     * ways, so a card can read "lost to Marcy" as readily as "Alpha booster pack".
     */
    private String originLabel(ChronicleAcquisitionLog.Entry event) {
        switch (event.kind) {
            case ANTE_WON: {
                ChronicleRival rival = ChronicleHub.controller().getRoster().byId(event.origin);
                return caption("lblChronicleWonFrom", "won from") + " "
                        + (rival != null ? rival.name : event.origin);
            }
            case ANTE_LOST: {
                ChronicleRival rival = ChronicleHub.controller().getRoster().byId(event.origin);
                return caption("lblChronicleLostTo", "lost to") + " "
                        + (rival != null ? rival.name : event.origin);
            }
            case STARTER:
                return productLabel(event.origin, SealedItem.Kind.STARTER);
            default:
                return productLabel(event.origin, SealedItem.Kind.BOOSTER);
        }
    }

    private String productLabel(String editionCode, SealedItem.Kind kind) {
        ChronicleRelease source = ChronicleHub.controller().getCalendar().byCode(editionCode);
        String name = source != null ? source.name : editionCode;
        String kindName = kind == SealedItem.Kind.STARTER
                ? caption("lblChronicleStarterDeck", "starter deck")
                : caption("lblChronicleBoosterPack", "booster pack");
        return name + " " + kindName;
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
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
