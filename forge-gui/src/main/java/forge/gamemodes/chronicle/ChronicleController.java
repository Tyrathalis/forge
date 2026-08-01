package forge.gamemodes.chronicle;

import java.io.File;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import forge.StaticData;
import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveIO;

/**
 * Chronicle's daily-session orchestrator: the one class the screens talk to.
 * Composes the headless services around a loaded run; holds NO engine or
 * FModel state of its own (Adventure's own-singleton pattern, deliberately
 * not Quest's FModel entanglement).
 *
 * All rules the services enforce individually meet here: the tick fires on
 * ration collection, the stipend credits on its payday tick, purchases check
 * the shelf window, sealed contents commit at acquisition, and drill-down
 * mutations autosave.
 */
public final class ChronicleController {

    /** What a played-day tick delivered — the daily session's opening summary. */
    public static final class DaySummary {
        public final int dayIndex;
        public final long stipendCredited;
        public final List<SealedItem> rationPacks;
        public final List<ChronicleRelease> releasesToday;
        public final List<ChronicleRelease> lastChance;

        DaySummary(int dayIndex, long stipendCredited, List<SealedItem> rationPacks,
                   List<ChronicleRelease> releasesToday, List<ChronicleRelease> lastChance) {
            this.dayIndex = dayIndex;
            this.stipendCredited = stipendCredited;
            this.rationPacks = rationPacks;
            this.releasesToday = releasesToday;
            this.lastChance = lastChance;
        }
    }

    /** Played-day horizon for last-chance shelf warnings in the paper. */
    public static final int LAST_CHANCE_HORIZON_DAYS = 7;

    private final ChronicleCalendar calendar;
    private final ChronicleConfig config;
    private final ChroniclePricing pricing;
    private final ChroniclePackEv packEv;
    private final ChronicleCollection.CardResolver resolver;
    private final Clock clock;

    private ChronicleRun run;
    /** Autosave target; null = autosave off (tests drive saves explicitly). */
    private File autosaveFile;

    public ChronicleController(ChronicleCalendar calendar, ChronicleConfig config, ChroniclePricing pricing,
                               ChronicleCollection.CardResolver resolver, Clock clock) {
        this.calendar = calendar;
        this.config = config;
        this.pricing = pricing;
        this.packEv = new ChroniclePackEv(pricing);
        this.resolver = resolver;
        this.clock = clock;
    }

    /** Production card resolver backed by the loaded card DB. */
    public static ChronicleCollection.CardResolver cardDbResolver() {
        return (name, edition, artIndex, foil) -> {
            PaperCard card = StaticData.instance().getCommonCards().getCard(name, edition, artIndex);
            if (card == null) {
                return null;
            }
            return foil ? card.getFoiled() : card;
        };
    }

    public ChronicleCalendar getCalendar() {
        return calendar;
    }

    public ChronicleConfig getConfig() {
        return config;
    }

    public ChroniclePricing getPricing() {
        return pricing;
    }

    public ChroniclePackEv getPackEv() {
        return packEv;
    }

    public ChronicleRun getRun() {
        return run;
    }

    public void setAutosaveFile(File file) {
        this.autosaveFile = file;
    }

    // --- run lifecycle -----------------------------------------------------

    public ChronicleRun newRun(long runSeed) {
        run = ChronicleRun.newRun(runSeed);
        return run;
    }

    public boolean saveTo(File file) {
        return ChronicleSaveIO.save(file, run.buildHeader(), run.save());
    }

    public boolean loadFrom(File file) {
        ChronicleSaveIO.Loaded loaded = ChronicleSaveIO.load(file);
        if (loaded == null) {
            return false;
        }
        run = ChronicleRun.load(loaded.main, resolver);
        return true;
    }

    private void autosave() {
        if (autosaveFile != null) {
            saveTo(autosaveFile);
        }
    }

    // --- the daily tick ----------------------------------------------------

    /** The day the next collection will begin (pre-collection, the player is still living the previous day). */
    public int nextDayIndex() {
        return run.timeline.hasEverTicked() ? run.timeline.getDayIndex() + 1 : 0;
    }

    public boolean canCollectRation() {
        return run.timeline.canTick(clock);
    }

    /** Ration products valid for the next collection: ration-eligible and in print on the day being started. */
    public List<ChronicleRelease> rationChoices() {
        List<ChronicleRelease> result = new ArrayList<>();
        for (ChronicleRelease r : calendar.inPrintOn(nextDayIndex())) {
            if (r.rationEligible) {
                result.add(r);
            }
        }
        return result;
    }

    /**
     * Collect the daily ration — THE consuming act that fires the played-day
     * tick (never app launch; at most one per real calendar day).
     */
    public DaySummary collectRation(String chosenEditionCode) {
        if (!run.timeline.canTick(clock)) {
            throw new IllegalStateException("Chronicle: ration already collected today");
        }
        int day = nextDayIndex();
        ChronicleRelease chosen = calendar.byCode(chosenEditionCode);
        if (chosen == null || !chosen.rationEligible || !chosen.inPrintOn(day)) {
            throw new IllegalArgumentException("Chronicle: " + chosenEditionCode + " is not a valid ration choice on day " + day);
        }
        run.timeline.tick(clock);
        long stipend = run.wallet.creditStipendIfDue(day, config.stipendPeriodDays, config.stipendCents);
        List<SealedItem> packs = run.sealed.acquire(run.runSeed, SealedItem.Kind.BOOSTER, chosenEditionCode, day, config.rationPacks);
        bumpMetaCounter("totalDaysPlayed");
        autosave();
        return new DaySummary(day, stipend, packs, calendar.releasingOn(day),
                calendar.leavingShelfWithin(day, LAST_CHANCE_HORIZON_DAYS));
    }

