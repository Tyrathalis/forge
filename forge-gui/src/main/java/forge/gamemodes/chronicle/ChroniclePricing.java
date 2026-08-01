package forge.gamemodes.chronicle;

import java.util.HashMap;
import java.util.Map;

import forge.card.CardRarity;
import forge.item.PaperCard;

/**
 * Static buylist pricing (what the shop pays for a card, integer cents):
 * a rarity-flat base plus the hand-authored 1994-desirability tier table —
 * the famous-card allowlist file's MVP-window column. Rarity-flat alone would
 * sell a pulled Lotus like bulk; all-prices.txt tiers are anachronistic and
 * would break the pack-EV-negative invariant.
 *
 * Notables are matched by card NAME (never name+rarity: ATQ prints the same
 * name at split rarities across sheets, and desirability is a name property).
 */
public final class ChroniclePricing {

    private final Map<CardRarity, Integer> rarityBaseCents;
    /** name -> buylist multiplier over the rarity base. */
    private final Map<String, Integer> notableMultipliers;

    public ChroniclePricing(Map<CardRarity, Integer> rarityBaseCents, Map<String, Integer> notableMultipliers) {
        this.rarityBaseCents = new HashMap<>(rarityBaseCents);
        this.notableMultipliers = new HashMap<>(notableMultipliers);
    }

    /**
     * Parse the notables table. Format, one STORY per line ('#' comments,
     * blanks skipped) — the schema anticipates the stage-2 allowlist growing
     * event-leg columns to the right of these:
     *
     * story-id|name[;name...]|multiplier
     */
    public static Map<String, Integer> parseNotables(Iterable<String> lines) {
        Map<String, Integer> result = new HashMap<>();
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] f = trimmed.split("\\|", -1);
            if (f.length < 3) {
                throw new IllegalArgumentException("Chronicle notables line " + lineNo + ": expected at least 3 fields");
            }
            int multiplier = Integer.parseInt(f[2].trim());
            for (String name : f[1].split(";")) {
                String cardName = name.trim();
                if (cardName.isEmpty()) {
                    continue;
                }
                Integer previous = result.put(cardName, multiplier);
                if (previous != null) {
                    throw new IllegalArgumentException("Chronicle notables line " + lineNo + ": duplicate card " + cardName);
                }
            }
        }
        return result;
    }

    /** Buylist value of one copy, in cents. Never below 1. */
    public int buylistCents(PaperCard card) {
        int base = rarityBaseCents.getOrDefault(card.getRarity(), rarityBaseCents.getOrDefault(CardRarity.Common, 1));
        Integer multiplier = notableMultipliers.get(card.getName());
        int value = multiplier == null ? base : base * multiplier;
        return Math.max(value, 1);
    }

    public boolean isNotable(String cardName) {
        return notableMultipliers.containsKey(cardName);
    }
}
