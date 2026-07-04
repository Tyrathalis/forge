package forge.view;

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
 * Anvil worker launcher (M0): plays games with PlayerControllerAnvil, the
 * bridged tag set answered by an AnvilBridge. Bridge modes: "local-random"
 * (in-process, transport-free control arm); "grpc:<host:port>" arrives with
 * the Python server.
 *
 * Syntax: forge anvil -d <deck1> <deck2> [-f <format>] [-n <games>] [-s <baseSeed>]
 *                     [-b local-random] [-tags <csv>] [-census <out.jsonl>]
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

    public static void run(String[] args) {
        FModel.initialize(null, null);

        Map<String, List<String>> params = parseParams(args);
        if (params == null || !params.containsKey("d") || params.get("d").size() != 2) {
            System.out.println("Syntax: forge anvil -d <deck1> <deck2> [-f <format>] [-n <games>] "
                    + "[-s <baseSeed>] [-b local-random] [-tags <csv>] [-census <out.jsonl>]");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        int nGames = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 10;
        long baseSeed = params.containsKey("s") ? Long.parseLong(params.get("s").get(0)) : 20260704L;
        String bridgeMode = params.containsKey("b") ? params.get("b").get(0) : "local-random";
        Set<String> tags = params.containsKey("tags")
                ? new HashSet<>(Arrays.asList(params.get("tags").get(0).split(","))) : DEFAULT_TAGS;

        final AnvilBridge bridge;
        if ("local-random".equals(bridgeMode)) {
            bridge = new LocalRandomBridge();
        } else if (bridgeMode.startsWith("grpc:")) {
            String[] hp = bridgeMode.substring(5).split(":");
            forge.anvil.GrpcBridge grpc = new forge.anvil.GrpcBridge(
                    hp[0], Integer.parseInt(hp[1]), "anvil-worker", "56c96c6c40");
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

        System.out.printf("Anvil run: %d games, %s, baseSeed=%d, bridge=%s, tags=%s%n",
                nGames, type, baseSeed, bridgeMode, tags);

        Map<String, Integer> tally = new TreeMap<>();
        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "anvil-watchdog");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.currentTimeMillis();
        try {
            if (params.containsKey("census")) {
                Census.open(params.get("census").get(0));
            }
            for (int i = 0; i < nGames; i++) {
                long seed = baseSeed + i;
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
                Census.startGame(i, seed);
                long gameT0 = System.currentTimeMillis();
                bridge.gameStart("g" + i, seed);
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
                bridge.gameEnd("g" + i, winner, turns, System.currentTimeMillis() - gameT0);
                tally.merge(status, 1, Integer::sum);
                System.out.printf("game %d/%d seed=%d -> %s (%d turns)%n", i + 1, nGames, seed, status, turns);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            Census.close();
            bridge.close();
            watchdogs.shutdownNow();
        }

        long wallS = (System.currentTimeMillis() - t0) / 1000;
        System.out.println("=== anvil tally ===");
        tally.forEach((k, v) -> System.out.printf("%-24s %d%n", k, v));
        System.out.printf("wall=%ds (%.1f s/game)%n", wallS, nGames > 0 ? (double) wallS / nGames : 0);
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
