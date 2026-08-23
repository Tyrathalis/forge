package forge.gamemodes.chronicle;

import java.util.HashMap;
import java.util.Map;

import forge.card.CardRarity;

/**
 * Chronicle economy knobs, parsed from res/chronicle/economy.txt. Everything
 * here is a D5-numbers-pass tuning surface; defaults are the plan's seed
 * values.
 */
public final class ChronicleConfig {

    /** Packs in the free daily ration. */
    public final int rationPacks;
    /** Allowance stipend: amount and played-day period (weekly lump by default). */
    public final long stipendCents;
    public final int stipendPeriodDays;
    /** LGS daily deal slots. */
    public final int lgsStockSlots;
    /** Kitchen-table purse: base cents a rival's win pays, before their pursePercent. */
    public final long pursebaseCents;
    /** Buylist base, cents per rarity. */
    public final Map<CardRarity, Integer> buylistBaseCents;

    public ChronicleConfig(int rationPacks, long stipendCents, int stipendPeriodDays, int lgsStockSlots,
                           long pursebaseCents, Map<CardRarity, Integer> buylistBaseCents) {
        this.rationPacks = rationPacks;
        this.stipendCents = stipendCents;
        this.stipendPeriodDays = stipendPeriodDays;
        this.lgsStockSlots = lgsStockSlots;
        this.pursebaseCents = pursebaseCents;
        this.buylistBaseCents = new HashMap<>(buylistBaseCents);
    }

    /**
     * Parse key=value lines ('#' comments, blanks skipped). Unknown keys are
     * ignored (forward compatibility); missing keys take defaults.
     */
    public static ChronicleConfig parse(Iterable<String> lines) {
        Map<String, String> kv = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Chronicle config: malformed line: " + trimmed);
            }
            kv.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        Map<CardRarity, Integer> base = new HashMap<>();
        base.put(CardRarity.BasicLand, intOf(kv, "buylistBasicLandCents", 1));
        base.put(CardRarity.Common, intOf(kv, "buylistCommonCents", 2));
        base.put(CardRarity.Uncommon, intOf(kv, "buylistUncommonCents", 10));
        base.put(CardRarity.Rare, intOf(kv, "buylistRareCents", 40));
        return new ChronicleConfig(
                intOf(kv, "rationPacks", 2),
                intOf(kv, "stipendCents", 1000),
                intOf(kv, "stipendPeriodDays", 7),
                intOf(kv, "lgsStockSlots", 4),
                intOf(kv, "pursebaseCents", 150),
                base);
    }

    private static int intOf(Map<String, String> kv, String key, int fallback) {
        String v = kv.get(key);
        return v == null ? fallback : Integer.parseInt(v);
    }
}
