package forge.view;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
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

import forge.ai.anvil.AnvilLobbyPlayer;
import forge.ai.anvil.LocalRandomBridge;
import forge.ai.anvil.PlayerControllerAnvil;
import forge.ai.simulation.GameCopier;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.event.GameEventPlayerPriority;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

/**
 * Fork-fidelity differential test: forks a live game via GameCopier at a
 * quiescent decision point (empty stack, drained trigger queue, priority about
 * to be given in a main phase), replays the fork to completion with the same
 * RNG state, then lets the mainline finish and compares the two trajectories.
 *
 * A faithful copy + deterministic engine implies identical per-turn state
 * digests and identical outcomes. Divergences are classified per game.
 *
 * Syntax: forge forkcheck -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl>
 */
public final class ForkFidelityCheck {
    /** Turn window in which the fork point is (deterministically) drawn per game. */
    private static final int MIN_FORK_TURN = 3;
    private static final int MAX_FORK_TURN = 12;
    /** Draw clocks, mirroring SimulateMatch's 120s convention. */
    private static final long MAIN_TIMEOUT_S = 300;
    private static final long FORK_TIMEOUT_S = 180;
    /** Last-resort per-game cap (covers mainline + inline fork replay + slack). */
    private static final long GAME_HARD_CAP_S = 660;

    private ForkFidelityCheck() {
    }

