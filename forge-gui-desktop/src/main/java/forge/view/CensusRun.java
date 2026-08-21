package forge.view;

import java.io.FileWriter;
import java.io.PrintWriter;
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
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.eventbus.Subscribe;

import forge.ai.anvil.Census;
import forge.ai.anvil.CensusLobbyPlayer;
import forge.ai.anvil.PayDirective;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
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
 * Certify mode (M9 rung 3, m9-rung3-draft.md): -certify <jobs.jsonl>
 * -certout <out.jsonl> replays census games (identical seeding + player
 * construction, so trajectories match the mined census game up to the
 * window) with a PayDirective armed per (job, arm, roll) and emits one
 * certify row per game — the paired K-rollout adjudication input.
 */
public final class CensusRun {
    private static final int DRAW_CLOCK_S = 300;
    private static final int GAME_HARD_CAP_S = 360;

    private CensusRun() {
    }

    public static void run(String[] args) {
        FModel.initialize(null, null);

        Map<String, List<String>> params = parseParams(args);
        if (params == null || (!params.containsKey("certify")
                && (!params.containsKey("d") || params.get("d").size() != 2))) {
            System.out.println("Syntax: forge census -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl> [-paytelemetry]");
            System.out.println("        forge census -certify <jobs.jsonl> -certout <out.jsonl> [-f <format>]");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        if (params.containsKey("certify")) {
            runCertify(params, type);
            return;
        }
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

    // ------------------------------------------------------------------
    // Certify mode (M9 rung 3): per-candidate paired-arm replay.
    //
    // For each jobs.jsonl line, for arm in 0..arms, roll in 0..k-1: play
    // ONE full game at the job's absolute seed with census-identical
    // construction (same MyRandom seeding, same CensusLobbyPlayer names —
    // NOT the AnvilRun path, which draws seed-derived AI profiles), the
    // PayDirective armed with pick=arm. Arm 0 = auto is the paired
    // baseline (byte-identical to unarmed play on roll 0). Determinization
    // for roll > 0 happens inside PayDirective AT the matched window (the
    // documented choice: prefix stays census-identical, completions
    // average over unseen library order); the roll seed is mixed like
    // AnvilRun's rollSeed and shared across arms so completions pair.
    // Games stop at the end of turn t+horizon (setGameOver(Draw) — the
    // AnvilRun rollout-watchdog mechanism, via GameEventTurnBegan) or at
    // natural game end, whichever first. A roll-0 miss (no_such_option /
    // drift) emits its row and skips the arm's remaining rolls.
    // ------------------------------------------------------------------

    private static final class CertJob {
        final int job;
        final long seed;
        final String deck1, deck2, p, sa;
        final int t, ord, arms, k, horizon;

        CertJob(Map<String, String> m) {
            job = Integer.parseInt(m.get("job"));
            seed = Long.parseLong(m.get("seed"));
            deck1 = m.get("deck1");
            deck2 = m.get("deck2");
            p = m.get("p");
            sa = m.get("sa");
            t = Integer.parseInt(m.get("t"));
            ord = m.containsKey("ord") ? Integer.parseInt(m.get("ord")) : 0;
            arms = m.containsKey("arms") ? Integer.parseInt(m.get("arms")) : 8;
            k = m.containsKey("k") ? Integer.parseInt(m.get("k")) : 1;
            horizon = m.containsKey("horizon") ? Integer.parseInt(m.get("horizon")) : 2;
        }
    }

    /** Bounded-horizon stop: end-of-turn t+horizon = the first TurnBegan
     *  with a higher number; forced end is a Draw so the results row stays
     *  obviously non-decisive (the AnvilRun rollout convention). */
    private static final class HorizonStop {
        final Game game;
        final int stopTurn;
        volatile boolean stopped = false;

        HorizonStop(Game game, int stopTurn) {
            this.game = game;
            this.stopTurn = stopTurn;
        }

        @Subscribe
        public void onTurnBegan(GameEventTurnBegan ev) {
            if (!game.isGameOver() && ev.turnNumber() > stopTurn) {
                stopped = true;
                game.setGameOver(GameEndReason.Draw);
            }
        }
    }

    private static void runCertify(Map<String, List<String>> params, GameType type) {
        String jobsPath = params.get("certify").get(0);
        String outPath = params.containsKey("certout") ? params.get("certout").get(0) : "certify.jsonl";
        // Provenance parity: drill candidates are mined from -paytelemetry
        // census runs and telemetry enumeration is trajectory-perturbing
        // (the PaymentTelemetry replay note) — certify replays must run
        // under the same flag or the window never lines up. Census stays
        // closed: enumeration still runs, rows are simply not written.
        forge.ai.anvil.PaymentTelemetry.enabled = true;

        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(java.util.EnumSet.of(type));

        List<CertJob> jobs = new ArrayList<>();
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(jobsPath))) {
                if (!line.isBlank()) {
                    jobs.add(new CertJob(flatJson(line)));
                }
            }
        } catch (Exception e) {
            System.err.println("FATAL: cannot read jobs file " + jobsPath + ": " + e);
            return;
        }
        System.out.printf("Payment certify: %d jobs, %s -> %s%n", jobs.size(), type, outPath);

