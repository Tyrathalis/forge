package forge.ai;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.FlatMonteCarloPicker;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameLogEntryType;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.localinstance.properties.ForgeConstants;
import forge.util.MyRandom;

/**
 * With {@code FLAT_MC_COUNT_BUDGET} the Monte Carlo search is bounded by its counts and never by the clock, so a game
 * is a function of its seed: the same seed and decks played twice give the same game, however busy the machine is.
 */
public class FlatMonteCarloCountBudgetTest extends AITest {
    private static final String PROFILE = "FlatMcCountBudget";

    private static final Object[] RED_GREEN = {
        "Mountain", 12, "Forest", 12, "Llanowar Elves", 4, "Grizzly Bears", 4, "Runeclaw Bear", 4, "Hill Giant", 4,
        "Craw Wurm", 4, "Lightning Bolt", 4, "Shock", 4, "Giant Growth", 4, "Fireball", 4 };
    private static final Object[] BLUE_BLACK = {
        "Island", 12, "Swamp", 12, "Wind Drake", 4, "Gravedigger", 4, "Air Elemental", 4, "Walking Corpse", 4,
        "Counterspell", 4, "Doom Blade", 4, "Unsummon", 4, "Divination", 4, "Opt", 4 };

    private static Deck deck(final String name, final Object[] list) {
        final Deck d = new Deck(name);
        for (int i = 0; i < list.length; i += 2) {
            d.getMain().add((String) list[i], (Integer) list[i + 1]);
        }
        return d;
    }

    /**
     * The shipped profiles, and the Default one with the count budget on. Its time budgets are zero, with which Monte
     * Carlo would never run a rollout if the clock still had a say.
     */
    private static File writeProfiles() throws IOException {
        final File dir = Files.createTempDirectory("flatmc-count-budget").toFile();
        final File[] shipped = new File(ForgeConstants.AI_PROFILE_DIR).listFiles((d, n) -> n.endsWith(".ai"));
        for (final File f : shipped) {
            Files.copy(f.toPath(), new File(dir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        final String base = new String(Files.readAllBytes(new File(ForgeConstants.AI_PROFILE_DIR, "Default.ai").toPath()),
                StandardCharsets.UTF_8);
        Files.write(new File(dir, PROFILE + ".ai").toPath(),
                (base + "\nFLAT_MC_COUNT_BUDGET=true\nFLAT_MC_DECISION_BUDGET_MS=0\nFLAT_MC_TURN_BUDGET_MS=0\n")
                        .getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    /** Plays one game on a thread of its own and returns its winner, its last turn and its log. */
    private static List<String> play(final long seed) throws InterruptedException {
        final List<String> record = new ArrayList<>();
        final Throwable[] failure = new Throwable[1];
        final Thread t = new Thread(null, () -> {
            try {
                MyRandom.setRandom(new Random(seed));
                final List<RegisteredPlayer> players = new ArrayList<>();
                final Deck[] decks = { deck("Red Green", RED_GREEN), deck("Blue Black", BLUE_BLACK) };
                for (int i = 0; i < 2; i++) {
                    final LobbyPlayerAi lobby = new LobbyPlayerAi("P" + i, null);
                    lobby.setAiProfile(PROFILE);
                    final RegisteredPlayer rp = new RegisteredPlayer(decks[i]);
                    rp.setPlayer(lobby);
                    players.add(rp);
                }
                final GameRules rules = new GameRules(GameType.Constructed);
                rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
                final Match match = new Match(rules, players, "flatmc-count-budget");
                final Game game = match.createGame();
                game.setNoGUIUser();
                game.AI_FLAT_MONTE_CARLO = true;
                game.AI_FLAT_MONTE_CARLO_MAX = true; // the desktop strength: the attack search and the opponent's turn too
                match.startGame(game);
                record.add("winner=" + (game.getOutcome() == null || game.getOutcome().isDraw() ? "draw"
                        : game.getOutcome().getWinningLobbyPlayer().getName()));
                record.add("turns=" + game.getPhaseHandler().getTurn());
                for (final Player p : game.getRegisteredPlayers()) {
                    record.add(p.getName() + " life=" + p.getLife());
                }
                for (final GameLogEntry entry : game.getGameLog().getAllEntries()) {
                    if (entry.type() != GameLogEntryType.GAME_OUTCOME) { // it has the duration of the game in it
                        record.add(entry.type() + ": " + entry.message());
                    }
                }
            } catch (final Throwable e) {
                failure[0] = e;
            }
        }, "flatmc-count-budget-game", 64L << 20);
        t.start();
        t.join();
        AssertJUnit.assertNull("the game failed: " + failure[0], failure[0]);
        return record;
    }

    @Test(timeOut = 10L * 60_000)
    public void sameSeedGivesTheSameGame() throws Exception {
        final File profiles = writeProfiles();
        AiProfileUtil.loadAllProfiles(profiles.getPath());
        try {
            final int before = FlatMonteCarloPicker.getTotalRollouts();
            final List<String> first = play(20261117L);
            final int rollouts = FlatMonteCarloPicker.getTotalRollouts() - before;
            final List<String> second = play(20261117L);
            AssertJUnit.assertTrue("no rollouts happened", rollouts > 0);
            AssertJUnit.assertEquals("rollouts of the second game", rollouts,
                    FlatMonteCarloPicker.getTotalRollouts() - before - rollouts);
            AssertJUnit.assertEquals(first.subList(0, 4), second.subList(0, 4));
            AssertJUnit.assertEquals(first, second);
        } finally {
            AiProfileUtil.loadAllProfiles(ForgeConstants.AI_PROFILE_DIR);
        }
    }
}
