package forge.chronicle;

import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.assets.FSkinFont;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.FDeckEditor;
import forge.game.GameType;
import forge.gamemodes.chronicle.ChronicleController;
import forge.item.PaperCard;
import forge.itemmanager.ItemManagerConfig;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.gui.FThreads;
import forge.toolbox.FButton;
import forge.toolbox.FContainer;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.util.ItemPool;
import forge.util.Utils;

/**
 * The player's decks: what you can take to the kitchen table.
 *
 * Decks live in the Chronicle save, NOT in Forge's shared deck store. That is
 * deliberate and load-bearing beyond tidiness — the playable build shares its
 * deck store with the research harness, whose pool launcher gates on installed
 * deck content hashes, so a Chronicle deck landing there would silently move a
 * number in an unrelated project. It also keeps decks inside the run, where the
 * prestige-proof save split can reason about them.
 *
 * The editor is stock FDeckEditor with one hook: setPlayerInventorySupplier
 * points the catalog at the collection. That is Quest's existing semantics and
 * exactly the legality ADR-0071 chose — the editor caps a deck at the copies
 * you own, while two decks may still name the same card, because naming a card
 * never removes it from the binder.
 *
 * The catalog uses Chronicle's OWN ItemManagerConfig rather than borrowing
 * Quest's. ItemManagerConfig holds persisted per-config view state — group-by,
 * pile-by, view index, column widths — so sharing QUEST_EDITOR_POOL meant
 * Chronicle and Quest silently reconfigured each other's editors, and a bad
 * value saved by one broke the other. Chronicle's binder already had its own
 * config for the same reason.
 */
