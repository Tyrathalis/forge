package forge.view;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import forge.ai.anvil.AnvilBridge;
import forge.ai.anvil.AnvilLobbyPlayer;
import forge.ai.anvil.Census;
import forge.ai.anvil.LocalRandomBridge;
import forge.ai.anvil.Obs;
import forge.ai.anvil.PlayerControllerAnvil;
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
 * Anvil worker (M0 batch-harness chunk contract): plays a chunk of globally
 * indexed games, appends one JSONL record per completed game (the progress
 * record resume scans), checks a stop-file between games (graceful stop:
 * finish current game, flush, exit 0), and exits when the chunk is done
 * (recycling = chunk boundary). Seeds: SplitMix64(seed_base ^ index), same
 * function as the Python orchestrator (anvil/bridge/harness/seeds.py).
 *
 * Syntax: forge anvil -d <deck1> <deck2> [-f <format>] [-b local-random|grpc:host:port]
 *   [-tags <csv>] [-census <out.jsonl>] [-obs <out.zst>]
 *   chunk mode:  -range <start> <count> -seedbase <long> [-results <games.jsonl>] [-stopfile <path>]
 *   legacy mode: [-n <games>] [-s <baseSeed>]
 */
public final class AnvilRun {
    private static final int DRAW_CLOCK_S = 300;
    private static final int GAME_HARD_CAP_S = 360;

    private static final Set<String> DEFAULT_TAGS = new HashSet<>(Arrays.asList(
            PlayerControllerAnvil.TAG_PRIORITY, PlayerControllerAnvil.TAG_MULLIGAN,
            PlayerControllerAnvil.TAG_TUCK, PlayerControllerAnvil.TAG_TRIGGER,
            PlayerControllerAnvil.TAG_BINARY, PlayerControllerAnvil.TAG_NUMBER));

    private AnvilRun() {
    }

