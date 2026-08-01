package forge.chronicle;

import java.util.List;

import com.badlogic.gdx.utils.Align;

import forge.gui.FThreads;
import forge.Forge;
import forge.assets.FSkinFont;
import forge.assets.FSkinImage;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleLgs;
import forge.gamemodes.chronicle.ChronicleRelease;
import forge.gamemodes.chronicle.SealedItem;
import forge.screens.FScreen;
import forge.screens.LoadingOverlay;
import forge.toolbox.FButton;
import forge.toolbox.FContainer;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FLabel;
import forge.toolbox.FOptionPane;
import forge.toolbox.FScrollPane;
import forge.util.Utils;

/**
 * The LGS: the full-price shelf (any in-print product at MSRP, unlimited) and
 * today's seeded deal slots (discounted, limited, sold-out state). Purchases
 * confirm, debit the wallet, and materialize sealed items with committed
 * contents seeds.
 */
public class ChronicleLgsScreen extends FScreen {

    private static final float PADDING = Utils.scale(6);
    private static final float ROW_HEIGHT = Utils.scale(44);

    private final FLabel lblCash = add(new FLabel.Builder().icon(FSkinImage.QUEST_COINSTACK).font(FSkinFont.get(16)).build());
    private final FScrollPane scroller = add(new FScrollPane() {
        @Override
        protected ScrollBounds layoutAndGetScrollBounds(float visibleWidth, float visibleHeight) {
            float y = PADDING;
            for (FDisplayObject child : getChildren()) {
                float childHeight = child instanceof ProductTile ? ((ProductTile) child).preferredHeight() : ROW_HEIGHT;
                child.setBounds(PADDING, y, visibleWidth - 2 * PADDING, childHeight);
                y += childHeight + PADDING;
            }
            return new ScrollBounds(visibleWidth, y);
        }
    });

    public ChronicleLgsScreen() {
        super(Forge.getLocalizer().getMessageorUseDefault("lblChronicleStore", "The Store"));
    }

    @Override
    public void onActivate() {
        super.onActivate();
        update();
    }

    private static String caption(String key, String fallback) {
        return Forge.getLocalizer().getMessageorUseDefault(key, fallback);
    }

    private void update() {
        ChronicleController controller = ChronicleHub.controller();
        lblCash.setText(ChronicleHomeScreen.formatCents(controller.getRun().wallet.getCents()));
        scroller.clear();

        //deals first: the daily roll is the check-in hook, and a discounted product
        //should greet the player before its full-price shelf button
        int today = controller.getRun().timeline.getDayIndex();
        List<ChronicleLgs.StockOffer> stock = controller.lgsStock();
        if (!stock.isEmpty()) {
            scroller.add(sectionLabel(caption("lblChronicleTodaysDeals", "Today's deals")));
            for (ChronicleLgs.StockOffer offer : stock) {
                scroller.add(dealRow(offer, today));
            }
        }

        scroller.add(sectionLabel(caption("lblChronicleOnTheShelf", "On the shelf")));
        for (ChronicleRelease release : controller.shelf()) {
            scroller.add(new ProductTile(release, today));
        }
        scroller.revalidate();
    }

    private FLabel sectionLabel(String text) {
        return new FLabel.Builder().text(text).font(FSkinFont.get(18)).align(Align.left).build();
    }

    private FButton dealRow(ChronicleLgs.StockOffer offer, int today) {
        ChronicleController controller = ChronicleHub.controller();
        ChronicleRelease product = controller.getCalendar().byCode(offer.editionCode);
        int remaining = offer.quantity - controller.getRun().lgs.purchasedFrom(today, offer.slot);
        String name = product == null ? offer.editionCode : product.name;
        String kindName = kindName(offer.kind);
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(' ').append(kindName).append("  ")
          .append(ChronicleHomeScreen.formatCents(offer.priceCents));
        if (offer.discountPercent > 0) {
            sb.append(" (-").append(offer.discountPercent).append("%)");
        }
        sb.append("  ").append(remaining).append(' ').append(caption("lblChronicleLeft", "left"));

        FButton row = new FButton(sb.toString());
        if (remaining <= 0) {
            row.setEnabled(false);
            row.setText(name + " " + kindName + "  " + caption("lblChronicleSoldOut", "SOLD OUT"));
        } else {
            row.setCommand(e -> confirmPurchase(name + " " + kindName, offer.priceCents,
                    () -> controller.buyLgsOffer(offer)));
        }
        return row;
    }

    private String kindName(ChronicleLgs.StockOffer.OfferKind kind) {
        switch (kind) {
            case BOOSTER: return caption("lblChronicleBoosterPack", "booster pack");
            case BOX: return caption("lblChronicleBoosterBox", "booster box");
            case STARTER: return caption("lblChronicleStarterDeck", "starter deck");
            default: return kind.toString();
        }
    }

