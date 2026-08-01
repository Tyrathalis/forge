package forge.chronicle;

import java.util.ArrayList;
import java.util.List;

import forge.gui.FThreads;
import forge.Forge;
import forge.assets.FSkinFont;
import forge.assets.FSkinImage;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.gamemodes.chronicle.SealedItem;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.toolbox.FButton;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.toolbox.GuiChoose;
import forge.util.Utils;

/**
 * Chronicle's daily home: the session-script spine. Status at the top, then
 * the day's actions in script order — read the paper, collect the ration,
 * open sealed product, visit the store, browse the binder.
 *
 * Registered in NewGameMenu behind CHRONICLE_MODE_ENABLED (default off);
 * needs the public no-arg constructor for the menu's reflection.
 */
public class ChronicleHomeScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);

    private final FScrollPane scroller = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float x = PADDING;
            float y = PADDING;
            float w = visibleWidth - 2 * PADDING;
            for (FDisplayObject child : getChildren()) {
                if (child.isVisible()) {
                    child.setBounds(x, y, w, child.getHeight() == 0 ? Utils.scale(40) : child.getHeight());
                    y += child.getHeight() + PADDING;
                }
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });

    private final FLabel lblDay = statusLabel(FSkinImage.QUEST_BOOK);
    private final FLabel lblCash = statusLabel(FSkinImage.QUEST_COINSTACK);
    private final FLabel lblCollection = statusLabel(FSkinImage.DECKLIST);
    private final FButton btnBegin = scroller.add(new FButton(caption("lblChronicleBegin", "Begin - Summer 1993")));
    private final FButton btnPaper = scroller.add(new FButton(caption("lblChroniclePaper", "Read the Chronicle")));
    private final FButton btnCollect = scroller.add(new FButton(""));
    private final FButton btnOpen = scroller.add(new FButton(""));
    private final FButton btnStore = scroller.add(new FButton(caption("lblChronicleStore", "The Store")));
    private final FButton btnBinder = scroller.add(new FButton(caption("lblChronicleBinder", "Binder")));

    public ChronicleHomeScreen() {
        super("");
        btnBegin.setCommand(e -> beginRun());
        btnPaper.setCommand(e -> Forge.openScreen(new ChroniclePaperScreen()));
        btnCollect.setCommand(e -> collectRation());
        btnOpen.setCommand(e -> Forge.openScreen(new ChronicleOpenScreen()));
        btnStore.setCommand(e -> Forge.openScreen(new ChronicleLgsScreen()));
        btnBinder.setCommand(e -> Forge.openScreen(new ChronicleBinderScreen()));
        for (FDisplayObject btn : new FDisplayObject[] { btnBegin, btnPaper, btnCollect, btnOpen, btnStore, btnBinder }) {
            btn.setHeight(Utils.scale(44));
        }
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }

    private FLabel statusLabel(FSkinImage icon) {
        FLabel label = scroller.add(new FLabel.Builder().icon(icon).font(FSkinFont.get(16)).build());
        label.setHeight(Utils.scale(24));
        return label;
    }

    @Override
    public void onActivate() {
        super.onActivate();
        ChronicleHub.ensureLoaded(this::update);
    }

    private void update() {
        boolean hasRun = ChronicleHub.hasRun();
        lblDay.setVisible(hasRun);
        lblCash.setVisible(hasRun);
        lblCollection.setVisible(hasRun);
        btnBegin.setVisible(!hasRun);
        btnPaper.setVisible(hasRun);
        btnCollect.setVisible(hasRun);
        btnOpen.setVisible(hasRun);
        btnStore.setVisible(hasRun);
        btnBinder.setVisible(hasRun);
        if (hasRun) {
            ChronicleController controller = ChronicleHub.controller();
            lblDay.setText(caption("lblChronicleDay", "Day") + " " + (controller.getRun().timeline.getDayIndex() + 1));
            lblCash.setText(formatCents(controller.getRun().wallet.getCents()));
            lblCollection.setText(controller.getRun().collection.totalCopies() + " "
                    + caption("lblChronicleCards", "cards") + " / " + controller.getRun().collection.newCount() + " NEW");
            boolean canCollect = controller.canCollectRation();
            btnCollect.setEnabled(canCollect);
            btnCollect.setText(canCollect
                    ? caption("lblChronicleCollectRation", "Collect Daily Ration")
                    : caption("lblChronicleRationCollected", "Ration collected - see you tomorrow"));
            int sealedCount = controller.getRun().sealed.size();
            btnOpen.setEnabled(sealedCount > 0);
            btnOpen.setText(caption("lblChronicleOpenSealed", "Open Sealed") + " (" + sealedCount + ")");
        }
        scroller.revalidate();
    }

    static String formatCents(long cents) {
        return String.format("$%d.%02d", cents / 100, cents % 100);
    }

    private void beginRun() {
        FOptionPane.showConfirmDialog(
                caption("nlChronicleBegin", "Start a collector's run in the summer of 1993? Your allowance arrives with your first daily ration."),
                caption("lblChronicleMode", "Chronicle"),
                caption("lblChronicleBegin", "Begin - Summer 1993"), caption("lblCancel", "Cancel"), true, result -> {
                    if (result) {
                        LoadingOverlay.runBackgroundTask("", () -> {
                            ChronicleHub.startNewRun();
                            FThreads.invokeInEdtLater(this::update);
                        });
                    }
                });
    }

    private void collectRation() {
        ChronicleController controller = ChronicleHub.controller();
        List<ChronicleRelease> choices = controller.rationChoices();
        if (choices.isEmpty()) {
            FOptionPane.showErrorDialog(caption("lblChronicleNoRation", "Nothing is in print today."));
            return;
        }
        if (choices.size() == 1) {
            doCollect(choices.get(0));
            return;
        }
        List<String> names = new ArrayList<>();
        for (ChronicleRelease release : choices) {
            names.add(release.name);
        }
        GuiChoose.oneOrNone(caption("lblChronicleChooseRation", "Choose today's ration"), names, name -> {
            if (name == null) {
                return;
            }
            doCollect(choices.get(names.indexOf(name)));
        });
    }

    private void doCollect(ChronicleRelease chosen) {
        LoadingOverlay.runBackgroundTask("", () -> {
            final ChronicleController.DaySummary summary = ChronicleHub.controller().collectRation(chosen.editionCode);
            FThreads.invokeInEdtLater(() -> {
                update();
                showDaySummary(summary, chosen);
            });
        });
    }

    private void showDaySummary(ChronicleController.DaySummary summary, ChronicleRelease chosen) {
        StringBuilder sb = new StringBuilder();
        sb.append(caption("lblChronicleDay", "Day")).append(' ').append(summary.dayIndex + 1).append('\n');
        sb.append(summary.rationPacks.size()).append("x ").append(chosen.name).append(' ')
          .append(caption("lblChronicleBoosterPack", "booster pack")).append('\n');
        if (summary.stipendCredited > 0) {
            sb.append(caption("lblChronicleAllowance", "Allowance")).append(": +")
              .append(formatCents(summary.stipendCredited)).append('\n');
        }
        for (ChronicleRelease release : summary.releasesToday) {
            sb.append(release.name).append(' ').append(caption("lblChronicleHitsShelves", "hits shelves today!")).append('\n');
        }
        for (ChronicleRelease release : summary.lastChance) {
            sb.append(caption("lblChronicleLastChance", "Leaving shelves soon:")).append(' ').append(release.name).append('\n');
        }
        List<SealedItem> packs = summary.rationPacks;
        FOptionPane.showConfirmDialog(sb.toString(), caption("lblChronicleGoodMorning", "Good Morning"),
                caption("lblChronicleOpenNow", "Open now"), caption("lblChronicleLater", "Later"), true, openNow -> {
                    if (openNow && !packs.isEmpty()) {
                        Forge.openScreen(new ChronicleOpenScreen());
                    }
                });
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        scroller.setBounds(0, startY, width, height - startY);
    }
}
