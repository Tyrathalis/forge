package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import forge.StaticData;
import forge.card.CardEdition;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.generation.DeckGenPool;
import forge.deck.generation.DeckGenerator2Color;
import forge.deck.generation.DeckGeneratorBase;
import forge.deck.generation.DeckGeneratorMonoColor;
import forge.item.PaperCard;
import forge.util.MyRandom;

/**
 * The MVP {@link ChronicleDeckSource}: forge-core's curve-and-colour generator,
 * fenced so it can only ever build a deck someone could actually have brought.
 *
 * Three things the raw generator does not do for us:
 *
 * 1. <b>It is count-blind.</b> DeckGenPool is keyed by card NAME, so a pool with
 *    one Black Lotus in it will happily yield four. Everything the generator
 *    returns is clamped back to what the collection actually holds.
 * 2. <b>It picks colours at random</b> when not told. We pick the two the
 *    collection is deepest in, which is both what a real kid would do and a
 *    much better deck out of a shallow pool.
 * 3. <b>Basics are not in the pool.</b> Forge's era sheets put no basic lands in
 *    1993-94 boosters (a documented deviation, engine-wide), so a rival who only
 *    buys packs owns literally zero lands. Basics are therefore supplied
 *    outside the collection — correct anyway, since basics were free at the
 *    kitchen table — and forced to a period edition so nobody shuffles up a
 *    Zendikar Island in 1994.
 *
 * Determinism comes free: every generator in forge-core routes its randomness
 * through MyRandom, the same way BoosterGenerator does, so seeding the thread's
 * MyRandom makes the whole build reproducible. Same pool and seed, same deck.
 */
public final class ChronicleDeckBuilder implements ChronicleDeckSource {

    /** Target deck size. Period kitchen-table decks were 60-ish; QuestDeck's floor is 40. */
    public static final int DECK_SIZE = 60;
    /** Fallback basic-land edition if the deck somehow names no in-window set. */
    private static final String FALLBACK_BASIC_EDITION = "LEA";

    private final String basicLandEdition;

    /** @param basicLandEdition edition basics are printed from; null = derive from the deck. */
    public ChronicleDeckBuilder(String basicLandEdition) {
        this.basicLandEdition = basicLandEdition;
    }

    public ChronicleDeckBuilder() {
        this(null);
    }

    @Override
    public Deck buildFrom(CardPool owned, long seed, String name) {
        Random previous = MyRandom.getRandom();
        MyRandom.setRandom(new Random(seed));
        try {
            return build(owned, name);
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    private Deck build(CardPool owned, String name) {
        Deck deck = new Deck(name);
        CardPool main = deck.getOrCreate(DeckSection.Main);

        List<PaperCard> nonBasics = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : owned) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                nonBasics.add(e.getKey());
            }
        }
        String basicEdition = basicLandEdition != null ? basicLandEdition : deriveBasicEdition(nonBasics);
        if (nonBasics.isEmpty()) {
            //nothing but lands: a deck of pure basics is still a legal 60 cards
            addBasics(main, DECK_SIZE, new byte[] { MagicColor.GREEN }, basicEdition);
            return deck;
        }