    /** Runs the purchase off the GL thread after a confirm; null result = refused (funds/sold out). */
    private void confirmPurchase(String what, long priceCents, java.util.function.Supplier<List<SealedItem>> purchase) {
        ChronicleController controller = ChronicleHub.controller();
        if (!controller.getRun().wallet.canAfford(priceCents)) {
            FOptionPane.showMessageDialog(caption("lblChronicleCantAfford", "You can't afford that."));
            return;
        }
        FOptionPane.showConfirmDialog(
                what + "\n" + ChronicleHomeScreen.formatCents(priceCents),
                caption("lblChronicleStore", "The Store"),
                caption("lblChronicleBuy", "Buy"), caption("lblCancel", "Cancel"), true, confirmed -> {
                    if (!confirmed) {
                        return;
                    }
                    LoadingOverlay.runBackgroundTask("", () -> {
                        final List<SealedItem> bought = purchase.get();
                        FThreads.invokeInEdtLater(() -> {
                            if (bought == null) {
                                FOptionPane.showMessageDialog(caption("lblChronicleSoldOut", "SOLD OUT"));
                            }
                            update();
                        });
                    });
                });
    }

    /** One in-print product: name, shelf note, and its MSRP purchase buttons. */
    private class ProductTile extends FContainer {
        private final FLabel lblName;
        private final FButton btnBooster;
        private final FButton btnStarter;
        private final FButton btnBox;

        ProductTile(ChronicleRelease release, int today) {
            int daysLeft = release.lastShelfDay() - today;
            String note = daysLeft <= ChronicleController.LAST_CHANCE_HORIZON_DAYS
                    ? "  (" + caption("lblChronicleLeavingIn", "leaving in") + " " + (daysLeft + 1) + "d)" : "";
            lblName = add(new FLabel.Builder().text(release.name + note).font(FSkinFont.get(16)).align(Align.left).build());

            btnBooster = add(new FButton(caption("lblChroniclePack", "Pack") + " " + ChronicleHomeScreen.formatCents(release.boosterCents)));
            btnBooster.setCommand(e -> confirmPurchase(release.name + " " + kindName(ChronicleLgs.StockOffer.OfferKind.BOOSTER),
                    release.boosterCents,
                    () -> ChronicleHub.controller().buyFromShelf(release.editionCode, ChronicleLgs.StockOffer.OfferKind.BOOSTER)));

            if (release.hasStarter()) {
                btnStarter = add(new FButton(caption("lblChronicleStarter", "Starter") + " " + ChronicleHomeScreen.formatCents(release.starterCents)));
                btnStarter.setCommand(e -> confirmPurchase(release.name + " " + kindName(ChronicleLgs.StockOffer.OfferKind.STARTER),
                        release.starterCents,
                        () -> ChronicleHub.controller().buyFromShelf(release.editionCode, ChronicleLgs.StockOffer.OfferKind.STARTER)));
            } else {
                btnStarter = null;
            }

            btnBox = add(new FButton(caption("lblChronicleBox", "Box") + " " + ChronicleHomeScreen.formatCents(release.boxCents)));
            btnBox.setCommand(e -> confirmPurchase(release.name + " " + kindName(ChronicleLgs.StockOffer.OfferKind.BOX),
                    release.boxCents,
                    () -> ChronicleHub.controller().buyFromShelf(release.editionCode, ChronicleLgs.StockOffer.OfferKind.BOX)));
        }

        float preferredHeight() {
            return Utils.scale(24) + ROW_HEIGHT + PADDING;
        }

        @Override
        protected void doLayout(float width, float height) {
            float labelHeight = Utils.scale(24);
            lblName.setBounds(0, 0, width, labelHeight);
            int buttons = btnStarter == null ? 2 : 3;
            float buttonWidth = (width - (buttons - 1) * PADDING) / buttons;
            float x = 0;
            float y = labelHeight;
            btnBooster.setBounds(x, y, buttonWidth, ROW_HEIGHT);
            x += buttonWidth + PADDING;
            if (btnStarter != null) {
                btnStarter.setBounds(x, y, buttonWidth, ROW_HEIGHT);
                x += buttonWidth + PADDING;
            }
            btnBox.setBounds(x, y, buttonWidth, ROW_HEIGHT);
        }
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float cashHeight = Utils.scale(26);
        lblCash.setBounds(PADDING, startY + PADDING / 2, width - 2 * PADDING, cashHeight);
        float top = startY + cashHeight + PADDING;
        scroller.setBounds(0, top, width, height - top);
    }
}