    // --- the shelf and the LGS ---------------------------------------------

    /** In-print products on the current day (the full-price shelf). */
    public List<ChronicleRelease> shelf() {
        return calendar.inPrintOn(run.timeline.getDayIndex());
    }

    /** Today's LGS deals (seed-derived; same every look, every reload). */
    public List<ChronicleLgs.StockOffer> lgsStock() {
        return run.lgs.stockFor(calendar, config, run.runSeed, run.timeline.getDayIndex());
    }

    /** Buy at MSRP from the shelf. BOX materializes as its component boosters, each with its own committed seed. */
    public List<SealedItem> buyFromShelf(String editionCode, ChronicleLgs.StockOffer.OfferKind kind) {
        int day = run.timeline.getDayIndex();
        ChronicleRelease product = calendar.byCode(editionCode);
        if (product == null || !product.inPrintOn(day)) {
            throw new IllegalArgumentException("Chronicle: " + editionCode + " is not on the shelf on day " + day);
        }
        long price = msrpFor(product, kind);
        if (!run.wallet.debit(price)) {
            return null;
        }
        List<SealedItem> items = materialize(product, kind, day);
        autosave();
        return items;
    }

    /** Buy one unit of a rolled LGS deal. Null = sold out or can't afford. */
    public List<SealedItem> buyLgsOffer(ChronicleLgs.StockOffer offer) {
        int day = run.timeline.getDayIndex();
        if (run.lgs.purchasedFrom(day, offer.slot) >= offer.quantity) {
            return null;
        }
        if (!run.wallet.canAfford(offer.priceCents)) {
            return null;
        }
        if (!run.lgs.recordPurchase(config, offer, day)) {
            return null;
        }
        run.wallet.debit(offer.priceCents);
        ChronicleRelease product = calendar.byCode(offer.editionCode);
        List<SealedItem> items = materialize(product, offer.kind, day);
        autosave();
        return items;
    }

    private long msrpFor(ChronicleRelease product, ChronicleLgs.StockOffer.OfferKind kind) {
        switch (kind) {
            case BOOSTER:
                return product.boosterCents;
            case BOX:
                return product.boxCents;
            case STARTER:
                if (!product.hasStarter()) {
                    throw new IllegalArgumentException("Chronicle: " + product.editionCode + " has no starter product");
                }
                return product.starterCents;
            default:
                throw new IllegalArgumentException("Chronicle: unknown product kind " + kind);
        }
    }

    private List<SealedItem> materialize(ChronicleRelease product, ChronicleLgs.StockOffer.OfferKind kind, int day) {
        switch (kind) {
            case BOOSTER:
                return run.sealed.acquire(run.runSeed, SealedItem.Kind.BOOSTER, product.editionCode, day, 1);
            case BOX:
                return run.sealed.acquire(run.runSeed, SealedItem.Kind.BOOSTER, product.editionCode, day, product.packsPerBox);
            case STARTER:
                return run.sealed.acquire(run.runSeed, SealedItem.Kind.STARTER, product.editionCode, day, 1);
            default:
                throw new IllegalArgumentException("Chronicle: unknown product kind " + kind);
        }
    }

    // --- opening and selling -----------------------------------------------

    /** Open a sealed item: reveal its committed contents into the collection. */
    public List<PaperCard> openSealed(long itemId) {
        SealedItem item = run.sealed.take(itemId);
        if (item == null) {
            throw new IllegalArgumentException("Chronicle: unknown sealed item " + itemId);
        }
        List<PaperCard> cards = ChroniclePackGenerator.open(item);
        run.collection.addAll(cards);
        run.acquisitions.record(run.timeline.getDayIndex(), item.kind, item.editionCode, cards);
        bumpMetaCounter(item.kind == SealedItem.Kind.BOOSTER ? "totalPacksOpened" : "totalStartersOpened");
        autosave();
        return cards;
    }

    /** Sell copies to the static buylist. Returns cents credited, or -1 if not owned. */
    public long sellToBuylist(PaperCard card, int amount) {
        if (!run.collection.remove(card, amount)) {
            return -1;
        }
        long credit = (long) pricing.buylistCents(card) * amount;
        run.wallet.credit(credit);
        autosave();
        return credit;
    }

    /** Binder pass: clear the NEW badge for cards actually viewed, one autosave for the batch. */
    public void markSeen(Iterable<PaperCard> cards) {
        boolean any = false;
        for (PaperCard card : cards) {
            run.collection.markSeen(card);
            any = true;
        }
        if (any) {
            autosave();
        }
    }

    private void bumpMetaCounter(String key) {
        run.meta.store(key, run.meta.readLong(key) + 1);
    }
}