        byte[] chosen = deepestColors(owned);
        CardPool generated = generate(new DeckGenPool(nonBasics), chosen);
        if (generated != null) {
            clampToOwned(generated, owned, main);
        }
        addBasics(main, DECK_SIZE - main.countAll(), chosen, basicEdition);
        return deck;
    }

    private CardPool generate(DeckGenPool pool, byte[] colors) {
        try {
            DeckGeneratorBase gen = colors.length < 2
                    ? new DeckGeneratorMonoColor(pool, DeckFormat.QuestDeck, MagicColor.toLongString(colors[0]))
                    : new DeckGenerator2Color(pool, DeckFormat.QuestDeck,
                            MagicColor.toLongString(colors[0]), MagicColor.toLongString(colors[1]));
            gen.setUseArtifacts(true);
            return gen.getDeck(DECK_SIZE, true);
        } catch (RuntimeException e) {
            //a pool too thin for the generator's curve assumptions — the caller
            //still gets a legal deck, it is just mostly lands. Better a weak
            //kitchen-table deck than a rival who cannot be played.
            System.err.println("Chronicle: deck generation fell back to basics — " + e);
            return null;
        }
    }

    /**
     * The generator may name more copies than the collection holds, because
     * DeckGenPool dedupes by name. Take the printings actually owned, in owned
     * order, up to the owned count.
     */
    private void clampToOwned(CardPool generated, CardPool owned, CardPool target) {
        Map<String, Integer> ownedByName = new HashMap<>();
        Map<String, List<PaperCard>> printingsByName = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : owned) {
            PaperCard card = e.getKey();
            ownedByName.merge(card.getName(), e.getValue(), Integer::sum);
            printingsByName.computeIfAbsent(card.getName(), k -> new ArrayList<>()).add(card);
        }
        for (Map.Entry<PaperCard, Integer> e : generated) {
            PaperCard wanted = e.getKey();
            if (wanted.getRules().getType().isBasicLand()) {
                continue; //basics are supplied separately, in a period edition
            }
            int allowed = Math.min(e.getValue(), ownedByName.getOrDefault(wanted.getName(), 0));
            List<PaperCard> printings = printingsByName.get(wanted.getName());
            if (allowed <= 0 || printings == null) {
                continue;
            }
            //spend the owned copies across the printings actually held, so the
            //deck names cards this collection can really produce
            int remaining = allowed;
            for (PaperCard printing : printings) {
                if (remaining <= 0) {
                    break;
                }
                int take = Math.min(remaining, owned.count(printing));
                if (take > 0) {
                    target.add(printing, take);
                    remaining -= take;
                }
            }
        }
    }

    /** The one or two colours this collection is deepest in — a real kid builds what they opened. */
    static byte[] deepestColors(CardPool owned) {
        int[] weight = new int[MagicColor.WUBRG.length];
        for (Map.Entry<PaperCard, Integer> e : owned) {
            byte color = e.getKey().getRules().getColorIdentity().getColor();
            for (int i = 0; i < MagicColor.WUBRG.length; i++) {
                if ((color & MagicColor.WUBRG[i]) != 0) {
                    weight[i] += e.getValue();
                }
            }
        }
        int best = 0, second = -1;
        for (int i = 1; i < weight.length; i++) {
            if (weight[i] > weight[best]) {
                best = i;
            }
        }
        for (int i = 0; i < weight.length; i++) {
            if (i != best && (second < 0 || weight[i] > weight[second])) {
                second = i;
            }
        }
        if (second < 0 || weight[second] == 0) {
            return new byte[] { MagicColor.WUBRG[best] };
        }
        return new byte[] { MagicColor.WUBRG[best], MagicColor.WUBRG[second] };
    }

    /** Basics in the deck's own era, split across its colours. */
    private void addBasics(CardPool target, int count, byte[] colors, String edition) {
        if (count <= 0 || colors.length == 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            byte color = colors[i % colors.length];
            PaperCard basic = basicFor(color, edition);
            if (basic != null) {
                target.add(basic, 1);
            }
        }
    }

    private static PaperCard basicFor(byte color, String edition) {
        String landName = MagicColor.Constant.BASIC_LANDS.get(indexOfColor(color));
        PaperCard card = StaticData.instance().getCommonCards().getCard(landName, edition);
        return card != null ? card : StaticData.instance().getCommonCards().getCard(landName);
    }

    private static int indexOfColor(byte color) {
        for (int i = 0; i < MagicColor.WUBRG.length; i++) {
            if (MagicColor.WUBRG[i] == color) {
                return i;
            }
        }
        return 0;
    }

    /** Most-represented edition among the deck's cards — keeps basics in period without a lookup table. */
    private static String deriveBasicEdition(List<PaperCard> cards) {
        Map<String, Integer> counts = new HashMap<>();
        for (PaperCard card : cards) {
            counts.merge(card.getEdition(), 1, Integer::sum);
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            CardEdition edition = StaticData.instance().getEditions().get(e.getKey());
            //only core/expansion sets actually print basics; skip anything that doesn't
            if (edition == null) {
                continue;
            }
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best != null ? best : FALLBACK_BASIC_EDITION;
    }
}