public class ChronicleDeckScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);
    private static final float ROW_HEIGHT = Utils.scale(44);

    private final FLabel lblCollection = add(new FLabel.Builder().font(FSkinFont.get(14)).align(Align.left).build());
    private final FButton btnNew = add(new FButton(caption("lblChronicleNewDeck", "New deck")));
    private final FButton btnAuto = add(new FButton(caption("lblChronicleAutoBuild", "Build one for me")));
    private final FScrollPane scroller = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = PADDING;
            for (FDisplayObject child : getChildren()) {
                float childHeight = child instanceof DeckTile ? ((DeckTile) child).preferredHeight() : ROW_HEIGHT;
                child.setBounds(PADDING, y, visibleWidth - 2 * PADDING, childHeight);
                y += childHeight + PADDING;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });

    public ChronicleDeckScreen() {
        super(caption("lblChronicleYourDecks", "Your decks"));
        btnNew.setCommand(e -> openEditor(new Deck(nextName())));
        btnAuto.setCommand(e -> autoBuild());
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
        //what you have to build from, stated up front. A deckbuilder wants this
        //anyway, and it makes an empty catalog self-diagnosing: if this says you
        //own cards and the editor shows none, the fault is downstream of here.
        CardPool owned = ChronicleHub.controller().collectionAsPool();
        lblCollection.setText(caption("lblChronicleCollection", "Collection") + ": "
                + owned.countAll() + " " + caption("lblChronicleCards", "cards") + ", "
                + owned.countDistinct() + " " + caption("lblChronicleDistinct", "distinct"));
        scroller.clear();
        List<Deck> decks = ChronicleHub.controller().playerDecks();
        if (decks.isEmpty()) {
            scroller.add(new FLabel.Builder()
                    .text(caption("lblChronicleNoDecksYet",
                            "No decks yet. Build one from what you've opened — the kids down the street are waiting."))
                    .font(FSkinFont.get(14)).align(Align.left).build());
        }
        for (Deck deck : decks) {
            scroller.add(new DeckTile(deck));
        }
        scroller.revalidate();
    }

    private String nextName() {
        ChronicleController controller = ChronicleHub.controller();
        int n = controller.playerDecks().size() + 1;
        String name = caption("lblChronicleDeck", "Deck") + " " + n;
        while (controller.getRun().decks.has(name)) {
            n++;
            name = caption("lblChronicleDeck", "Deck") + " " + n;
        }
        return name;
    }

    private void autoBuild() {
        final String name = nextName();
        LoadingOverlay.runBackgroundTask(caption("lblChronicleShufflingThrough", "Shuffling through the binder..."), () -> {
            final Deck built = ChronicleHub.controller().autoBuildPlayerDeck(name);
            FThreads.invokeInEdtLater(() -> {
                ChronicleHub.controller().savePlayerDeck(built);
                update();
                openEditor(built);
            });
        });
    }

    /** Stock editor, catalog pointed at the collection. */
    public static void openEditor(Deck deck) {
        ChronicleDeckController controller = new ChronicleDeckController();
        FDeckEditor.GameTypeDeckEditorConfig config =
                new FDeckEditor.GameTypeDeckEditorConfig(GameType.Constructed, controller)
                        .setPlayerInventorySupplier(ChronicleDeckScreen::collectionPool)
                        //own config, so the catalog needs its own caption too
                        .setCatalogConfig(ItemManagerConfig.CHRONICLE_DECK_POOL, "lblCollection");
        Forge.openScreen(new ChronicleDeckEditor(config, deck));
    }

    /** The collection, as the editor's catalog. Rebuilt per open so a fresh pull shows up. */
    private static ItemPool<PaperCard> collectionPool() {
        return ChronicleHub.controller().collectionAsPool();
    }

    /** Routes the editor's saves into the Chronicle run instead of Forge's deck store. */
    private static final class ChronicleDeckController implements FDeckEditor.IDeckController {
        private Deck deck;
        private FDeckEditor editor;
        private boolean saved = true;

        @Override
        public void setEditor(FDeckEditor editor) {
            this.editor = editor;
            //Attaching is only half the contract: the editor's own deck field is
            //populated from the controller here, and FDeckEditor.getDeck() reads
            //that field. Skip this and getDeck() stays null through construction,
            //so the pages are never told the deck arrived and the catalog is never
            //refreshed — an editor that opens fine and shows nothing, forever.
            if (editor != null) {
                editor.notifyNewControllerModel();
            }
        }
        @Override public void setDeck(Deck deck) { this.deck = deck; saved = false; }
        @Override public Deck getDeck() { return deck; }
        @Override public void newDeck() { deck = new Deck(caption("lblChronicleDeck", "Deck")); saved = false; }
        @Override public String getDeckDisplayName() { return deck == null ? null : deck.getName(); }
        @Override public void notifyModelChanged() { saved = false; }
        @Override public void exitWithoutSaving() { saved = true; }
        @Override public boolean supportsSave() { return true; }
        @Override public boolean supportsRename() { return true; }
        @Override public boolean supportsDelete() { return true; }
        @Override public boolean isSaved() { return saved; }

        @Override
        public void save() {
            if (deck != null) {
                ChronicleHub.controller().savePlayerDeck(deck);
                saved = true;
            }
            if (editor != null) {
                editor.notifyNewControllerModel();
            }
        }

        @Override
        public void saveAs(String name) {
            if (deck == null) {
                return;
            }
            Deck copy = (Deck) deck.copyTo(name);
            deck = copy;
            ChronicleHub.controller().savePlayerDeck(copy);
            saved = true;
        }

        @Override
        public void rename(String name) {
            if (deck == null || name.equals(deck.getName())) {
                return;
            }
            String old = deck.getName();
            Deck renamed = (Deck) deck.copyTo(name);
            ChronicleHub.controller().deletePlayerDeck(old);
            ChronicleHub.controller().savePlayerDeck(renamed);
            deck = renamed;
            saved = true;
        }

        @Override
        public boolean delete() {
            if (deck == null) {
                return false;
            }
            boolean removed = ChronicleHub.controller().deletePlayerDeck(deck.getName());
            deck = null;
            return removed;
        }

        @Override
        public String getNextAvailableName() {
            ChronicleController controller = ChronicleHub.controller();
            int n = controller.playerDecks().size() + 1;
            String name = caption("lblChronicleDeck", "Deck") + " " + n;
            while (controller.getRun().decks.has(name)) {
                n++;
                name = caption("lblChronicleDeck", "Deck") + " " + n;
            }
            return name;
        }
    }

    /** One deck: name, size, and whether the binder still backs it. */
    private class DeckTile extends FContainer {
        private final FLabel lblName;
        private final FButton btnEdit;
        private final FButton btnDelete;

        DeckTile(Deck deck) {
            int size = deck.getOrCreate(DeckSection.Main).countAll();
            Map<PaperCard, Integer> missing = ChronicleHub.controller().deckShortfall(deck);
            StringBuilder sb = new StringBuilder(deck.getName());
            sb.append("  ").append(size).append(' ').append(caption("lblChronicleCards", "cards"));
            if (!missing.isEmpty()) {
                //a buylist sale can leave a deck naming cards that are gone; say so
                //rather than quietly editing the player's deck
                sb.append("  — ").append(caption("lblChronicleSoldOutOfCards", "you've sold cards this deck needs"));
            }
            lblName = add(new FLabel.Builder().text(sb.toString()).font(FSkinFont.get(15)).align(Align.left).build());

            btnEdit = add(new FButton(caption("lblEdit", "Edit")));
            btnEdit.setCommand(e -> openEditor(deck));
            btnDelete = add(new FButton(caption("lblDelete", "Delete")));
            btnDelete.setCommand(e -> FOptionPane.showConfirmDialog(
                    caption("lblChronicleDeleteDeckPrompt", "Take this deck apart?") + "\n" + deck.getName(),
                    caption("lblChronicleYourDecks", "Your decks"),
                    caption("lblDelete", "Delete"), caption("lblCancel", "Cancel"), false, confirmed -> {
                        if (confirmed) {
                            ChronicleHub.controller().deletePlayerDeck(deck.getName());
                            update();
                        }
                    }));
        }

        float preferredHeight() {
            return Utils.scale(24) + ROW_HEIGHT + PADDING;
        }

        @Override
        protected void doLayout(float width, float height) {
            float labelHeight = Utils.scale(24);
            lblName.setBounds(0, 0, width, labelHeight);
            float buttonWidth = (width - PADDING) / 2;
            btnEdit.setBounds(0, labelHeight, buttonWidth, ROW_HEIGHT);
            btnDelete.setBounds(buttonWidth + PADDING, labelHeight, buttonWidth, ROW_HEIGHT);
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float labelHeight = Utils.scale(22);
        lblCollection.setBounds(PADDING, startY + PADDING / 2, width - 2 * PADDING, labelHeight);
        float buttonWidth = (width - 3 * PADDING) / 2;
        float buttonY = startY + labelHeight + PADDING;
        btnNew.setBounds(PADDING, buttonY, buttonWidth, ROW_HEIGHT);
        btnAuto.setBounds(2 * PADDING + buttonWidth, buttonY, buttonWidth, ROW_HEIGHT);
        float top = buttonY + ROW_HEIGHT + PADDING;
        scroller.setBounds(0, top, width, height - top);
    }
}
