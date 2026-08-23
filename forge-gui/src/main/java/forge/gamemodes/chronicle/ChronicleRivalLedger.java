package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * What ante has moved between the player and each rival.
 *
 * A rival's collection is derived, not stored — that is what lets the save carry
 * nothing for it and makes their cards as unrerollable as the player's. Ante
 * breaks that on its own, because cards now actually change hands. Rather than
 * give up and store whole rival collections, only the DELTA persists:
 *
 *   pool(rival, day) = derived(packs owned by day) − lostToPlayer + wonFromPlayer
 *
 * The derived base stays a pure function of the run seed, the delta is bounded
 * by how many ante games have actually been played, and — the part that matters
 * for the economy — a rival's baseline keeps growing on its own schedule, so a
 * stripped rival recovers naturally as their allowance rolls in. There is no
 * catch-up mechanic because none is needed, and no rubber band to tune.
 *
 * That also bounds the world's card supply. Total cards extractable from a rival
 * over a whole run can never exceed what their derived curve gave them, so ante
 * cannot pull cards out of the world faster than the release calendar puts them
 * in — the scarcity engine and the pack-EV invariant both survive contact with
 * an unlimited match channel.
 */
public final class ChronicleRivalLedger {

    private final Map<String, CardPool> lostToPlayer = new HashMap<>();
    private final Map<String, CardPool> wonFromPlayer = new HashMap<>();

    /** Cards this rival has lost to the player (subtracted from their derived pool). */
    public CardPool lostBy(String rivalId) {
        return lostToPlayer.computeIfAbsent(rivalId, k -> new CardPool());
    }

    /** Cards this rival has taken off the player (added to their derived pool). */
    public CardPool wonBy(String rivalId) {
        return wonFromPlayer.computeIfAbsent(rivalId, k -> new CardPool());
    }

    /**
     * Record one ante settlement from the rival's point of view.
     *
     * A card the rival previously won back off the player cancels against the
     * won pile before it is booked as a loss, and vice versa — otherwise the two
     * piles would both grow forever as one card ping-ponged between binders.
     */
    public void settle(String rivalId, Iterable<PaperCard> rivalLost, Iterable<PaperCard> rivalWon) {
        CardPool lost = lostBy(rivalId);
        CardPool won = wonBy(rivalId);
        for (PaperCard card : rivalLost) {
            if (won.count(card) > 0) {
                won.remove(card, 1);
            } else {
                lost.add(card, 1);
            }
        }
        for (PaperCard card : rivalWon) {
            if (lost.count(card) > 0) {
                lost.remove(card, 1);
            } else {
                won.add(card, 1);
            }
        }
    }

    /** Apply this rival's delta to their derived base. The base is never mutated. */
    public CardPool applyTo(String rivalId, CardPool derived) {
        CardPool result = new CardPool();
        for (Map.Entry<PaperCard, Integer> e : derived) {
            result.add(e.getKey(), e.getValue());
        }
        for (Map.Entry<PaperCard, Integer> e : lostBy(rivalId)) {
            int take = Math.min(e.getValue(), result.count(e.getKey()));
            if (take > 0) {
                result.remove(e.getKey(), take);
            }
        }
        for (Map.Entry<PaperCard, Integer> e : wonBy(rivalId)) {
            result.add(e.getKey(), e.getValue());
        }
        return result;
    }

    public boolean isEmpty() {
        for (CardPool pool : lostToPlayer.values()) {
            if (pool.countAll() > 0) {
                return false;
            }
        }
        for (CardPool pool : wonFromPlayer.values()) {
            if (pool.countAll() > 0) {
                return false;
            }
        }
        return true;
    }

    // --- persistence -------------------------------------------------------

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("lost", encode(lostToPlayer));
        data.store("won", encode(wonFromPlayer));
        return data;
    }

    public void load(ChronicleSaveData data, ChronicleCollection.CardResolver resolver) {
        lostToPlayer.clear();
        wonFromPlayer.clear();
        decode(data.readString("lost"), resolver, lostToPlayer);
        decode(data.readString("won"), resolver, wonFromPlayer);
    }

    private static String encode(Map<String, CardPool> pools) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, CardPool> rival : pools.entrySet()) {
            for (Map.Entry<PaperCard, Integer> e : rival.getValue()) {
                PaperCard card = e.getKey();
                lines.add(rival.getKey() + "\t" + e.getValue() + "\t" + card.getName() + "\t"
                        + card.getEdition() + "\t" + card.getArtIndex() + "\t" + card.isFoil());
            }
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static void decode(String block, ChronicleCollection.CardResolver resolver, Map<String, CardPool> into) {
        if (block == null || block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n")) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) {
                System.err.println("Chronicle rival ledger: malformed line: " + line);
                continue;
            }
            PaperCard card = resolver.resolve(f[2], f[3], Integer.parseInt(f[4]), Boolean.parseBoolean(f[5]));
            if (card == null) {
                System.err.println("Chronicle rival ledger: unresolvable card dropped: " + line);
                continue;
            }
            into.computeIfAbsent(f[0], k -> new CardPool()).add(card, Integer.parseInt(f[1]));
        }
    }
}
