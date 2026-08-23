package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import forge.deck.CardPool;
import forge.item.PaperCard;

/**
 * Derives a rival's collection instead of storing it.
 *
 * The seed-integrity invariant says all randomness comes from (run seed, ...)
 * and can never be rerolled. A rival's cards are held to the same standard the
 * cheapest possible way: pack {@code i} of rival {@code r} is seeded by
 * (run seed, rival id, i) and NOTHING else — not the day, not the call site —
 * so pack i is the same pack forever and the collection is strictly cumulative.
 * On day D the rival owns packs [0, packsOwnedBy(D)); tomorrow that prefix
 * grows and every card in it is where it was.
 *
 * That also means the save stores nothing: a rival's collection is a pure
 * function of the run seed and the calendar, the same way the LGS stock roll
 * is. The cost is regenerating on demand, which is why results are cached per
 * (rival, day) — a deck is built at most once per rival per played day.
 *
 * The era bound is the interesting part: pack i is bought on
 * {@link ChronicleRival#acquisitionDay(int)}, and can only contain a product
 * RELEASED by then. A rival keeps what they bought — out-of-print cards stay in
 * the binder, which is what makes them a collector — so the pool spreads across
 * more and stronger sets as the timeline advances. Difficulty rises with the
 * era without a difficulty knob anywhere.
 */
public final class ChronicleRivalPool {

    /** Domain for a rival's pack contents. */
    public static final String DOMAIN_RIVAL_PACK = "rival-pack";
    /** Domain for which product a rival bought. */
    public static final String DOMAIN_RIVAL_PRODUCT = "rival-product";

    private final ChronicleCalendar calendar;
    private final long runSeed;
    /** (rivalId, dayIndex) -> pool. Bounded by cast size × days actually played this session. */
    private final Map<String, CardPool> cache = new HashMap<>();

    public ChronicleRivalPool(ChronicleCalendar calendar, long runSeed) {
        this.calendar = calendar;
        this.runSeed = runSeed;
    }

    /** Everything this rival owns by the end of the given played day. */
    public CardPool poolFor(ChronicleRival rival, int dayIndex) {
        String key = rival.id + "@" + dayIndex;
        CardPool cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        CardPool pool = new CardPool();
        int packs = rival.packsOwnedBy(dayIndex);
        for (int i = 0; i < packs; i++) {
            String editionCode = productForPack(rival, i);
            if (editionCode == null) {
                continue; //nothing had been released yet when this pack was notionally bought
            }
            for (PaperCard card : openRivalPack(rival, i, editionCode)) {
                pool.add(card, 1);
            }
        }
        cache.put(key, pool);
        return pool;
    }

    /**
     * Which product pack {@code index} was: a seeded pick over everything
     * RELEASED by that pack's acquisition day. Released, not in-print — a rival
     * who bought Alpha in week one still owns it in week ten.
     */
    String productForPack(ChronicleRival rival, int index) {
        List<ChronicleRelease> released = releasedBy(rival.acquisitionDay(index));
        if (released.isEmpty()) {
            return null;
        }
        Random rng = new Random(packSeed(rival, index, DOMAIN_RIVAL_PRODUCT));
        return released.get(rng.nextInt(released.size())).editionCode;
    }

    private List<ChronicleRelease> releasedBy(int dayIndex) {
        List<ChronicleRelease> result = new ArrayList<>();
        for (ChronicleRelease r : calendar.all()) {
            if (r.releaseDay <= dayIndex) {
                result.add(r);
            }
        }
        return result;
    }

    private List<PaperCard> openRivalPack(ChronicleRival rival, int index, String editionCode) {
        SealedItem item = new SealedItem(index, SealedItem.Kind.BOOSTER, editionCode,
                packSeed(rival, index, DOMAIN_RIVAL_PACK), rival.acquisitionDay(index));
        try {
            return ChroniclePackGenerator.open(item);
        } catch (RuntimeException e) {
            //a product with no booster template (starter-only rows) — the rival just
            //didn't buy anything that day rather than the roster failing to load
            return new ArrayList<>();
        }
    }

    /** Seed for one of a rival's packs: (run seed, domain, rival id, pack index) — deliberately day-free. */
    long packSeed(ChronicleRival rival, int index, String domain) {
        long itemId = ChronicleSeeds.fnv1a64(rival.id) ^ ChronicleSeeds.mix64(index);
        return ChronicleSeeds.deriveItem(runSeed, domain, itemId);
    }

    /** Drop cached pools — used when a run is replaced under a live controller. */
    public void clearCache() {
        cache.clear();
    }
}
