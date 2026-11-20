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
 *
 * Payment certification runs on AnvilRun -replay (ADR-0117); flatJson
 * below is the job-line reader the two share.
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
            System.out.println("Syntax: forge census -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl> [-paytelemetry]");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        int nGames = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 10;
        long baseSeed = params.containsKey("s") ? Long.parseLong(params.get("s").get(0)) : 20260703L;
        String outPath = params.containsKey("o") ? params.get("o").get(0) : "census.jsonl";
        // M9 D3 §3c: payment-surface telemetry-only mode (enumeration + flag
        // kv on every in-scope payManaCost; no bridging — the spec §8
        // pre-training read). Trajectory-perturbing like -obs; runs pin it.
        forge.ai.anvil.PaymentTelemetry.enabled = params.containsKey("paytelemetry");

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
                AnvilGames.noGui(game); // -Danvil.nogui=on: upstream #11780 DummyCardView (ADR-0110)
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

    /** Minimal flat-JSON object parser (string/number/bool values, no
     *  nesting) — the jobs contract is flat and the fork carries no JSON
     *  dependency. Values land as raw strings; the job class converts. */
    static Map<String, String> flatJson(String line) {
        Map<String, String> m = new HashMap<>();
        int n = line.length();
        int i = line.indexOf('{');
        if (i < 0) {
            throw new IllegalArgumentException("bad jobs line: " + line);
        }
        i++;
        while (i < n) {
            while (i < n && (line.charAt(i) == ',' || Character.isWhitespace(line.charAt(i)))) {
                i++;
            }
            if (i >= n || line.charAt(i) == '}') {
                break;
            }
            StringBuilder key = new StringBuilder();
            i = readJsonString(line, i, key);
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            if (i >= n || line.charAt(i) != ':') {
                throw new IllegalArgumentException("bad jobs line: " + line);
            }
            i++;
            while (i < n && Character.isWhitespace(line.charAt(i))) {
                i++;
            }
            StringBuilder val = new StringBuilder();
            if (line.charAt(i) == '"') {
                i = readJsonString(line, i, val);
            } else {
                while (i < n && line.charAt(i) != ',' && line.charAt(i) != '}') {
                    val.append(line.charAt(i++));
                }
            }
            m.put(key.toString(), val.toString().trim());
        }
        return m;
    }

    private static int readJsonString(String s, int i, StringBuilder out) {
        if (s.charAt(i) != '"') {
            throw new IllegalArgumentException("expected string at " + i + ": " + s);
        }
        i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return i;
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                case 'n': out.append('\n'); break;
                case 't': out.append('\t'); break;
                case 'r': out.append('\r'); break;
                case 'u':
                    out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: out.append(e);
                }
            } else {
                out.append(c);
            }
        }
        throw new IllegalArgumentException("unterminated string: " + s);
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
