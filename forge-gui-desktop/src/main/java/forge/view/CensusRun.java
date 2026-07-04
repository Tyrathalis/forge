package forge.view;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import forge.ai.anvil.Census;
import forge.ai.anvil.CensusLobbyPlayer;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.model.FModel;
import forge.util.MyRandom;

/**
 * Instrumented callback census (Anvil M0): heuristic-vs-heuristic games with
 * CensusPlayerController logging every PlayerController callback to JSONL.
 * Answers (a) empirical callback frequency across the 109-method decision
 * surface and (b) the exact callback order on the AI cast path — the two
 * ground-truth checks the override plan and bridge protocol cite.
 *
 * Syntax: forge census -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl>
 */
public final class CensusRun {
    private static final int DRAW_CLOCK_S = 300;
    private static final int GAME_HARD_CAP_S = 360;

    private CensusRun() {
    }

    public static void run(String[] args) {
        FModel.initialize(null, null);

        Map<String, List<String>> params = parseParams(args);
        if (params == null || !params.containsKey("d") || params.get("d").size() != 2) {
            System.out.println("Syntax: forge census -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl>");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        int nGames = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 10;
        long baseSeed = params.containsKey("s") ? Long.parseLong(params.get("s").get(0)) : 20260703L;
        String outPath = params.containsKey("o") ? params.get("o").get(0) : "census.jsonl";

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(java.util.EnumSet.of(type));

        List<Deck> decks = new ArrayList<>();
        for (String deckName : params.get("d")) {
            Deck d = SimulateMatch.deckFromCommandLineParameter(deckName, type);
            if (d == null) {
                System.out.println("Could not load deck: " + deckName);
                return;
            }
            decks.add(d);
        }

        System.out.printf("Callback census: %d games, %s, baseSeed=%d -> %s%n", nGames, type, baseSeed, outPath);

        Map<String, Integer> tally = new TreeMap<>();
        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "census-watchdog");
            t.setDaemon(true);
            return t;
        });
        try {
            Census.open(outPath);
            for (int i = 0; i < nGames; i++) {
                long seed = baseSeed + i;
                MyRandom.setRandom(new Random(seed));

                List<RegisteredPlayer> pp = new ArrayList<>();
                for (int j = 0; j < decks.size(); j++) {
                    Deck d = decks.get(j);
                    RegisteredPlayer rp = type.equals(GameType.Commander)
                            ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
                    rp.setPlayer(new CensusLobbyPlayer("Census(" + (j + 1) + ")-" + d.getName()));
                    pp.add(rp);
                }

                Match mc = new Match(rules, pp, "Census");
                Game game = mc.createGame();
                Census.startGame(i, seed);
                ScheduledFuture<?> drawClock = watchdogs.schedule(
                        () -> game.setGameOver(GameEndReason.Draw), DRAW_CLOCK_S, TimeUnit.SECONDS);
                String status;
                try {
                    TimeLimitedCodeBlock.runWithTimeout(() -> mc.startGame(game), GAME_HARD_CAP_S, TimeUnit.SECONDS);
                    status = game.getOutcome() == null ? "no_outcome"
                            : game.getOutcome().isDraw() ? "draw" : "won";
                } catch (Exception | StackOverflowError e) {
                    game.setGameOver(GameEndReason.Draw);
                    status = "crash_or_hang:" + e.getClass().getSimpleName();
                } finally {
                    drawClock.cancel(false);
                }
                String winner = game.getOutcome() != null && !game.getOutcome().isDraw()
                        ? game.getOutcome().getWinningLobbyPlayer().getName() : null;
                int turns = game.getOutcome() != null ? game.getOutcome().getLastTurnNumber() : -1;
                Census.endGame(winner, turns);
                tally.merge(status, 1, Integer::sum);
                System.out.printf("game %d/%d seed=%d -> %s (%d turns)%n", i + 1, nGames, seed, status, turns);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Census.close();
            watchdogs.shutdownNow();
        }

        System.out.println("=== census tally ===");
        tally.forEach((k, v) -> System.out.printf("%-24s %d%n", k, v));
        System.out.flush();
    }

    private static Map<String, List<String>> parseParams(String[] args) {
        Map<String, List<String>> params = new HashMap<>();
        List<String> current = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-") && a.length() > 1 && !Character.isDigit(a.charAt(1))) {
                current = new ArrayList<>();
                params.put(a.substring(1), current);
            } else if (current != null) {
                current.add(a);
            } else {
                return null;
            }
        }
        return params;
    }
}
