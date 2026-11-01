package forge.web;

import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameState;
import forge.gui.GuiBase;
import forge.web.match.LocalGame;
import forge.web.match.WebGuiGame;
import forge.web.protocol.BrowserChannel;
import org.testng.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** A match between the web seat, played by a test's browser, and the computer. It is shut down when the test ends. */
final class TestMatch {
    private TestMatch() {
    }

    interface Body<B> {
        void run(LocalGame local, WebGuiGame gui, B browser) throws Exception;
    }

    static <B extends BrowserChannel> void play(final Deck web, final Deck computer, final Function<WebGuiGame, B> browserFor,
            final Body<B> body) throws Exception {
        final LocalGame local = new LocalGame();
        final WebGuiGame gui = new WebGuiGame();
        final B browser = browserFor.apply(gui);
        try {
            gui.attach(browser);
            GuiBase.getInterface().invokeInEdtAndWait(() -> local.startMatch("Web Player", web, "AI", computer, gui));
            body.run(local, gui, browser);
        } finally {
            GuiBase.getInterface().invokeInEdtAndWait(local::shutdown);
            if (browser instanceof TestBrowser played) {
                played.stop();
            }
        }
    }

    /** Applies the state on the game thread and waits until the cards are placed. */
    static void apply(final GameState state, final Game game) throws InterruptedException {
        final CountDownLatch placed = new CountDownLatch(1);
        game.getAction().invoke(() -> {
            state.applyToGame(game);
            placed.countDown();
        });
        Assert.assertTrue(placed.await(30, TimeUnit.SECONDS), "the game never placed the cards");
    }
}
