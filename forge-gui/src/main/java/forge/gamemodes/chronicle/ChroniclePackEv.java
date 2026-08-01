package forge.gamemodes.chronicle;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

import forge.StaticData;
import forge.card.PrintSheet;
import forge.item.PaperCard;
import forge.item.SealedTemplate;
import forge.item.generation.BoosterGenerator;

/**
 * The Ante pack-EV ledger stub: with static buylist prices, the expected
 * buylist value of a pack is exactly computable from its print sheets —
 * per slot, the weighted mean over the sheet times the slot count. The
 * ethics identity ships with the MVP: the ledger shows, honestly, that
 * cracking packs for value loses money (the pack-EV-negative invariant,
 * re-checked against the tier table in the D5 numbers pass).
 *
 * Foil/replacement branches contribute nothing in the MVP window (all nine
 * sets are Foil=NotSupported with no replacement chances), so the slot sum
 * IS the exact EV.
 */
public final class ChroniclePackEv {

    private final ChroniclePricing pricing;
    private final Map<String, Long> evCache = new HashMap<>();

    public ChroniclePackEv(ChroniclePricing pricing) {
        this.pricing = pricing;
    }

    /** Exact expected buylist value of one sealed product, in cents (rounded down). */
    public long evCents(SealedItem.Kind kind, String editionCode) {
        String cacheKey = kind + "|" + editionCode;
        Long cached = evCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        SealedTemplate template = ChroniclePackGenerator.templateFor(kind, editionCode);
        String setCode = template.getEdition();
        double ev = 0;
        for (Pair<String, Integer> slot : template.getSlots()) {
            String sheetKey = StaticData.instance().getEditions().contains(setCode)
                    ? slot.getLeft().trim() + " " + setCode : slot.getLeft().trim();
            PrintSheet sheet = BoosterGenerator.makeSheet(sheetKey,
                    StaticData.instance().getCommonCards().getAllCards());
            List<PaperCard> flat = sheet.toFlatList();
            if (flat.isEmpty()) {
                continue;
            }
            long sum = 0;
            for (PaperCard card : flat) {
                sum += pricing.buylistCents(card);
            }
            ev += slot.getRight() * (sum / (double) flat.size());
        }
        long result = (long) ev;
        evCache.put(cacheKey, result);
        return result;
    }

    /** Ledger line: what a purchase expects to return at the buylist. Negative = the honest answer. */
    public long expectedNetCents(SealedItem.Kind kind, String editionCode, long priceCents) {
        return evCents(kind, editionCode) - priceCents;
    }
}