        Map<String, Deck> deckCache = new HashMap<>();
        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "certify-watchdog");
            t.setDaemon(true);
            return t;
        });
        try (PrintWriter out = new PrintWriter(new FileWriter(outPath, true))) {
            for (CertJob job : jobs) {
                Deck[] decks = new Deck[2];
                String[] names = { job.deck1, job.deck2 };
                for (int j = 0; j < 2; j++) {
                    decks[j] = deckCache.computeIfAbsent(names[j],
                            n -> SimulateMatch.deckFromCommandLineParameter(n, type));
                    if (decks[j] == null) {
                        System.err.println("FATAL: could not load deck: " + names[j]);
                        return;
                    }
                }
                for (int arm = 0; arm <= job.arms; arm++) {
                    for (int roll = 0; roll < Math.max(1, job.k); roll++) {
                        boolean fired = certifyGame(job, arm, roll, rules, type, decks, watchdogs, out);
                        if (roll == 0 && !fired) {
                            // no_such_option / replay drift: one row per arm,
                            // never k — the identical prefix cannot fire later.
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            watchdogs.shutdownNow();
        }
        System.out.println("certify done");
        System.out.flush();
    }

    /** Plays one (job, arm, roll) game, writes its certify row; returns the
     *  directive's fired bit (the arm-skip signal). */
    private static boolean certifyGame(CertJob job, int arm, int roll, GameRules rules, GameType type,
            Deck[] decks, ScheduledExecutorService watchdogs, PrintWriter out) {
        // Shared across arms (job+roll only) so completions pair; 0 = the
        // undeterminized true continuation.
        long rollSeed = roll == 0 ? 0L
                : AnvilRun.splitmix64(job.seed
                        ^ (job.job * 0x9E3779B97F4A7C15L) ^ (roll * 0xBF58476D1CE4E5B9L));
        MyRandom.setRandom(new Random(job.seed));

        List<RegisteredPlayer> pp = new ArrayList<>();
        for (int j = 0; j < 2; j++) {
            Deck d = decks[j];
            RegisteredPlayer rp = type.equals(GameType.Commander)
                    ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
            rp.setPlayer(new CensusLobbyPlayer("Census(" + (j + 1) + ")-" + d.getName()));
            pp.add(rp);
        }
        Match mc = new Match(rules, pp, "Census");
        Game game = mc.createGame();
        PayDirective dir = PayDirective.armPayDirective(game, job.p, job.t, job.sa, job.ord, arm, rollSeed);
        HorizonStop stop = new HorizonStop(game, job.t + job.horizon);
        game.subscribeToEvents(stop);

        AtomicBoolean clockFired = new AtomicBoolean(false);
        ScheduledFuture<?> drawClock = watchdogs.schedule(() -> {
            clockFired.set(true);
            game.setGameOver(GameEndReason.Draw);
        }, DRAW_CLOCK_S, TimeUnit.SECONDS);
        boolean crashed = false;
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> mc.startGame(game), GAME_HARD_CAP_S, TimeUnit.SECONDS);
        } catch (Exception | StackOverflowError e) {
            game.setGameOver(GameEndReason.Draw);
            crashed = true;
        } finally {
            drawClock.cancel(false);
        }

        String row = certRow(job, arm, roll, dir, game, pp,
                !crashed && !stop.stopped && !clockFired.get() && game.getOutcome() != null);
        out.println(row);
        out.flush();
        System.out.printf("job %d arm %d roll %d -> %s%n", job.job, arm, roll,
                dir.fired ? dir.exec : "miss:" + dir.resolvedReason());
        PayDirective.clear(game);
        return dir.fired;
    }

    /** One certify row — the schema is a CONTRACT with the Python reader;
     *  fields and their conditionality must not drift. */
    private static String certRow(CertJob job, int arm, int roll, PayDirective dir, Game game,
            List<RegisteredPlayer> pp, boolean ended) {
        int tEnd = -1;
        try {
            tEnd = game.getPhaseHandler().getTurn();
        } catch (Exception ignored) {
        }
        int winner = -1;
        if (game.getOutcome() != null && !game.getOutcome().isDraw()) {
            String w = game.getOutcome().getWinningLobbyPlayer().getName();
            for (int j = 0; j < pp.size(); j++) {
                if (pp.get(j).getPlayer().getName().equals(w)) {
                    winner = j;
                }
            }
        }
        // Snap at the stop point; seat order = census player order.
        int[] life = new int[2], creatures = new int[2], power = new int[2],
                hand = new int[2], lands = new int[2];
        try {
            for (int j = 0; j < 2; j++) {
                Player gp = null;
                for (Player q : game.getPlayers()) {
                    if (q.getName().equals(pp.get(j).getPlayer().getName())) {
                        gp = q;
                    }
                }
                if (gp == null) {
                    continue;
                }
                life[j] = gp.getLife();
                hand[j] = gp.getCardsIn(ZoneType.Hand).size();
                for (Card c : gp.getCardsIn(ZoneType.Battlefield)) {
                    if (c.isCreature()) {
                        creatures[j]++;
                        power[j] += c.getNetPower();
                    }
                    if (c.isLand()) {
                        lands[j]++;
                    }
                }
            }
        } catch (Exception ignored) {
        }

        StringBuilder sb = new StringBuilder(320);
        sb.append("{\"ev\":\"certify\",\"job\":").append(job.job)
                .append(",\"arm\":").append(arm)
                .append(",\"roll\":").append(roll)
                .append(",\"fired\":").append(dir.fired);
        if (!dir.fired) {
            sb.append(",\"reason\":\"").append(esc(dir.resolvedReason())).append('"');
        }
        if (dir.fired && arm > 0 && dir.goals != null) {
            sb.append(",\"goals\":[");
            for (int i = 0; i < dir.goals.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(esc(dir.goals.get(i))).append('"');
            }
            sb.append("],\"gk\":[");
            for (int i = 0; i < dir.kinds.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(dir.kinds.get(i));
            }
            sb.append(']');
        }
        if (dir.fired) {
            sb.append(",\"exec\":\"").append(dir.exec).append('"');
            if (dir.execWhy != null) {
                sb.append(",\"exec_why\":\"").append(esc(dir.execWhy)).append('"');
            }
            if (dir.planDesc != null) {
                sb.append(",\"plan\":\"").append(esc(dir.planDesc)).append('"');
            }
            sb.append(",\"t_fired\":").append(dir.tFired);
        }
        sb.append(",\"t_end\":").append(tEnd)
                .append(",\"ended\":").append(ended)
                .append(",\"winner\":").append(winner)
                .append(",\"snap\":{\"life\":[").append(life[0]).append(',').append(life[1])
                .append("],\"creatures\":[").append(creatures[0]).append(',').append(creatures[1])
                .append("],\"power\":[").append(power[0]).append(',').append(power[1])
                .append("],\"hand\":[").append(hand[0]).append(',').append(hand[1])
                .append("],\"lands\":[").append(lands[0]).append(',').append(lands[1])
                .append("],\"avail_options\":").append(dir.availOptions)
                .append("}}");
        return sb.toString();
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    /** Minimal flat-JSON object parser (string/number/bool values, no
     *  nesting) — the jobs contract is flat and the fork carries no JSON
     *  dependency. Values land as raw strings; CertJob converts. */
    private static Map<String, String> flatJson(String line) {
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
