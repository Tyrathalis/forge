package forge.gamemodes.chronicle;

import forge.deck.CardPool;
import forge.deck.Deck;

/**
 * The seam between "a pile of cards someone owns" and "a deck they brought to
 * the table". Pool in, deck out, nothing else.
 *
 * It exists narrow on purpose. The MVP implementation is
 * {@link ChronicleDeckBuilder}, a curve-and-colour heuristic over forge-core's
 * DeckGeneratorBase — good enough for a kitchen-table opponent and honest about
 * what it is. The design record's long-run answer is Anvil: sketch layer 3's
 * deck judge, then Tutor's learned scorer, building decks to a requested power
 * level. When that arrives it implements this interface and nothing else in
 * Chronicle changes.
 */
public interface ChronicleDeckSource {

    /**
     * Build a deck from exactly these cards.
     *
     * @param owned  the collection to build from; the result must never use a
     *               card more times than it appears here
     * @param seed   deterministic seed — the same pool and seed must give the
     *               same deck, so a rival's deck is as unrerollable as their
     *               collection
     * @param name   deck name for the match UI
     */
    Deck buildFrom(CardPool owned, long seed, String name);
}
