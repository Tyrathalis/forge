package forge.web;

import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.web.WebSession.InCampaign;
import forge.web.WebSession.Playing;
import forge.web.WebSession.Stage;
import forge.web.deck.DeckSession;
import forge.web.limited.DraftOwner;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import forge.web.protocol.BrowserChannel;

import java.util.Map;
import java.util.function.Consumer;

/**
 * One session's campaign modes. They live in forge.web.campaign so that a build can leave them out. The session creates
 * them in its constructor. Without them it uses {@link #NONE}, which handles no command and shows nothing.
 */
public interface Campaigns {
    Campaigns NONE = new Campaigns() { };

    interface Host {
        boolean hosting();

        Stage stage();

        boolean move(Stage from, Stage next);

        void tell(Record message);

        Record hello();

        BrowserChannel browser();

        DeckSession decks();

        WebGuiBase ui();

        String playerName();

        int avatarIndex();

        LocalGame local();

        /**
         * Starts a match that a campaign builds. Leaving the match returns to back. start builds the match on the GUI it is played
         * through, and throws if it cannot. gameOver receives each game's end with the host's view of it. failed undoes the preparation.
         */
        void startMatch(InCampaign back, Consumer<WebGuiGame> start, Runnable failed, Consumer<GameView> gameOver);

        /** Opens a draft against the computer whose packs are already dealt. The draft is played for owner. */
        void startDraft(BoosterDraft draft, DraftOwner owner);
    }

    default boolean handles(final Record command) {
        return false;
    }

    default void run(final BrowserChannel channel, final Record command) {
    }

    /** A deck command. The campaigns take it when it is about their own cards or was made on their pages. Returns whether they took it. */
    default boolean deckCommand(final BrowserChannel channel, final Record command) {
        return false;
    }

    /** The browser reconnected at stage now, so what a campaign shows there is sent to it again. */
    default void reconnected(final BrowserChannel channel, final Stage now) {
    }

    /** The save that each mode would resume, keyed by mode. The start page offers these. */
    default Map<String, String> resume() {
        return Map.of();
    }

    /** The session took the host's seat. Only the host's seat can open a campaign. */
    default void becameHost() {
    }

    /** The player left the match that was started from back. */
    default void left(final Playing from, final InCampaign back) {
    }

    /** The campaign stage is shown again, so its page is sent. */
    default void show() {
    }
}
