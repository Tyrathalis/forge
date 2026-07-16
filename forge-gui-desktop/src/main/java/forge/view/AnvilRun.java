package forge.view;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

import com.google.common.eventbus.Subscribe;

import forge.ai.AiProfileUtil;
import forge.ai.anvil.AnvilBridge;
import forge.ai.simulation.GameCopier;
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
import forge.game.card.Card;
import forge.game.event.GameEventPlayerPriority;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
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
 * Syntax: forge anvil (-d <deck1> <deck2> | -pairs <file> [-gpp <gamesPerPair>])
 *   [-f <format>] [-b local-random|grpc:host:port]
 *   [-tags <csv>] [-census <out.jsonl>] [-obs <out.zst>]
 *   chunk mode:  -range <start> <count> -seedbase <long> [-results <games.jsonl>] [-stopfile <path>]
 *   legacy mode: [-n <games>] [-s <baseSeed>]
 *
 * -pairs: one deck pair per line, tab-separated (deck names contain spaces);
 * game index i plays pair (i / gpp) % nPairs. AI personalities are drawn
 * per seat from the game seed (sorted profile list), so corpus provenance is
 * a pure function of (seedbase, index) — logged in results JSONL and the
 * observation game record.
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
        boolean fixedPair = params != null && params.containsKey("d") && params.get("d").size() == 2;
        boolean pairFile = params != null && params.containsKey("pairs");
        if (params == null || fixedPair == pairFile) {
            System.out.println("Syntax: forge anvil (-d <deck1> <deck2> | -pairs <file> [-gpp <n>]) [-f <format>] "
                    + "[-b local-random|grpc:host:port] [-tags <csv>] [-bridgeseats <csv>] [-reask] "
                    + "[-census <out.jsonl>] [-obs <out.zst>] "
                    + "[-range <start> <count> -seedbase <long> [-results <jsonl>] [-stopfile <path>]] "
                    + "[-rollout <k> -points <m> -labels <jsonl> [-noreshuffle]] "
                    + "[-n <games>] [-s <baseSeed>]");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        String bridgeMode = params.containsKey("b") ? params.get("b").get(0) : "local-random";
        Set<String> tags = params.containsKey("tags")
                ? new HashSet<>(Arrays.asList(params.get("tags").get(0).split(","))) : DEFAULT_TAGS;
        // null = all seats bridged (self-play); "-bridgeseats 0" = seat 0 vs heuristic
        Set<Integer> bridgeSeats = null;
        if (params.containsKey("bridgeseats")) {
            bridgeSeats = new HashSet<>();
            for (String s : params.get("bridgeseats").get(0).split(",")) {
                bridgeSeats.add(Integer.parseInt(s.trim()));
            }
        }
        // D6 run-2: re-ask-on-veto (d6-vtrace-loop §6b). Per-JVM, all seats.
        boolean reask = params.containsKey("reask");
        PlayerControllerAnvil.setReaskOnVeto(reask);

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

        // Rollout-label mode (M2 D4): at -points sampled quiescent MAIN1
        // priority windows per game, fork the live game and complete -rollout
        // copies to game end under the bridge (forks inherit Anvil
        // controllers; wire-only obs sessions keep them out of the store).
        // One labels-JSONL record per fork point; an Obs "mark" record keys
        // the fork point to the next mainline priority window. Unless
        // -noreshuffle, each rollout silently re-randomizes both libraries
        // (determinization: the label approximates E over unseen order, not
        // the outcome of the one concrete order nobody has seen).
        int rolloutK = params.containsKey("rollout")
                ? Integer.parseInt(params.get("rollout").get(0)) : 0;
        int rolloutPoints = params.containsKey("points")
                ? Integer.parseInt(params.get("points").get(0)) : 4;
        boolean rolloutReshuffle = !params.containsKey("noreshuffle");

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

        // Deck schedule: fixed pair (-d) or index-mapped pairs file (-pairs).
        List<String[]> pairNames = new ArrayList<>();
        int gamesPerPair = params.containsKey("gpp")
                ? Integer.parseInt(params.get("gpp").get(0)) : 5;
        if (fixedPair) {
            pairNames.add(new String[] { params.get("d").get(0), params.get("d").get(1) });
            gamesPerPair = Integer.MAX_VALUE;
        } else {
            try {
                for (String line : Files.readAllLines(Paths.get(params.get("pairs").get(0)),
                        StandardCharsets.UTF_8)) {
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    String[] pq = line.split("\t");
                    if (pq.length != 2) {
                        System.err.println("FATAL: bad pairs line (need 2 tab-separated decks): " + line);
                        System.exit(2);
                    }
                    pairNames.add(pq);
                }
            } catch (java.io.IOException e) {
                System.err.println("FATAL: cannot read pairs file: " + e);
                System.exit(2);
            }
            if (pairNames.isEmpty()) {
                System.err.println("FATAL: pairs file has no pairs");
                System.exit(2);
            }
        }
        Map<String, Deck> deckCache = new HashMap<>();

        // AI personalities (ADR-0004: corpus is personality-randomized). Sorted
        // so the seed->profile map is stable across filesystems.
        List<String> profiles = new ArrayList<>(AiProfileUtil.getAvailableProfiles());
        Collections.sort(profiles);
        if (profiles.isEmpty()) {
            // Profiles are corpus provenance — an empty list would silently run
            // every seat on enum defaults while the log claims randomization.
            System.err.println("FATAL: no AI profiles found (AI_PROFILE_DIR missing?)");
            System.exit(2);
        }

        System.out.printf("Anvil worker: games [%d,%d), %s, seedbase=%s, bridge=%s, tags=%s, "
                        + "reask=%s, pairs=%d gpp=%s, profiles=%s%n",
                rangeStart, rangeStart + nGames, type,
                seedBase != null ? seedBase : ("legacy:" + legacyBaseSeed), bridgeMode, tags,
                reask, pairNames.size(), fixedPair ? "-" : String.valueOf(gamesPerPair), profiles);

        Map<String, Integer> tally = new TreeMap<>();
        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "anvil-watchdog");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.currentTimeMillis();
        PrintWriter results = null;
        PrintWriter labels = null;
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
                if (rolloutK > 0) {
                    // Fork copies share the census stream with their mainline
                    // game index — rollout decisions would inflate per-run
                    // veto/rung telemetry. Labeler runs go without census.
                    System.err.println("WARNING: -census with -rollout pollutes "
                            + "telemetry with fork decisions; prefer omitting it");
                }
            }
            if (params.containsKey("labels")) {
                try {
                    labels = new PrintWriter(new FileWriter(params.get("labels").get(0), true));
                } catch (java.io.IOException e) {
                    System.err.println("FATAL: cannot open labels file: " + e);
                    System.exit(2);
                }
            }
            if (rolloutK > 0 && labels == null) {
                System.err.println("FATAL: -rollout requires -labels <out.jsonl>");
                System.exit(2);
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
            // Headless worker: never route uncaught throwables to Forge's GUI
            // bug-report dialog — Main registers ExceptionHandler, and an
            // OutOfMemoryError that escaped the per-game catch opened a modal
            // Swing dialog that wedged workers forever (model-mirror run,
            // 2026-07-12). Log and let the JVM policy decide (the harness runs
            // workers with -XX:+ExitOnOutOfMemoryError; chunk re-issue covers
            // a dead worker).
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("[anvil] uncaught " + e.getClass().getName()
                        + " on thread " + t.getName() + " (headless: no dialog)");
                e.printStackTrace();
            });
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

                String[] pair = pairNames.get((int) ((idx / (long) gamesPerPair) % pairNames.size()));
                List<Deck> decks = new ArrayList<>();
                for (String deckName : pair) {
                    Deck d = deckCache.computeIfAbsent(deckName,
                            n -> SimulateMatch.deckFromCommandLineParameter(n, type));
                    if (d == null) {
                        System.err.println("FATAL: could not load deck: " + deckName);
                        System.exit(2);
                    }
                    decks.add(d);
                }

                // Per-seat personality: pure function of the game seed, so the
                // corpus expert mix reproduces from (seedbase, index) alone.
                String[] seatProfiles = new String[decks.size()];
                List<RegisteredPlayer> pp = new ArrayList<>();
                for (int j = 0; j < decks.size(); j++) {
                    Deck d = decks.get(j);
                    seatProfiles[j] = profiles.get((int) Long.remainderUnsigned(
                            splitmix64(seed + (j + 1) * 0x9E3779B97F4A7C15L), profiles.size()));
                    RegisteredPlayer rp = type.equals(GameType.Commander)
                            ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
                    // Mixed-seat arms (M1 D8): seats outside -bridgeseats get an
                    // empty tag set — every decision falls through to the
                    // inherited heuristic (provenance rule intact), and the name
                    // prefix makes games.jsonl winners parseable per arm.
                    boolean seatBridged = bridgeSeats == null || bridgeSeats.contains(j);
                    AnvilLobbyPlayer lp = new AnvilLobbyPlayer(
                            (seatBridged ? "Anvil(" : "Heur(") + (j + 1) + ")-" + d.getName(),
                            bridge, seatBridged ? tags : java.util.Collections.emptySet());
                    lp.setAiProfile(seatProfiles[j]);
                    rp.setPlayer(lp);
                    pp.add(rp);
                }

                Match mc = new Match(rules, pp, "Anvil");
                Game game = mc.createGame();
                Census.startGame(idx, seed);
                Obs.startGame(idx, seed, game, type.toString());
                long gameT0 = System.currentTimeMillis();
                bridge.gameStart("g" + idx, seed);
                if (rolloutK > 0) {
                    game.subscribeToEvents(new RolloutMonitor(game, idx, seed,
                            rolloutK, rolloutPoints, rolloutReshuffle, bridge,
                            type.toString(), labels, watchdogs));
                }
                // Rollout forks run inside the game's wall — budget the clocks
                // for them (45 s/rollout is far above the 4.4 s median but
                // below the per-rollout timeout, so a pathological point can't
                // eat the whole game budget).
                int extraS = rolloutK > 0 ? rolloutPoints * rolloutK * 45 : 0;
                final boolean[] drawClockHit = {false};
                ScheduledFuture<?> drawClock = watchdogs.schedule(() -> {
                    drawClockHit[0] = true;
                    game.setGameOver(GameEndReason.Draw);
                }, DRAW_CLOCK_S + extraS, TimeUnit.SECONDS);
                String status;
                try {
                    TimeLimitedCodeBlock.runWithTimeout(() -> mc.startGame(game),
                            GAME_HARD_CAP_S + extraS, TimeUnit.SECONDS);
                    status = game.getOutcome() == null ? "no_outcome"
                            : game.getOutcome().isDraw() ? "draw" : "won";
                } catch (Throwable e) {
                    // Throwable, not Exception|StackOverflowError: any Error
                    // class escaping here reaches the uncaught handler and
                    // used to wedge the worker in a GUI dialog. OOM is the
                    // exception — -XX:+ExitOnOutOfMemoryError kills the JVM
                    // before this catch matters.
                    game.setGameOver(GameEndReason.Draw);
                    status = "crash_or_hang:" + e.getClass().getSimpleName();
                    if (Boolean.getBoolean("anvil.crash.trace")) {
                        e.printStackTrace();
                    }
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
                    // registered players, not getPlayers(): the live list drops
                    // eliminated players, so at game end the winner is ~always
                    // index 0 (same reindex class as the M1 D1 header fix).
                    // Obs records index seats by getRegisteredPlayers throughout.
                    for (int wi = 0; wi < game.getRegisteredPlayers().size(); wi++) {
                        if (game.getRegisteredPlayers().get(wi).getName().equals(winner)) {
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
                            + ",\"draw_clock\":" + drawClockHit[0]
                            + ",\"decks\":[\"" + jstr(pair[0]) + "\",\"" + jstr(pair[1]) + "\"]"
                            + ",\"profiles\":[\"" + jstr(seatProfiles[0]) + "\",\"" + jstr(seatProfiles[1]) + "\"]}");
                    results.flush();
                }
                System.out.printf("game %d seed=%d -> %s (%d turns)%n", idx, seed, status, turns);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (results != null) {
                results.close();
            }
            if (labels != null) {
                labels.close();
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

    private static String jstr(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

    // ------------------------------------------------------------------
    // Rollout-label mode (M2 D4): fork the live game at sampled quiescent
    // MAIN1 priority windows, complete K copies to game end under the
    // bridge, one labels-JSONL record per fork point. Fork discipline is
    // ForkFidelityCheck's (quiescence drain, active-player priority only,
    // RNG snapshot/restore around the block, wire-only obs sessions).
    // ------------------------------------------------------------------

    private static final int ROLLOUT_TIMEOUT_S = 120;

    private static final class RolloutMonitor {
        final Game game;
        final int gameIdx;
        final long seed;
        final int k;
        final boolean reshuffle;
        final AnvilBridge bridge;
        final String fmt;
        final PrintWriter labels;
        final ScheduledExecutorService watchdogs;
        final java.util.TreeSet<Integer> targets = new java.util.TreeSet<>();
        int fp = 0;

        RolloutMonitor(Game game, int gameIdx, long seed, int k, int points,
                boolean reshuffle, AnvilBridge bridge, String fmt,
                PrintWriter labels, ScheduledExecutorService watchdogs) {
            this.game = game;
            this.gameIdx = gameIdx;
            this.seed = seed;
            this.k = k;
            this.reshuffle = reshuffle;
            this.bridge = bridge;
            this.fmt = fmt;
            this.labels = labels;
            this.watchdogs = watchdogs;
            // Target turns from a meta-RNG (pure function of the game seed —
            // never perturbs game randomness); distinct turns in [2, 16].
            Random meta = new Random(splitmix64(seed ^ 0xD4D4D4D4D4D4D4D4L));
            while (targets.size() < Math.min(points, 15)) {
                targets.add(2 + meta.nextInt(15));
            }
        }

        @Subscribe
        public void onPriority(GameEventPlayerPriority ev) {
            if (targets.isEmpty() || ev.phase() != PhaseType.MAIN1) {
                return;
            }
            PhaseHandler ph = game.getPhaseHandler();
            if (ph.getTurn() < targets.first() || !game.getStack().isEmpty()) {
                return;
            }
            // Active player's priority only (GameCopier resets the copy's
            // priority to the active player), quiescent stack only.
            if (ph.getPriorityPlayer() != ph.getPlayerTurn()) {
                return;
            }
            java.util.Set<Card> affected = new HashSet<>();
            do {
                game.getAction().checkStateEffects(false, affected);
                if (game.isGameOver()) {
                    return;
                }
            } while (game.getStack().addAllTriggeredAbilitiesToStack());
            if (!game.getStack().isEmpty()) {
                return;
            }
            int turn = ph.getTurn();
            while (!targets.isEmpty() && targets.first() <= turn) {
                targets.pollFirst();
            }
            doRollouts(turn);
        }

        private void doRollouts(int turn) {
            int myFp = fp++;
            // The mark keys this fork point to the NEXT mainline priority
            // window in the obs stream (the label's training window).
            Obs.mark(game, "fork", "fp", myFp, "kr", k);
            byte[] rngState = snapshotRng();
            int[] wins = new int[game.getRegisteredPlayers().size()];
            int draws = 0;
            int crashes = 0;
            long block0 = System.nanoTime();
            long copyMsTotal = 0;
            for (int r = 0; r < k; r++) {
                Game copy;
                long c0 = System.nanoTime();
                try {
                    copy = new GameCopier(game).makeCopy();
                } catch (Throwable t) {
                    crashes++;
                    MyRandom.setRandom(restoreRng(rngState));
                    continue;
                }
                copyMsTotal += (System.nanoTime() - c0) / 1_000_000;
                Random rollRng = new Random(splitmix64(
                        seed ^ (myFp * 0x9E3779B97F4A7C15L) ^ (r * 0xBF58476D1CE4E5B9L)));
                if (reshuffle) {
                    // Determinization: silently re-randomize both libraries
                    // (Zone.setCards — no shuffle events/triggers) so the K
                    // rollouts average over unseen order instead of replaying
                    // the one concrete order nobody has observed. Known-order
                    // states (scry tops, tucked bottoms) are knowingly
                    // approximated — label quality, not ledger unbiasedness.
                    for (Player p : copy.getPlayers()) {
                        List<Card> lib = new ArrayList<>();
                        for (Card c : p.getZone(ZoneType.Library)) {
                            lib.add(c);
                        }
                        Collections.shuffle(lib, rollRng);
                        p.getZone(ZoneType.Library).setCards(lib);
                    }
                }
                copy.getPhaseHandler().devResumeAtPriority();
                copy.copyLastState();
                String wid = "g" + gameIdx + ".f" + myFp + "r" + r;
                Obs.startWireGame(copy, wid, seed, fmt, game);
                bridge.gameStart(wid, seed, Obs.lastHeaderForBridge(copy));
                MyRandom.setRandom(rollRng);
                ScheduledFuture<?> clock = watchdogs.schedule(
                        () -> copy.setGameOver(GameEndReason.Draw),
                        ROLLOUT_TIMEOUT_S, TimeUnit.SECONDS);
                try {
                    copy.getPhaseHandler().mainGameLoop();
                    if (copy.getOutcome() == null || copy.getOutcome().isDraw()) {
                        draws++;
                    } else {
                        String w = copy.getOutcome().getWinningLobbyPlayer().getName();
                        int wi = -1;
                        for (int j = 0; j < copy.getRegisteredPlayers().size(); j++) {
                            if (copy.getRegisteredPlayers().get(j).getName().equals(w)) {
                                wi = j;
                                break;
                            }
                        }
                        if (wi >= 0) {
                            wins[wi]++;
                        } else {
                            draws++;
                        }
                    }
                } catch (Throwable t) {
                    crashes++;
                } finally {
                    clock.cancel(false);
                    if (!copy.isGameOver()) {
                        copy.setGameOver(GameEndReason.Draw);
                    }
                    MyRandom.setRandom(restoreRng(rngState));
                    Obs.endWireGame(copy);
                }
            }
            // Re-announce the mainline: the fork wire sessions re-bound the
            // server's per-stream header.
            bridge.gameStart("g" + gameIdx, seed, Obs.lastHeaderForBridge(game));
            if (labels != null) {
                StringBuilder sb = new StringBuilder(192);
                sb.append("{\"i\":").append(gameIdx)
                        .append(",\"seed\":").append(seed)
                        .append(",\"fp\":").append(myFp)
                        .append(",\"t\":").append(turn)
                        .append(",\"k\":").append(k)
                        .append(",\"w\":[");
                for (int j = 0; j < wins.length; j++) {
                    sb.append(j > 0 ? "," : "").append(wins[j]);
                }
                sb.append("],\"draw\":").append(draws)
                        .append(",\"crash\":").append(crashes)
                        .append(",\"copy_ms\":").append(copyMsTotal)
                        .append(",\"ms\":").append((System.nanoTime() - block0) / 1_000_000)
                        .append('}');
                synchronized (labels) {
                    labels.println(sb);
                    labels.flush();
                }
            }
        }
    }

    private static byte[] snapshotRng() {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(bos)) {
                oos.writeObject(MyRandom.getRandom());
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("RNG snapshot failed", e);
        }
    }

    private static Random restoreRng(byte[] state) {
        try (java.io.ObjectInputStream ois = new java.io.ObjectInputStream(
                new java.io.ByteArrayInputStream(state))) {
            return (Random) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("RNG restore failed", e);
        }
    }
}
