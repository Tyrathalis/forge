package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The player's collection: printing×finish inventory keyed on PaperCard
 * identity (name+edition+collectorNumber+artIndex+foil — forge-core's
 * CardPool keys on exactly this), plus new-card tracking with per-card
 * clear-on-seen for the binder's NEW badge.
 *
 * Persistence stores identity lines, not serialized card objects — saves
 * survive card-DB updates and are re-resolved at load through a
 * CardResolver (production: CardDb; tests: fixtures).
 */
public final class ChronicleCollection {

    /** Resolves a persisted identity line back to a PaperCard. Null = card no longer in the DB (dropped with a warning). */
    public interface CardResolver {
        PaperCard resolve(String name, String edition, int artIndex, boolean foil);
    }

    private final CardPool owned = new CardPool();
    /** Printings acquired but not yet seen in the binder. */
    private final Set<PaperCard> newCards = new HashSet<>();
    /** Printings the player has ever seen — an acquisition is only NEW the first time. */
    private final Set<PaperCard> everSeen = new HashSet<>();

    /** Add copies; flags the printing NEW if it has never been seen. */
    public void add(PaperCard card, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("add " + amount);
        }
        owned.add(card, amount);
        if (!everSeen.contains(card)) {
            newCards.add(card);
        }
    }

    public void addAll(Collection<PaperCard> cards) {
        for (PaperCard card : cards) {
            add(card, 1);
        }
    }

    /** Remove copies (buylist sale). False (and no change) if fewer are owned. */
    public boolean remove(PaperCard card, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("remove " + amount);
        }
        if (owned.count(card) < amount) {
            return false;
        }
        owned.remove(card, amount);
        if (owned.count(card) == 0) {
            newCards.remove(card);
        }
        return true;
    }

    public int count(PaperCard card) {
        return owned.count(card);
    }

    /** Total copies across all printings. */
    public int totalCopies() {
        return owned.countAll();
    }

    /** Distinct printings owned. */
    public int distinctOwned() {
        return owned.countDistinct();
    }

    public boolean isNew(PaperCard card) {
        return newCards.contains(card);
    }

    public int newCount() {
        return newCards.size();
    }

    /** Binder look: clears the NEW badge for this printing and remembers it was seen. */
    public void markSeen(PaperCard card) {
        newCards.remove(card);
        everSeen.add(card);
    }

    public Iterable<Map.Entry<PaperCard, Integer>> entries() {
        return owned;
    }

    /** Distinct printings owned out of the given per-set universe. */
    public int[] completion(Collection<PaperCard> setUniverse) {
        int ownedDistinct = 0;
        for (PaperCard card : setUniverse) {
            if (owned.count(card) > 0) {
                ownedDistinct++;
            }
        }
        return new int[] { ownedDistinct, setUniverse.size() };
    }

    // --- persistence -------------------------------------------------------

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        List<String> ownedLines = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : owned) {
            ownedLines.add(e.getValue() + "|" + identityLine(e.getKey()));
        }
        Collections.sort(ownedLines);
        data.store("owned", String.join("\n", ownedLines));
        data.store("new", joinIdentities(newCards));
        data.store("seen", joinIdentities(everSeen));
        return data;
    }

    public void load(ChronicleSaveData data, CardResolver resolver) {
        owned.clear();
        newCards.clear();
        everSeen.clear();
        String ownedBlock = data.readString("owned");
        if (ownedBlock != null && !ownedBlock.isEmpty()) {
            for (String line : ownedBlock.split("\n")) {
                int sep = line.indexOf('|');
                int amount = Integer.parseInt(line.substring(0, sep));
                PaperCard card = parseIdentityLine(line.substring(sep + 1), resolver);
                if (card != null) {
                    owned.add(card, amount);
                }
            }
        }
        loadIdentitySet(data.readString("new"), resolver, newCards);
        loadIdentitySet(data.readString("seen"), resolver, everSeen);
    }

    private static String joinIdentities(Set<PaperCard> cards) {
        Set<String> lines = new TreeSet<>();
        for (PaperCard card : cards) {
            lines.add(identityLine(card));
        }
        return String.join("\n", lines);
    }

    private static void loadIdentitySet(String block, CardResolver resolver, Set<PaperCard> target) {
        if (block == null || block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n")) {
            PaperCard card = parseIdentityLine(line, resolver);
            if (card != null) {
                target.add(card);
            }
        }
    }

    private static String identityLine(PaperCard card) {
        return card.getName() + "\t" + card.getEdition() + "\t" + card.getArtIndex() + "\t" + card.isFoil();
    }

    private static PaperCard parseIdentityLine(String line, CardResolver resolver) {
        String[] f = line.split("\t", -1);
        if (f.length < 4) {
            System.err.println("Chronicle collection: malformed identity line: " + line);
            return null;
        }
        PaperCard card = resolver.resolve(f[0], f[1], Integer.parseInt(f[2]), Boolean.parseBoolean(f[3]));
        if (card == null) {
            System.err.println("Chronicle collection: unresolvable card dropped: " + line);
        }
        return card;
    }
}
