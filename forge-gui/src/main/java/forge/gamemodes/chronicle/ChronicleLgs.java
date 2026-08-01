package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The LGS daily deal shelf: a seeded stock roll of discounted offers over the
 * in-print products, re-derived deterministically from (run seed, day index)
 * on every look — only the purchase counts persist (the Adventure
 * seed-not-inventory pattern, with a local Random instead of a shared global).
 *
 * The full-price shelf (any in-print product at MSRP, unlimited) is separate
 * and stateless; these are the rotating deals that make the daily check-in a
 * hook.
 */
public final class ChronicleLgs {

    /** One rolled deal: a product at a discount, limited quantity. */
    public static final class StockOffer {
        public enum OfferKind {
            BOOSTER, BOX, STARTER
        }

        public final int slot;
        public final String editionCode;
        public final OfferKind kind;
        /** Unit price in cents, discount applied (for BOX: the whole box). */
        public final int priceCents;
        public final int quantity;
        public final int discountPercent;

        StockOffer(int slot, String editionCode, OfferKind kind, int priceCents, int quantity, int discountPercent) {
            this.slot = slot;
            this.editionCode = editionCode;
            this.kind = kind;
            this.priceCents = priceCents;
            this.quantity = quantity;
            this.discountPercent = discountPercent;
        }
    }

    /** Day the purchase counters belong to. */
    private int purchasesDay = -1;
    private int[] purchased = new int[0];

    /**
     * Today's stock. Deterministic in (runSeed, dayIndex): same inputs, same
     * offers, on every call and every reload.
     */
    public List<StockOffer> stockFor(ChronicleCalendar calendar, ChronicleConfig config, long runSeed, int dayIndex) {
        List<ChronicleRelease> inPrint = calendar.inPrintOn(dayIndex);
        if (inPrint.isEmpty()) {
            return Collections.emptyList();
        }
        Random rng = ChronicleSeeds.random(
                ChronicleSeeds.deriveDaily(runSeed, dayIndex, ChronicleSeeds.DOMAIN_LGS_STOCK));
        List<StockOffer> offers = new ArrayList<>();
        for (int slot = 0; slot < config.lgsStockSlots; slot++) {
            ChronicleRelease product = inPrint.get(rng.nextInt(inPrint.size()));
            int kindRoll = rng.nextInt(100);
            int discountPercent = rng.nextInt(16); // 0-15% off
            StockOffer.OfferKind kind;
            int unitCents;
            int quantity;
            if (kindRoll < 60) {
                kind = StockOffer.OfferKind.BOOSTER;
                unitCents = product.boosterCents;
                quantity = 2 + rng.nextInt(7);
            } else if (kindRoll < 85) {
                kind = StockOffer.OfferKind.BOX;
                unitCents = product.boxCents;
                quantity = 1;
            } else if (product.hasStarter()) {
                kind = StockOffer.OfferKind.STARTER;
                unitCents = product.starterCents;
                quantity = 1 + rng.nextInt(3);
            } else {
                kind = StockOffer.OfferKind.BOOSTER;
                unitCents = product.boosterCents;
                quantity = 2 + rng.nextInt(7);
            }
            int priceCents = Math.max(1, unitCents * (100 - discountPercent) / 100);
            offers.add(new StockOffer(slot, product.editionCode, kind, priceCents, quantity, discountPercent));
        }
        return offers;
    }

    /** Units already bought from a slot today. */
    public int purchasedFrom(int dayIndex, int slot) {
        if (dayIndex != purchasesDay || slot >= purchased.length) {
            return 0;
        }
        return purchased[slot];
    }

    /** Record a purchase of one unit from a slot. False if the slot is sold out. */
    public boolean recordPurchase(ChronicleConfig config, StockOffer offer, int dayIndex) {
        if (dayIndex != purchasesDay) {
            purchasesDay = dayIndex;
            purchased = new int[config.lgsStockSlots];
        }
        if (offer.slot >= purchased.length || purchased[offer.slot] >= offer.quantity) {
            return false;
        }
        purchased[offer.slot]++;
        return true;
    }

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("purchasesDay", purchasesDay);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < purchased.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(purchased[i]);
        }
        data.store("purchased", sb.toString());
        return data;
    }

    public void load(ChronicleSaveData data) {
        purchasesDay = data.containsKey("purchasesDay") ? data.readInt("purchasesDay") : -1;
        String block = data.readString("purchased");
        if (block == null || block.isEmpty()) {
            purchased = new int[0];
            return;
        }
        String[] parts = block.split(",");
        purchased = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            purchased[i] = Integer.parseInt(parts[i]);
        }
    }
}
