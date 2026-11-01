package forge.web.limited;

import forge.deck.DeckGroup;

/** The owner of a draft against the computer that the Limited pages did not start, such as a quest tournament. */
public interface DraftOwner {
    /** The single name that the drafted decks are saved under. */
    String deckName();

    void saved(DeckGroup decks);

    void discarded();
}
