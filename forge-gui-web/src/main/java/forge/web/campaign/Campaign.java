package forge.web.campaign;

import forge.game.GameView;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.match.PreparedMatch;
import forge.web.deck.DeckSession;
import forge.web.protocol.BrowserChannel;
import forge.web.protocol.CampaignProtocol.*;
import forge.web.protocol.FromBrowser.CatalogueQuery;
import forge.web.protocol.ToBrowser.CataloguePage;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;

/**
 * A campaign mode as a session sees it. There is one per session and mode, over Forge's single model of that mode.
 * Only the host's session opens a campaign. It makes every call on its campaign thread ({@code CampaignSession.run}),
 * so nothing here takes a lock. {@link #current} reads only a preference and the saves folder, so it may be called from any thread.
 */
public interface Campaign {
    /** What a mode's own pages need from the session. */
    interface Host {
        Record error(String text);

        DeckSession decks();

        /** The campaign made a save and opened it, so the session moves to that save. */
        void opened(String save);

        /** Starts a match the campaign built. failed undoes what preparing it did. */
        void startMatch(Supplier<PreparedMatch> prepare, Runnable failed);

        /** Opens a draft whose packs are already dealt, as a quest tournament's are. */
        void startDraft(BoosterDraft draft);
    }

    /** The saved games, as the mode's own message. Reading them is slow. */
    Record saves();

    /** The save played last, or null. */
    String current();

    /** Makes a save the one Forge has open, and returns its name. Returns null when no save has that name or it cannot be read. */
    String open(String save);

    /** Renames a save. Returns why it cannot be renamed, or null when it is done. */
    String rename(String name, String to);

    void delete(String name);

    /** The data an open campaign's pages are drawn from. It is sent whenever it may have changed. */
    List<Record> page();

    /** What the last match gave that has not been shown yet, or null. */
    Reward reward();

    /** Called when the reward has been shown. The campaign then goes on to whatever comes next. */
    void claim(Host host);

    /** A game of the campaign's match ended. It is recorded and rewarded here. hostGame is the host's own view, the only view that knows the match. */
    CampaignResult gameOver(GameView hostGame);

    /** Called when the game's result has been sent. So whatever the mode does next, such as asking about a reward, comes after the result screen. */
    default void resultSent() {
    }

    /** The result of the game just ended, while its match is still open, or null. */
    CampaignResult result();

    /** The match was left. Its result is dropped with it. */
    void left();

    CataloguePage cards(CatalogueQuery q);

    /** The lists whose cards are traded, by size. */
    Trading trading();

    /** Trades picks out of the list of that source. Returns why it cannot, or null when it is done. */
    String trade(String source, List<TradePick> picks);

    CampaignStats stats(String scope);

    /** The mode's preferences. problem is why the last change was refused, if it was. */
    CampaignPrefs prefs(String problem);

    /** Sets a preference and saves. Returns why it cannot, or null when it is set. */
    String setPref(String key, String value);

    void resetPrefs();

    /** A command of the mode's own. save is null on the list of saved games. */
    void handle(BrowserChannel channel, Record command, String save, Host host);

    /** Whether file is a direct child of the saves folder with exactly that name, however its path was written. Save names come from the browser. So a name with a path in it must not reach a file outside the saves to open, rename or delete. */
    static boolean isSaveNamed(final String savesDir, final File file, final String child) {
        try {
            final File canonical = file.getCanonicalFile();
            return new File(savesDir).getCanonicalFile().equals(canonical.getParentFile()) && canonical.getName().equals(child);
        } catch (final IOException e) {
            return false;
        }
    }

    /** The day a save was last written, as the browser shows it (yyyy-mm-dd). */
    static String savedOn(final File file) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).toString();
    }
}
