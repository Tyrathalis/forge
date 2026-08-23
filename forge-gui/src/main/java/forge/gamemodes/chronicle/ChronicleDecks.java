package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The player's decks. Reference-only by decision (ADR-0071): a deck NAMES owned
 * copies, it does not take them out of the binder, and two decks may name the
 * same card. That is how a kitchen-table player actually behaves — one Lightning
 * Bolt moves between decks between games — and it means building a deck can
 * never make the collection look like it shrank.
 *
 * The consequence to handle is the other direction: selling a card to the
 * buylist can leave a deck naming copies that are gone. Rather than silently
 * editing the player's deck or blocking the sale, a deck is checked against the
 * collection when it matters ({@link #shortfall}) and reported as unplayable
 * until it is fixed. The deck is the player's; we do not quietly rewrite it.
 *
 * Persistence stores identity lines and re-resolves through the same
 * CardResolver the collection uses, so decks survive card-DB updates.
 */
public final class ChronicleDecks {

    private final Map<String, Deck> decks = new LinkedHashMap<>();

    public void put(Deck deck) {
        decks.put(deck.getName(), deck);
    }

    public Deck get(String name) {
        return decks.get(name);
    }

    public boolean remove(String name) {
        return decks.remove(name) != null;
    }

    public boolean has(String name) {
        return decks.containsKey(name);
    }

    public List<Deck> all() {
        return Collections.unmodifiableList(new ArrayList<>(decks.values()));
    }

    public int size() {
        return decks.size();
    }

    /**
     * Cards this deck names more copies of than the collection now holds.
     * Empty = playable. Basic lands are exempt: they are supplied outside the
     * collection (Forge's era sheets put none in 1993-94 boosters), so a deck
     * naming twenty Forests is not short of anything.
     */
    public static Map<PaperCard, Integer> shortfall(Deck deck, ChronicleCollection collection) {
        Map<PaperCard, Integer> missing = new HashMap<>();
        Map<String, Integer> namedByName = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : deck.getOrCreate(DeckSection.Main)) {
            if (e.getKey().getRules().getType().isBasicLand()) {
                continue;
            }
            namedByName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        Map<String, Integer> ownedByName = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : collection.entries()) {
            ownedByName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        for (Map.Entry<PaperCard, Integer> e : deck.getOrCreate(DeckSection.Main)) {
            PaperCard card = e.getKey();
            if (card.getRules().getType().isBasicLand()) {
                continue;
            }
            Integer named = namedByName.remove(card.getName());
            if (named == null) {
                continue; //already accounted on an earlier printing of the same name
            }
            int owned = ownedByName.getOrDefault(card.getName(), 0);
            if (named > owned) {
                missing.put(card, named - owned);
            }
        }
        return missing;
    }

    public static boolean isPlayable(Deck deck, ChronicleCollection collection) {
        return shortfall(deck, collection).isEmpty();
    }

    // --- persistence -------------------------------------------------------

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        List<String> names = new ArrayList<>();
        int index = 0;
        for (Deck deck : decks.values()) {
            names.add(deck.getName());
            data.store("deck" + index, encode(deck));
            index++;
        }
        data.store("names", String.join("\n", names));
        return data;
    }

    public void load(ChronicleSaveData data, ChronicleCollection.CardResolver resolver) {
        decks.clear();
        String namesBlock = data.readString("names");
        if (namesBlock == null || namesBlock.isEmpty()) {
            return;
        }
        String[] names = namesBlock.split("\n");
        for (int i = 0; i < names.length; i++) {
            String block = data.readString("deck" + i);
            if (block == null) {
                continue;
            }
            Deck deck = decode(names[i], block, resolver);
            if (deck != null) {
                decks.put(deck.getName(), deck);
            }
        }
    }

    private static String encode(Deck deck) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : deck.getOrCreate(DeckSection.Main)) {
            PaperCard card = e.getKey();
            lines.add(e.getValue() + "|" + card.getName() + "\t" + card.getEdition()
                    + "\t" + card.getArtIndex() + "\t" + card.isFoil());
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static Deck decode(String name, String block, ChronicleCollection.CardResolver resolver) {
        Deck deck = new Deck(name);
        CardPool main = deck.getOrCreate(DeckSection.Main);
        if (block.isEmpty()) {
            return deck;
        }
        for (String line : block.split("\n")) {
            int sep = line.indexOf('|');
            if (sep < 0) {
                System.err.println("Chronicle decks: malformed deck line: " + line);
                continue;
            }
            int amount = Integer.parseInt(line.substring(0, sep));
            String[] f = line.substring(sep + 1).split("\t", -1);
            if (f.length < 4) {
                System.err.println("Chronicle decks: malformed identity line: " + line);
                continue;
            }
            PaperCard card = resolver.resolve(f[0], f[1], Integer.parseInt(f[2]), Boolean.parseBoolean(f[3]));
            if (card == null) {
                System.err.println("Chronicle decks: unresolvable card dropped from " + name + ": " + line);
                continue;
            }
            main.add(card, amount);
        }
        return deck;
    }
}
