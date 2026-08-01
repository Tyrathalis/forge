package forge.chronicle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.utils.Align;

import forge.CachedCardImage;
import forge.Forge;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.card.CardRenderer;
import forge.card.CardRenderer.CardStackPosition;
import forge.card.CardZoom;
import forge.gamemodes.chronicle.ChronicleAcquisitionLog;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChroniclePricing;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.gamemodes.chronicle.SealedItem;
import forge.gui.FThreads;
import forge.item.PaperCard;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.toolbox.FButton;
import forge.toolbox.FCardPanel;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FScrollPane;
import forge.util.Utils;

/**
 * Open Sealed: pick a product, then the D3 reveal scene — single-pack ceremony
 * or the batch register (starters and Open All route through batch) — followed
 * by a summary spread with first-pull badges and the buylist total.
 */
public class ChronicleOpenScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);
    private static final long PRELOAD_TIMEOUT_MS = 8000;

    private enum Mode { LIST, REVEAL, SUMMARY }

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
    private final FButton btnSkip = add(new FButton(Forge.getLocalizer().getMessageorUseDefault("lblSkip", "Skip")));
    private final FButton btnFlipCommons = add(new FButton(""));
    private final FButton btnDone = add(new FButton(Forge.getLocalizer().getMessageorUseDefault("lblDone", "Done")));
    private final FButton btnOpenAnother = add(new FButton(""));
    private final SummaryPane summaryPane = add(new SummaryPane());

    private Mode mode = Mode.LIST;
    private ChronicleRevealScene revealScene;
    private final List<ChronicleRevealScene.RevealCard> lastOpened = new ArrayList<>();
    private String lastGroupKey;
    private boolean lastWasBatch;

    public ChronicleOpenScreen() {
        super(Forge.getLocalizer().getMessageorUseDefault("lblChronicleOpenSealed", "Open Sealed"));
        btnSkip.setCommand(e -> {
            if (revealScene != null) {
                revealScene.finishNow();
            }
        });
        btnFlipCommons.setCommand(e -> {
            if (revealScene != null) {
                revealScene.batchFlipCommons();
            }
        });
        btnDone.setCommand(e -> showList());
        btnOpenAnother.setCommand(e -> openAnotherFromLastGroup());
    }

    @Override
    public void onActivate() {
        super.onActivate();
        if (mode == Mode.LIST) {
            showList();
        }
    }

    @Override
    public void onSwitchAway(java.util.function.Consumer<Boolean> canSwitchCallback) {
        stopRevealIfRunning(); //leaving mid-reveal must release the animation driver
        super.onSwitchAway(canSwitchCallback);
    }

    @Override
    public void onClose(java.util.function.Consumer<Boolean> canCloseCallback) {
        stopRevealIfRunning();
        super.onClose(canCloseCallback);
    }

    private void stopRevealIfRunning() {
        if (revealScene != null) {
            revealScene.finishNow(); //ends the driver; the summary is ready if the player returns
        }
    }

    //--- list mode -----------------------------------------------------------

    private void showList() {
        mode = Mode.LIST;
        if (revealScene != null) {
            removeRevealScene();
        }
        lastOpened.clear();
        listPane.clear();
        setModeVisibility();

        Map<String, List<SealedItem>> groups = groupedSealed();
        if (groups.isEmpty()) {
            listPane.add(new FLabel.Builder().text(
                    Forge.getLocalizer().getMessageorUseDefault("lblChronicleNothingSealed", "Nothing sealed - visit the store."))
                    .align(Align.center).build());
        }
        for (Map.Entry<String, List<SealedItem>> group : groups.entrySet()) {
            final String groupKey = group.getKey();
            List<SealedItem> items = group.getValue();
            SealedItem first = items.get(0);
            String productName = productName(first);
            FButton btn = new FButton(productName + "  x" + items.size());
            btn.setCommand(e -> openFromGroup(groupKey, 1));
            listPane.add(btn);
            if (first.kind == SealedItem.Kind.BOOSTER && items.size() > 1) {
                final int n = items.size();
                FButton btnAll = new FButton("  ⇩ " + Forge.getLocalizer().getMessageorUseDefault(
                        "lblChronicleOpenAll", "Open all") + " x" + n);
                btnAll.setCommand(e -> openFromGroup(groupKey, n));
                listPane.add(btnAll);
            }
        }
        listPane.revalidate();
    }

    private Map<String, List<SealedItem>> groupedSealed() {
        Map<String, List<SealedItem>> groups = new LinkedHashMap<>();
        for (SealedItem item : ChronicleHub.controller().getRun().sealed.all()) {
            groups.computeIfAbsent(item.kind + "|" + item.editionCode, k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    private String productName(SealedItem item) {
        ChronicleRelease release = ChronicleHub.controller().getCalendar().byCode(item.editionCode);
        String set = release == null ? item.editionCode : release.name;
        return set + " " + (item.kind == SealedItem.Kind.STARTER
                ? Forge.getLocalizer().getMessageorUseDefault("lblChronicleStarterDeck", "starter deck")
                : Forge.getLocalizer().getMessageorUseDefault("lblChronicleBoosterPack", "booster pack"));
    }

    //--- staging: open items, price and badge the contents, preload art ------

    private void openFromGroup(String groupKey, int count) {
        List<SealedItem> items = groupedSealed().get(groupKey);
        if (items == null || items.isEmpty()) {
            showList();
            return;
        }
        int n = Math.min(count, items.size());
        //starters are 60-card blocks: always the batch register; multi-pack opens too
        final boolean batch = items.get(0).kind == SealedItem.Kind.STARTER || n > 1;
        final List<SealedItem> toOpen = new ArrayList<>(items.subList(0, n));
        final String title = productName(items.get(0));
        lastGroupKey = groupKey;
        lastWasBatch = batch && toOpen.get(0).kind != SealedItem.Kind.STARTER;

        LoadingOverlay.runBackgroundTask(
                Forge.getLocalizer().getMessageorUseDefault("lblChronicleOpening", "Opening..."), () -> {
            ChronicleController controller = ChronicleHub.controller();
            ChroniclePricing pricing = controller.getPricing();
            ChronicleAcquisitionLog log = controller.getRun().acquisitions;
            List<ChronicleRevealScene.RevealPack> packs = new ArrayList<>();
            List<ChronicleRevealScene.RevealCard> allStaged = new ArrayList<>();
            Set<String> glinted = new HashSet<>(); //one glint per identity across the whole opening
            Map<String, Integer> batchCopies = new LinkedHashMap<>(); //identity -> copies opened this batch

            for (SealedItem item : toOpen) {
                List<PaperCard> cards = controller.openSealed(item.itemId);
                long openingSeq = log.all().get(log.all().size() - 1).seq;
                for (PaperCard card : cards) {
                    batchCopies.merge(identityOf(card), 1, Integer::sum);
                }

                List<ChronicleRevealScene.RevealCard> staged = new ArrayList<>();
                for (PaperCard card : cards) {
                    //first pull = the log's oldest sighting is this opening AND the collection
                    //holds no copies beyond this batch's. The count cross-check covers runs
                    //whose early openings predate the acquisition log (it shipped mid-run):
                    //log-blind history would otherwise glint every re-pull as new.
                    List<ChronicleAcquisitionLog.Entry> events = log.eventsFor(card);
                    boolean firstPull = !events.isEmpty() && events.get(0).seq == openingSeq
                            && controller.getRun().collection.count(card) == batchCopies.get(identityOf(card));
                    staged.add(new ChronicleRevealScene.RevealCard(card, firstPull,
                            pricing.isNotable(card.getName()), pricing.buylistCents(card)));
                }
                //best-last staging: basics and commons first, the money card closes the pack
                staged.sort(Comparator
                        .comparingInt(ChronicleRevealScene.RevealCard::rarityRank)
                        .thenComparingInt(rc -> rc.valueCents));
                //a duplicate inside this opening only glints its first appearance
                List<ChronicleRevealScene.RevealCard> deduped = new ArrayList<>();
                for (ChronicleRevealScene.RevealCard rc : staged) {
                    if (rc.firstPull && !glinted.add(identityOf(rc.card))) {
                        rc = new ChronicleRevealScene.RevealCard(rc.card, false, rc.notable, rc.valueCents);
                    }
                    deduped.add(rc);
                }
                allStaged.addAll(deduped);

                String artKey = wrapperArtKey(item);
                String packTitle = toOpen.size() > 1
                        ? title + "  " + (packs.size() + 1) + "/" + toOpen.size()
                        : title;
                packs.add(new ChronicleRevealScene.RevealPack(packTitle, artKey, deduped));
            }

            preloadImages(allStaged);

            FThreads.invokeInEdtLater(() -> {
                lastOpened.clear();
                lastOpened.addAll(allStaged);
                startReveal(packs, batch);
            });
        });
    }

    private static String identityOf(PaperCard card) {
        return card.getName() + "|" + card.getEdition() + "|" + card.getArtIndex() + "|" + card.isFoil();
    }

    private String wrapperArtKey(SealedItem item) {
        return item.kind == SealedItem.Kind.STARTER
                ? ChronicleHub.starterArtKey(item.editionCode)
                : ChronicleHub.boosterArtKey(item.editionCode);
    }

    /** Kick fetches for every card (on the EDT — the fetcher asserts it), then hold (bounded) until the files are local. */
    private void preloadImages(List<ChronicleRevealScene.RevealCard> staged) {
        List<String> keys = new ArrayList<>();
        for (ChronicleRevealScene.RevealCard rc : staged) {
            keys.add(rc.card.getImageKey(false));
        }
        FThreads.invokeInEdtNowOrLater(() -> {
            for (ChronicleRevealScene.RevealCard rc : staged) {
                new CachedCardImage(rc.card) {
                    @Override
                    public void onImageFetched() {
                    }
                };
            }
        });
        long deadline = System.currentTimeMillis() + PRELOAD_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean allPresent = true;
            for (String key : keys) {
                if (!forge.assets.ImageCache.getInstance().imageKeyFileExists(key)) {
                    allPresent = false;
                    break;
                }
            }
            if (allPresent) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    //--- reveal mode ---------------------------------------------------------

    private void startReveal(List<ChronicleRevealScene.RevealPack> packs, boolean batch) {
        mode = Mode.REVEAL;
        revealScene = add(new ChronicleRevealScene(packs, batch, new ChronicleRevealScene.Listener() {
            @Override
            public void onRevealFinished() {
                showSummary();
            }

            @Override
            public void onStateChanged() {
                syncFlipCommonsButton();
            }
        }));
        setModeVisibility();
        revalidate();
    }

    private void removeRevealScene() {
        if (revealScene != null) {
            revealScene.finishNow();
            remove(revealScene);
            revealScene = null;
        }
    }

    private void syncFlipCommonsButton() {
        if (revealScene == null) {
            btnFlipCommons.setVisible(false);
            return;
        }
        boolean can = revealScene.canBatchFlipCommons();
        btnFlipCommons.setVisible(mode == Mode.REVEAL && can);
        if (can) {
            btnFlipCommons.setText(Forge.getLocalizer().getMessageorUseDefault(
                    "lblChronicleFlipCommons", "Flip commons") + " x" + revealScene.batchFlipCount());
        }
    }

    //--- summary mode --------------------------------------------------------

    private void showSummary() {
        mode = Mode.SUMMARY;
        if (revealScene != null) {
            remove(revealScene);
            revealScene = null;
        }
        //group not re-fetched until Done, so "Open another" reflects what's left NOW
        List<SealedItem> remaining = lastGroupKey == null ? null : groupedSealed().get(lastGroupKey);
        if (remaining != null && !remaining.isEmpty()) {
            String verb = lastWasBatch
                    ? Forge.getLocalizer().getMessageorUseDefault("lblChronicleOpenAll", "Open all") + " x" + remaining.size()
                    : Forge.getLocalizer().getMessageorUseDefault("lblChronicleOpenAnother", "Open another");
            btnOpenAnother.setText(verb);
        }
        setModeVisibility();
        summaryPane.setVisible(true);
        btnOpenAnother.setVisible(remaining != null && !remaining.isEmpty());
        SoundSystem.instance.play(SoundEffectType.CoinsDrop, false);
        summaryPane.revalidate();
        revalidate();
    }

    private void openAnotherFromLastGroup() {
        if (lastGroupKey == null) {
            return;
        }
        List<SealedItem> remaining = groupedSealed().get(lastGroupKey);
        if (remaining == null || remaining.isEmpty()) {
            showList();
            return;
        }
        openFromGroup(lastGroupKey, lastWasBatch ? remaining.size() : 1);
    }

    private void setModeVisibility() {
        listPane.setVisible(mode == Mode.LIST);
        btnSkip.setVisible(mode == Mode.REVEAL);
        btnDone.setVisible(mode == Mode.SUMMARY);
        btnOpenAnother.setVisible(false); //showSummary flips it on when the group has more
        summaryPane.setVisible(mode == Mode.SUMMARY);
        syncFlipCommonsButton();
    }

    private class SummaryPane extends FScrollPane {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            return new ScrollBounds(visibleWidth, contentHeight(visibleWidth));
        }

        private int columns(float width) {
            return Math.max(3, (int) (width / Utils.scale(110)));
        }

        private float cellWidth(float width) {
            int cols = columns(width);
            return (width - (cols + 1) * PADDING) / cols;
        }

        private float contentHeight(float width) {
            int cols = columns(width);
            float cellH = cellWidth(width) * FCardPanel.ASPECT_RATIO;
            int rows = (lastOpened.size() + cols - 1) / cols;
            return headerHeight() + rows * (cellH + PADDING) + PADDING;
        }

        private float headerHeight() {
            return Utils.scale(40);
        }

        @Override
        public void draw(Graphics g) {
            if (!g.startClip(0, 0, getWidth(), getHeight())) {
                return;
            }
            float w = getWidth();
            int newCount = 0;
            long totalCents = 0;
            for (ChronicleRevealScene.RevealCard rc : lastOpened) {
                if (rc.firstPull) {
                    newCount++;
                }
                totalCents += rc.valueCents;
            }
            String headline = lastOpened.size() + " "
                    + Forge.getLocalizer().getMessageorUseDefault("lblChronicleCardsOpened", "cards")
                    + (newCount > 0 ? "  •  " + newCount + " NEW" : "")
                    + "  •  " + Forge.getLocalizer().getMessageorUseDefault("lblChronicleBuylist", "Buylist")
                    + " " + ChronicleHomeScreen.formatCents(totalCents);
            g.drawText(headline, FSkinFont.get(14), FLabel.getInlineLabelColor(),
                    0, -getScrollTop(), w, headerHeight(), false, Align.center, true);

            int cols = columns(w);
            float cellW = cellWidth(w);
            float cellH = cellW * FCardPanel.ASPECT_RATIO;
            for (int i = 0; i < lastOpened.size(); i++) {
                ChronicleRevealScene.RevealCard rc = lastOpened.get(i);
                float x = PADDING + (i % cols) * (cellW + PADDING);
                float y = headerHeight() + (i / cols) * (cellH + PADDING) - getScrollTop();
                if (y + cellH < 0 || y > getHeight()) {
                    continue;
                }
                CardRenderer.drawCard(g, rc.card, x, y, cellW, cellH, CardStackPosition.Top);
                if (rc.firstPull) {
                    float bw = Utils.scale(28);
                    float bh = Utils.scale(13);
                    g.fillRoundRect(FSkinColor.getStandardColor(new Color(0.9f, 0.75f, 0.2f, 1f)).getColor(),
                            x + Utils.scale(3), y + Utils.scale(3), bw, bh, Utils.scale(2));
                    g.drawText("NEW", FSkinFont.get(10), Color.BLACK,
                            x + Utils.scale(3), y + Utils.scale(3), bw, bh, false, Align.center, true);
                }
            }
            g.endClip();
        }

        @Override
        public boolean tap(float x, float y, int count) {
            int index = cellAt(x, y);
            if (index >= 0) {
                List<PaperCard> cards = new ArrayList<>();
                for (ChronicleRevealScene.RevealCard rc : lastOpened) {
                    cards.add(rc.card);
                }
                CardZoom.show(cards, index, null);
                return true;
            }
            return false;
        }

        private int cellAt(float x, float y) {
            float w = getWidth();
            int cols = columns(w);
            float cellW = cellWidth(w);
            float cellH = cellW * FCardPanel.ASPECT_RATIO;
            float gy = y + getScrollTop() - headerHeight();
            if (gy < 0) {
                return -1;
            }
            int col = (int) ((x - PADDING) / (cellW + PADDING));
            int row = (int) (gy / (cellH + PADDING));
            if (col < 0 || col >= cols) {
                return -1;
            }
            int index = row * cols + col;
            return index < lastOpened.size() ? index : -1;
        }
    }

    //--- layout --------------------------------------------------------------

    @Override
    protected void doLayout(float startY, float width, float height) {
        listPane.setBounds(0, startY, width, height - startY);

        float btnHeight = Utils.scale(40);
        float btnY = height - btnHeight - PADDING;
        if (revealScene != null) {
            revealScene.setBounds(0, startY, width, btnY - startY - PADDING);
        }
        btnSkip.setBounds(width * 0.6f, btnY, width * 0.34f, btnHeight);
        btnFlipCommons.setBounds(width * 0.06f, btnY, width * 0.46f, btnHeight);

        summaryPane.setBounds(0, startY, width, btnY - startY - PADDING);
        btnDone.setBounds(width * 0.55f, btnY, width * 0.39f, btnHeight);
        btnOpenAnother.setBounds(width * 0.06f, btnY, width * 0.44f, btnHeight);
    }
}