    public static void run(String[] args) {
        FModel.initialize(null, null);

        Map<String, List<String>> params = parseParams(args);
        if (params == null || !params.containsKey("d") || params.get("d").size() != 2) {
            System.out.println("Syntax: forge forkcheck -d <deck1> <deck2> -f <format> -n <games> -s <baseSeed> -o <out.jsonl>");
            return;
        }

        GameType type = params.containsKey("f")
                ? GameType.valueOf(params.get("f").get(0)) : GameType.Commander;
        int nGames = params.containsKey("n") ? Integer.parseInt(params.get("n").get(0)) : 10;
        long baseSeed = params.containsKey("s") ? Long.parseLong(params.get("s").get(0)) : 20260703L;
        String outPath = params.containsKey("o") ? params.get("o").get(0) : "forkcheck.jsonl";
        // Sensitivity check: perturb the fork (1 life) after the static compare; the
        // trajectory detector MUST then report divergence, or the harness is broken.
        boolean perturb = params.containsKey("perturb");
        // Discriminator: replay the fork with a DIFFERENT RNG instead of the clone.
        // If the divergence rate matches the same-RNG rate, RNG stream identity isn't
        // the binding factor and object-identity effects (hash iteration order) are.
        boolean freshRng = params.containsKey("freshrng");
        // Chain mode: also make F2 = copy(F1) at the same point and replay both from
        // the same RNG snapshot. F2 ≡ F1 means the orig→copy scramble is a one-time
        // canonicalization (copy-of-copy is faithful → search trees are internally
        // consistent); F2 ≠ F1 means every copy introduces fresh drift.
        boolean chain = params.containsKey("chain");
        // Twin mode: F2 = a second independent copy of the MAINLINE at the same
        // point, replayed from the same RNG snapshot as F1. F1 ≡ F2 is the
        // rollout-contract determinism check: identical state + identical seed
        // must reproduce the identical trajectory, or rollout value labels are
        // not replayable. (Chain reuses the same plumbing with source = F1.)
        boolean twin = params.containsKey("twin");
        // Bridge mode: seats are PlayerControllerAnvil answered by the
        // in-process LocalRandomBridge (deterministic per seed, transport-free)
        // — the rollout contract's "completion under the bridge" leg. Forked
        // games inherit Anvil controllers because clonePlayer reuses the
        // AnvilLobbyPlayer (a LobbyPlayerAi subclass); obs stays off, so the
        // isLogging(game) guard keeps forks out of any obs stream by design.
        boolean bridge = params.containsKey("bridge");

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

        System.out.printf("Fork-fidelity check: %d games, %s, baseSeed=%d, forkTurn in [%d,%d]%n",
                nGames, type, baseSeed, MIN_FORK_TURN, MAX_FORK_TURN);

        // -seeds <s1,s2,...> runs an explicit seed list (e.g. a repro set)
        // instead of the contiguous baseSeed..baseSeed+n-1 range.
        List<Long> seeds = new ArrayList<>();
        if (params.containsKey("seeds")) {
            for (String part : String.join(",", params.get("seeds")).split(",")) {
                if (!part.isBlank()) {
                    seeds.add(Long.parseLong(part.trim()));
                }
            }
        } else {
            for (int i = 0; i < nGames; i++) {
                seeds.add(baseSeed + i);
            }
        }

        Map<String, Integer> tally = new TreeMap<>();
        try (PrintWriter out = new PrintWriter(new FileWriter(outPath, true))) {
            for (int i = 0; i < seeds.size(); i++) {
                GameResult r = runOneGame(rules, type, decks, seeds.get(i), perturb, freshRng, chain, twin, bridge);
                out.println(r.toJson());
                out.flush();
                tally.merge(r.status, 1, Integer::sum);
                if (r.chainStatus != null) {
                    tally.merge("chain:" + r.chainStatus, 1, Integer::sum);
                }
                System.out.printf("game %d/%d seed=%d forkTurn=%d -> %s%n",
                        i + 1, seeds.size(), r.seed, r.forkTurn, r.summaryLine());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("=== fork-fidelity tally ===");
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

    /** M0 tag set: what the LocalRandomBridge answers in -bridge mode. */
    private static final Set<String> BRIDGE_TAGS = new HashSet<>(java.util.Arrays.asList(
            PlayerControllerAnvil.TAG_PRIORITY, PlayerControllerAnvil.TAG_MULLIGAN,
            PlayerControllerAnvil.TAG_TUCK, PlayerControllerAnvil.TAG_TRIGGER,
            PlayerControllerAnvil.TAG_BINARY, PlayerControllerAnvil.TAG_NUMBER));
    private static final LocalRandomBridge LOCAL_BRIDGE = new LocalRandomBridge();

    private static GameResult runOneGame(GameRules rules, GameType type, List<Deck> decks, long seed, boolean perturb, boolean freshRng, boolean chain, boolean twin, boolean bridge) {
        GameResult result = new GameResult(seed);
        // Fork turn drawn from a meta-RNG so it never perturbs game randomness.
        Random meta = new Random(seed * 6364136223846793005L + 1442695040888963407L);
        result.plannedForkTurn = MIN_FORK_TURN + meta.nextInt(MAX_FORK_TURN - MIN_FORK_TURN + 1);

        MyRandom.setRandom(new Random(seed));

        List<RegisteredPlayer> pp = new ArrayList<>();
        for (int i = 0; i < decks.size(); i++) {
            Deck d = decks.get(i);
            RegisteredPlayer rp = type.equals(GameType.Commander)
                    ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
            if (bridge) {
                rp.setPlayer(new AnvilLobbyPlayer(
                        "Anvil(" + (i + 1) + ")-" + d.getName(), LOCAL_BRIDGE, BRIDGE_TAGS));
            } else {
                rp.setPlayer(GamePlayerUtil.createAiPlayer("Ai(" + (i + 1) + ")-" + d.getName(), i));
            }
            pp.add(rp);
        }

        Match mc = new Match(rules, pp, "ForkFidelity");
        Game game = mc.createGame();
        Monitor monitor = new Monitor(game, result);
        monitor.perturb = perturb;
        monitor.freshRng = freshRng;
        monitor.chain = chain;
        monitor.twin = twin;
        result.mode = (twin ? "twin" : chain ? "chain" : "")
                + (bridge ? (twin || chain ? "+bridge" : "bridge") : "");
        game.subscribeToEvents(monitor);

        ScheduledExecutorService watchdogs = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "forkcheck-watchdog");
            t.setDaemon(true);
            return t;
        });
        monitor.watchdogs = watchdogs;
        ScheduledFuture<?> mainClock = watchdogs.schedule(
                () -> game.setGameOver(GameEndReason.Draw), MAIN_TIMEOUT_S + FORK_TIMEOUT_S, TimeUnit.SECONDS);
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> mc.startGame(game), GAME_HARD_CAP_S, TimeUnit.SECONDS);
        } catch (Exception | StackOverflowError e) {
            result.notes = "mainline: " + e;
            game.setGameOver(GameEndReason.Draw);
            result.status = "main_crash_or_hang";
            return result;
        } finally {
            mainClock.cancel(false);
            watchdogs.shutdownNow();
        }

        result.mainTurns = monitor.mainTrace.size();
        result.forkTurns = monitor.forkTrace.size();
        if (!monitor.forked) {
            result.status = "no_fork"; // game ended before the fork turn
            return result;
        }
        result.mainOutcome = outcomeString(game);
        result.mainTraceHash = traceHash(monitor.mainTrace, result.forkTurn);
        result.forkTraceHash = traceHash(monitor.forkTrace, result.forkTurn);
        classify(result, monitor);
        if (chain || twin) {
            result.chainTraceHash = traceHash(monitor.chainTrace, result.forkTurn);
            classifyChain(result, monitor);
        }
        return result;
    }

    /** Compares F1's trajectory to F2's (fork-of-fork), independent of the main-vs-F1 verdict. */
    private static void classifyChain(GameResult r, Monitor m) {
        if (r.chainStatus != null) {
            return; // chain_copy_crash / chain_resume_crash already set
        }
        if (!r.chainStaticMatch) {
            r.chainStatus = "chain_static_mismatch";
            return;
        }
        int lastCommonTurn = Math.min(
                m.forkTrace.isEmpty() ? 0 : Collections.max(m.forkTrace.keySet()),
                m.chainTrace.isEmpty() ? 0 : Collections.max(m.chainTrace.keySet()));
        for (int t = r.forkTurn + 1; t <= lastCommonTurn; t++) {
            List<String> a = m.forkTrace.get(t);
            List<String> b = m.chainTrace.get(t);
            if (a == null || b == null || !a.equals(b)) {
                r.chainStatus = "chain_divergence";
                r.chainDivergenceTurn = t;
                r.chainSample = firstDiff(a, b);
                return;
            }
        }
        if (!r.forkOutcome.equals(r.chainOutcome)) {
            r.chainStatus = "chain_outcome_mismatch";
            r.chainSample = "f1=" + r.forkOutcome + " f2=" + r.chainOutcome;
            return;
        }
        r.chainStatus = "chain_clean";
    }

    /** Compares mainline vs fork traces from the fork turn onward and sets result.status. */
    private static void classify(GameResult r, Monitor m) {
        if (r.status != null) {
            return; // copy_crash / resume_crash already set at fork time
        }
        if (!r.staticMatch) {
            r.status = "static_mismatch";
            return;
        }
        int lastCommonTurn = Math.min(
                m.mainTrace.isEmpty() ? 0 : Collections.max(m.mainTrace.keySet()),
                m.forkTrace.isEmpty() ? 0 : Collections.max(m.forkTrace.keySet()));
        for (int t = r.forkTurn + 1; t <= lastCommonTurn; t++) {
            List<String> a = m.mainTrace.get(t);
            List<String> b = m.forkTrace.get(t);
            if (a == null || b == null || !a.equals(b)) {
                r.status = "divergence";
                r.divergenceTurn = t;
                r.divergenceSample = firstDiff(a, b);
                return;
            }
        }
        if (!r.mainOutcome.equals(r.forkOutcome)) {
            r.status = "outcome_mismatch";
            r.divergenceSample = "main=" + r.mainOutcome + " fork=" + r.forkOutcome;
            return;
        }
        r.status = "clean";
    }

    private static String firstDiff(List<String> a, List<String> b) {
        if (a == null || b == null) {
            return "missing trace entry (main=" + (a != null) + " fork=" + (b != null) + ")";
        }
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            String x = i < a.size() ? a.get(i) : "<absent>";
            String y = i < b.size() ? b.get(i) : "<absent>";
            if (!x.equals(y)) {
                return "main: " + x + " || fork: " + y;
            }
        }
        return "traces equal element-wise but not equals()?";
    }

    // ------------------------------------------------------------------
    // Event monitor: records the mainline trace and performs the fork.
    // ------------------------------------------------------------------
    private static final class Monitor {
        final Game game;
        final GameResult result;
        final Map<Integer, List<String>> mainTrace = new TreeMap<>();
        final Map<Integer, List<String>> forkTrace = new TreeMap<>();
        final Map<Integer, List<String>> chainTrace = new TreeMap<>();
        ScheduledExecutorService watchdogs;
        boolean forked = false;
        boolean perturb = false;
        boolean freshRng = false;
        boolean chain = false;
        boolean twin = false;

        Monitor(Game game, GameResult result) {
            this.game = game;
            this.result = result;
        }

        @Subscribe
        public void onTurnBegan(GameEventTurnBegan ev) {
            mainTrace.put(ev.turnNumber(), digest(game));
        }

        @Subscribe
        public void onPriority(GameEventPlayerPriority ev) {
            if (forked || ev.phase() != PhaseType.MAIN1) {
                return;
            }
            PhaseHandler ph = game.getPhaseHandler();
            if (ph.getTurn() < result.plannedForkTurn || !game.getStack().isEmpty()) {
                return;
            }
            // Only fork when the ACTIVE player holds priority: GameCopier resets the
            // copy's priority to the active player, so forking at an opponent-priority
            // window would hand the active player an extra action in the fork.
            // If the planned turn's MAIN1 offers no such window, the fork slips to a
            // later turn (recorded via forkTurn vs plannedForkTurn).
            if (ph.getPriorityPlayer() != ph.getPlayerTurn()) {
                return;
            }
            // Drain pending triggers exactly the way the priority loop is about to
            // (PhaseHandler.checkStateBasedEffects): if anything lands on the stack,
            // this priority point isn't quiescent — defer to a later priority event.
            Set<Card> affected = new HashSet<>();
            do {
                game.getAction().checkStateEffects(false, affected);
                if (game.isGameOver()) {
                    return;
                }
            } while (game.getStack().addAllTriggeredAbilitiesToStack());
            if (!game.getStack().isEmpty()) {
                return;
            }
            forked = true;
            result.forkTurn = ph.getTurn();
            doFork();
        }

        private void doFork() {
            byte[] rngState = snapshotRng();

            Game copy;
            long t0 = System.nanoTime();
            try {
                copy = new GameCopier(game).makeCopy();
            } catch (Throwable t) {
                result.status = "copy_crash";
                result.notes = String.valueOf(t);
                MyRandom.setRandom(restoreRng(rngState)); // makeCopy may have consumed RNG
                return;
            }
            result.copyMs = (System.nanoTime() - t0) / 1_000_000;

            List<String> dMain = digest(game);
            List<String> dFork = digest(copy);
            result.staticMatch = dMain.equals(dFork);
            if (!result.staticMatch) {
                result.divergenceTurn = result.forkTurn;
                result.divergenceSample = firstDiff(dMain, dFork);
            }

            // Chain mode: F2 = copy(F1); twin mode: F2 = a second copy of the
            // mainline. Both made at the same point BEFORE F1 plays anything,
            // both compared against F1 (digest now, trace after replay).
            Game copy2 = null;
            if (chain || twin) {
                long t1 = System.nanoTime();
                try {
                    copy2 = new GameCopier(twin ? game : copy).makeCopy();
                    result.copy2Ms = (System.nanoTime() - t1) / 1_000_000;
                    result.chainStaticMatch = dFork.equals(digest(copy2));
                } catch (Throwable t) {
                    result.chainStatus = "chain_copy_crash";
                    result.notes = result.notes + " chain: " + t;
                    copy2 = null;
                }
            }

            if (perturb) {
                Player p0 = copy.getPlayers().get(0);
                p0.setLife(p0.getLife() - 1, null);
            }

            ForkRecorder rec = new ForkRecorder(copy, forkTrace);
            copy.subscribeToEvents(rec);
            copy.getPhaseHandler().devResumeAtPriority();
            copy.copyLastState();

            MyRandom.setRandom(freshRng ? new Random(result.seed * 31 + 7) : restoreRng(rngState));
            ScheduledFuture<?> forkClock = watchdogs.schedule(
                    () -> copy.setGameOver(GameEndReason.Draw), FORK_TIMEOUT_S, TimeUnit.SECONDS);
            try {
                copy.getPhaseHandler().mainGameLoop();
                result.forkOutcome = outcomeString(copy);
            } catch (Throwable t) {
                result.status = "resume_crash";
                result.notes = String.valueOf(t);
            } finally {
                forkClock.cancel(false);
                if (!copy.isGameOver()) {
                    copy.setGameOver(GameEndReason.Draw);
                }
                MyRandom.setRandom(restoreRng(rngState));
            }

            // F2 replay from the same RNG snapshot; compare to F1 in classifyChain.
            if (copy2 != null && result.chainStatus == null) {
                final Game f2 = copy2;
                f2.subscribeToEvents(new ForkRecorder(f2, chainTrace));
                f2.getPhaseHandler().devResumeAtPriority();
                f2.copyLastState();
                MyRandom.setRandom(freshRng ? new Random(result.seed * 31 + 7) : restoreRng(rngState));
                ScheduledFuture<?> chainClock = watchdogs.schedule(
                        () -> f2.setGameOver(GameEndReason.Draw), FORK_TIMEOUT_S, TimeUnit.SECONDS);
                try {
                    f2.getPhaseHandler().mainGameLoop();
                    result.chainOutcome = outcomeString(f2);
                } catch (Throwable t) {
                    result.chainStatus = "chain_resume_crash";
                    result.notes = result.notes + " chain: " + t;
                } finally {
                    chainClock.cancel(false);
                    if (!f2.isGameOver()) {
                        f2.setGameOver(GameEndReason.Draw);
                    }
                    // Mainline resumes from the exact RNG state the fork started with.
                    MyRandom.setRandom(restoreRng(rngState));
                }
            }
        }
    }

    /** Subscribed to the forked game only: records its per-turn digests. */
    private static final class ForkRecorder {
        final Game copy;
        final Map<Integer, List<String>> trace;

        ForkRecorder(Game copy, Map<Integer, List<String>> trace) {
            this.copy = copy;
            this.trace = trace;
        }

        @Subscribe
        public void onTurnBegan(GameEventTurnBegan ev) {
            trace.put(ev.turnNumber(), digest(copy));
        }
    }

    // ------------------------------------------------------------------
    // State digest
    // ------------------------------------------------------------------
    private static final ZoneType[] ORDERED_ZONES = { ZoneType.Library, ZoneType.Graveyard };
    private static final ZoneType[] UNORDERED_ZONES = {
            ZoneType.Battlefield, ZoneType.Hand, ZoneType.Exile, ZoneType.Command };

    static List<String> digest(Game g) {
        List<String> lines = new ArrayList<>();
        PhaseHandler ph = g.getPhaseHandler();
        lines.add("TURN " + ph.getTurn() + " " + ph.getPhase() + " active=" + name(ph.getPlayerTurn()));
        for (Player p : g.getPlayers()) {
            lines.add("PLAYER " + p.getName()
                    + " life=" + p.getLife()
                    + " poison=" + p.getPoisonCounters()
                    + " counters=" + countersString(p.getCounters())
                    + " mana=" + p.getManaPool().totalMana()
                    + " lands=" + p.getLandsPlayedThisTurn()
                    + " maxHand=" + p.getMaxHandSize());
            // Order-sensitive zones: library order determines future draws; graveyard order matters.
            for (ZoneType z : ORDERED_ZONES) {
                List<String> cards = new ArrayList<>();
                for (Card c : p.getCardsIn(z)) {
                    cards.add(c.getName());
                }
                lines.add("ZONE " + p.getName() + " " + z + " n=" + cards.size()
                        + " " + String.join("|", cards));
            }
            for (ZoneType z : UNORDERED_ZONES) {
                List<String> cards = new ArrayList<>();
                for (Card c : p.getCardsIn(z)) {
                    cards.add(cardSig(c, z));
                }
                Collections.sort(cards);
                lines.add("ZONE " + p.getName() + " " + z + " n=" + cards.size()
                        + " " + String.join("|", cards));
            }
        }
        lines.add("STACK n=" + g.getStack().size());
        return lines;
    }

    private static String cardSig(Card c, ZoneType z) {
        if (z != ZoneType.Battlefield) {
            return c.getName();
        }
        StringBuilder sb = new StringBuilder(c.getName());
        sb.append(c.isTapped() ? ":T" : ":U");
        if (c.getDamage() > 0) {
            sb.append(":dmg").append(c.getDamage());
        }
        String counters = countersString(c.getCounters());
        if (!counters.isEmpty()) {
            sb.append(":cnt{").append(counters).append('}');
        }
        if (c.isCreature()) {
            sb.append(":pt").append(c.getNetPower()).append('/').append(c.getNetToughness());
        }
        if (c.isFaceDown()) {
            sb.append(":FD");
        }
        Card attached = c.getAttachedTo();
        if (attached != null) {
            sb.append(":on[").append(attached.getName()).append(']');
        }
        return sb.toString();
    }

    private static String countersString(com.google.common.collect.Multiset<CounterType> counters) {
        if (counters == null || counters.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (com.google.common.collect.Multiset.Entry<CounterType> e : counters.entrySet()) {
            parts.add(e.getElement() + "=" + e.getCount());
        }
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private static String name(Player p) {
        return p == null ? "null" : p.getName();
    }

    private static String outcomeString(Game g) {
        if (g.getOutcome() == null) {
            return "no_outcome";
        }
        String winner = g.getOutcome().isDraw() ? "draw"
                : g.getOutcome().getWinningLobbyPlayer().getName();
        return winner + "@turn" + g.getOutcome().getLastTurnNumber();
    }

    /** Short stable hash of a trace's turns strictly after fromTurn (for cross-run comparison). */
    private static String traceHash(Map<Integer, List<String>> trace, int fromTurn) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (Map.Entry<Integer, List<String>> e : trace.entrySet()) { // TreeMap: sorted
                if (e.getKey() <= fromTurn) {
                    continue;
                }
                md.update(String.valueOf(e.getKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                for (String s : e.getValue()) {
                    md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            StringBuilder sb = new StringBuilder();
            byte[] h = md.digest();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "hash_error";
        }
    }

    // ------------------------------------------------------------------
    // RNG snapshot (java.util.Random is Serializable)
    // ------------------------------------------------------------------
    private static byte[] snapshotRng() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(MyRandom.getRandom());
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("RNG snapshot failed", e);
        }
    }

    private static Random restoreRng(byte[] state) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(state))) {
            return (Random) ois.readObject();
        } catch (Exception e) {
            throw new RuntimeException("RNG restore failed", e);
        }
    }

    // ------------------------------------------------------------------
    // Per-game result record
    // ------------------------------------------------------------------
    private static final class GameResult {
        final long seed;
        int plannedForkTurn;
        int forkTurn = -1;
        String status; // clean | divergence | static_mismatch | outcome_mismatch | copy_crash | resume_crash | no_fork | main_crash_or_hang
        boolean staticMatch;
        long copyMs;
        int divergenceTurn = -1;
        String divergenceSample;
        String mainOutcome = "";
        String forkOutcome = "";
        String notes = "";
        String mode = "";
        int mainTurns;
        int forkTurns;
        String mainTraceHash = "";
        String forkTraceHash = "";
        // chain mode (F2 = copy of F1)
        String chainStatus;
        boolean chainStaticMatch;
        long copy2Ms;
        int chainDivergenceTurn = -1;
        String chainSample;
        String chainOutcome = "";
        String chainTraceHash = "";

        GameResult(long seed) {
            this.seed = seed;
        }

        String summaryLine() {
            return status + (divergenceTurn >= 0 ? "@turn" + divergenceTurn : "")
                    + (chainStatus != null ? " " + chainStatus + (chainDivergenceTurn >= 0 ? "@turn" + chainDivergenceTurn : "") : "")
                    + " copy=" + copyMs + "ms main=" + mainOutcome + " fork=" + forkOutcome;
        }

        String toJson() {
            return "{\"seed\":" + seed
                    + ",\"plannedForkTurn\":" + plannedForkTurn
                    + ",\"forkTurn\":" + forkTurn
                    + ",\"status\":\"" + status + '"'
                    + ",\"staticMatch\":" + staticMatch
                    + ",\"copyMs\":" + copyMs
                    + ",\"divergenceTurn\":" + divergenceTurn
                    + ",\"mainTurns\":" + mainTurns
                    + ",\"forkTurns\":" + forkTurns
                    + ",\"mainOutcome\":\"" + esc(mainOutcome) + '"'
                    + ",\"forkOutcome\":\"" + esc(forkOutcome) + '"'
                    + ",\"divergenceSample\":\"" + esc(divergenceSample) + '"'
                    + ",\"mainTraceHash\":\"" + mainTraceHash + '"'
                    + ",\"forkTraceHash\":\"" + forkTraceHash + '"'
                    + (chainStatus == null ? "" :
                        ",\"chainStatus\":\"" + chainStatus + '"'
                        + ",\"chainStaticMatch\":" + chainStaticMatch
                        + ",\"copy2Ms\":" + copy2Ms
                        + ",\"chainDivergenceTurn\":" + chainDivergenceTurn
                        + ",\"chainOutcome\":\"" + esc(chainOutcome) + '"'
                        + ",\"chainSample\":\"" + esc(chainSample) + '"'
                        + ",\"chainTraceHash\":\"" + chainTraceHash + '"')
                    + (mode.isEmpty() ? "" : ",\"mode\":\"" + mode + '"')
                    + ",\"notes\":\"" + esc(notes) + "\"}";
        }

        private static String esc(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "");
        }
    }
}
