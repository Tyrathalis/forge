package forge.web.deck;

import forge.deck.DeckFormat;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.util.Localizer;

/** What a deck is checked against: a format, plus an optional card pool for Constructed. Unrestricted checks only the limit of four copies. */
public record Check(GameType format, GameFormat pool, boolean unrestricted) {
    public static Check of(final GameType format, final GameFormat pool) {
        return new Check(format, pool, false);
    }

    static Check none() {
        return new Check(GameType.Constructed, null, true);
    }

    DeckFormat deckFormat() {
        return unrestricted ? DeckFormat.Constructed : format.getDeckFormat();
    }

    String label() {
        if (unrestricted) {
            return Localizer.getInstance().getMessage("lblWebEditorNoRestriction");
        }
        return pool == null ? format.toString() : format + " · " + pool.getName();
    }
}