    /** Same constants as Python-side seeds.py; the pair must stay in lockstep. */
    static long splitmix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public static void run(String[] args) {
        FModel.initialize(null, null);

        Map<String, List<String>> params = parseParams(args);
        if (params == null || !params.containsKey("d") || params.get("d").size() != 2) {
            System.out.println("Syntax: forge anvil -d <deck1> <deck2> [-f <format>] "
                    + "[-b local-random|grpc:host:port] [-tags <csv>] [-census <out.jsonl>] [-obs <out.zst>] "
                    + "[-range <start> <count> -seedbase <long> [-results <jsonl>] [-stopfile <path>]] "
                    + "[-n <games>] [-s <baseSeed>]");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        String bridgeMode = params.containsKey("b") ? params.get("b").get(0) : "local-random";
        Set<String> tags = params.containsKey("tags")
                ? new HashSet<>(Arrays.asList(params.get("tags").get(0).split(","))) : DEFAULT_TAGS;

        int rangeStart = 0;
        int nGames;
        Long seedBase = params.containsKey("seedbase")
                ? Long.parseLong(params.get("seedbase").get(0)) : null;
        long legacyBaseSeed = params.containsKey("s")
                ? Long.parseLong(params.get("s").get(0)) : 20260704L;
        if (params.containsKey("range")) {
            rangeStart = Integer.parseInt(params.get("range").get(0));
            nGames = Integer.parseInt(params.get("range").get(1));
        } else {
            nGames = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 10;
        }
        File stopFile = params.containsKey("stopfile") ? new File(params.get("stopfile").get(0)) : null;

        final AnvilBridge bridge;
        if ("local-random".equals(bridgeMode)) {
            bridge = new LocalRandomBridge();
        } else if (bridgeMode.startsWith("grpc:")) {
            String[] hp = bridgeMode.substring(5).split(":");
            forge.anvil.GrpcBridge grpc = new forge.anvil.GrpcBridge(
                    hp[0], Integer.parseInt(hp[1]), "anvil-worker-r" + rangeStart, "");
            if (!grpc.serverBridgedTags().isEmpty()) {
                tags = grpc.serverBridgedTags(); // server-driven coverage
            }
            bridge = grpc;
        } else {
            System.out.println("Unknown bridge mode: " + bridgeMode);
            return;
        }

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

        System.out.printf("Anvil worker: games [%d,%d), %s, seedbase=%s, bridge=%s, tags=%s%n",
                rangeStart, rangeStart + nGames, type,
                seedBase != null ? seedBase : ("legacy:" + legacyBaseSeed), bridgeMode, tags);

        Map<String, Integer> tally = new TreeMap<>();
        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "anvil-watchdog");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.currentTimeMillis();
        PrintWriter results = null;
        boolean stopped = false;
        try {
            if (params.containsKey("results")) {
                try {
                    results = new PrintWriter(new FileWriter(params.get("results").get(0), true));
                } catch (java.io.IOException e) {
                    // The results file is the harness's progress record — failing to
                    // open it must be fatal and loud, never swallowed (a worker that
                    // plays games nobody can account for is worse than one that dies).
                    System.err.println("FATAL: cannot open results file: " + e);
                    System.exit(2);
                }
            }
            if (params.containsKey("census")) {
                Census.open(params.get("census").get(0));
            }
            if (params.containsKey("obs")) {
                try {
                    Obs.open(params.get("obs").get(0));
                } catch (java.io.IOException e) {
                    // The observation log is the corpus artifact — same rule as
                    // the results file: fail loud, never play unaccounted games.
                    System.err.println("FATAL: cannot open obs file: " + e);
                    System.exit(2);
                }
            }
            for (int g = 0; g < nGames; g++) {
                if (stopFile != null && stopFile.exists()) {
                    stopped = true;
                    System.out.println("stop-file present; exiting gracefully after "
                            + g + "/" + nGames + " games");
                    break;
                }
                int idx = rangeStart + g;
                long seed = seedBase != null
                        ? splitmix64(seedBase + idx * 0x9E3779B97F4A7C15L) : legacyBaseSeed + idx;
                MyRandom.setRandom(new Random(seed));

                List<RegisteredPlayer> pp = new ArrayList<>();
                for (int j = 0; j < decks.size(); j++) {
                    Deck d = decks.get(j);
                    RegisteredPlayer rp = type.equals(GameType.Commander)
                            ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
                    rp.setPlayer(new AnvilLobbyPlayer("Anvil(" + (j + 1) + ")-" + d.getName(), bridge, tags));
                    pp.add(rp);
                }

                Match mc = new Match(rules, pp, "Anvil");
                Game game = mc.createGame();
                Census.startGame(idx, seed);
                Obs.startGame(idx, seed, game, type.toString());
                long gameT0 = System.currentTimeMillis();
                bridge.gameStart("g" + idx, seed);
                final boolean[] drawClockHit = {false};
                ScheduledFuture<?> drawClock = watchdogs.schedule(() -> {
                    drawClockHit[0] = true;
                    game.setGameOver(GameEndReason.Draw);
                }, DRAW_CLOCK_S, TimeUnit.SECONDS);
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
                long wallMs = System.currentTimeMillis() - gameT0;
                String winner = game.getOutcome() != null && !game.getOutcome().isDraw()
                        ? game.getOutcome().getWinningLobbyPlayer().getName() : null;
                int turns = game.getOutcome() != null ? game.getOutcome().getLastTurnNumber() : -1;
                Census.endGame(winner, turns);
                int winnerIdx = -1;
                if (winner != null) {
                    for (int wi = 0; wi < game.getPlayers().size(); wi++) {
                        if (game.getPlayers().get(wi).getName().equals(winner)) {
                            winnerIdx = wi;
                            break;
                        }
                    }
                }
                Obs.endGame(status, winnerIdx, turns, wallMs, drawClockHit[0]);
                bridge.gameEnd("g" + idx, winner, turns, wallMs);
                tally.merge(status, 1, Integer::sum);
                if (results != null) {
                    results.println("{\"i\":" + idx + ",\"seed\":" + seed
                            + ",\"status\":\"" + status + "\""
                            + ",\"winner\":" + (winner == null ? "null" : "\"" + winner.replace("\"", "'") + "\"")
                            + ",\"turns\":" + turns + ",\"ms\":" + wallMs
                            + ",\"draw_clock\":" + drawClockHit[0] + "}");
                    results.flush();
                }
                System.out.printf("game %d seed=%d -> %s (%d turns)%n", idx, seed, status, turns);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (results != null) {
                results.close();
            }
            Census.close();
            Obs.close();
            bridge.close();
            watchdogs.shutdownNow();
        }

        long wallS = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("=== anvil tally ===" + (stopped ? " (stopped)" : ""));
        tally.forEach((k, v) -> System.out.printf("%-24s %d%n", k, v));
        System.out.printf("wall=%ds%n", wallS);
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
