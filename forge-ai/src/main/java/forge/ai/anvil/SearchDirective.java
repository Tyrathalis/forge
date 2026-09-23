package forge.ai.anvil;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * M12 Build 0 (ADR-0101 §1, ADR-0102 fork J): the one-ply search copy's
 * directive. Armed on a GameCopier copy for the acting seat: at the copy's
 * FIRST priority window of that seat (the searched window itself — GameCopier
 * resumes at the active player's priority) it forces exactly one option: pass,
 * or a single SA matched by its Census.str label under a forbid-decline ask,
 * so the network still fills the plan (targets, X, modes, payment). Every later
 * window of the seat is natural play until the seat's next QUIESCENT priority
 * window (empty stack) — the leaf (fork A) — where the window's peek record
 * (with the copy session's history ring) is captured and the copy ends. Other
 * seats play naturally throughout. Inert unless armed; never on a mainline.
 * Mainline side (Pending): the searched window's row waits for the natural
 * pick, and — M12 Build 2 — carries the acting rule (margin bar, softmax
 * temperature) the controller applies after its own ask.
 */
public final class SearchDirective {
    public static final int W_PASS = 0, W_NATURAL = 1, W_FORCE = 2, W_LEAF = 3, W_VOID = 4;

    public static final class Window {
        public final int kind;
        public final List<SpellAbility> ask;

        Window(int kind, List<SpellAbility> ask) {
            this.kind = kind;
            this.ask = ask;
        }
    }

    final String playerName;
    /** null = the pass option. */
    public final String optionLabel;
    public volatile boolean applied = false;
    public volatile String leafPeek = null;
    /** "leaf" | "void" (the option was absent at apply); the driver adds
     *  end / timeout / crash. */
    public volatile String outcome = null;
    public volatile int seatWindows = 0;
    /** Evening 4 (ADR-0105): the END-OF-TURN leaf — when ≥ 0, a quiescent
     *  window of the seat inside this turn is natural play and the leaf is
     *  the seat's first quiescent window of a LATER turn (ADR-0098's eot
     *  horizon, where a payment's consequence — what stayed untapped — is
     *  visible). -1 = the next quiescent window (fork A, every other copy). */
    public volatile int leafAfterTurn = -1;
    /** ADR-0114 (the void-rescue instrument): on this copy the forced option
     *  is realized by the heuristic's planner even on a bridged seat
     *  (PlayerControllerAnvil.heuristicForce) — the plan it set (Obs.planJson)
     *  or the AI's refusal recorded here; and, on any copy, why the forced
     *  ask voided (the realizer's veto code, pass_masked, veto_cap,
     *  no_oneshot, heur_refuse). Recording only. */
    public volatile boolean heuristicForce = false;
    /** The certifier merge (09-23, ADR-0117): a REPLAY copy — at the seat's
     *  first window the copy runs the seat's own natural chooser (a heuristic
     *  seat: the AI's full decision, under the mainline's pre-decision RNG
     *  state) and verifies the pick is the stored option; a different pick
     *  voids the copy ("diverged"). The forced option is never re-approved
     *  through canPlaySa (whose chance-based checks re-roll — the 09-23
     *  smoke's heur_refuse class on matched picks). Bridged seats keep the
     *  forced path (the model's plan is deterministic). */
    public volatile boolean replayNatural = false;
    public volatile String voidReason = null;
    public volatile String plan = null;
    public volatile String refuse = null;

    /** M12 Build 3: one traced surface callback of the seat on this copy
     *  (after the forced option applied) — what the monitor may expand. */
    public static final class Surface {
        public final int kind;
        public final int ordinal;
        public final String label;
        public final int n;
        public final int min;
        public final int max;
        public final int[] natural;
        public final int[] aux;
        /** The callback allows an option more than once (mode allowRepeat). */
        public final boolean repeat;

        Surface(int kind, int ordinal, String label, int n, int min, int max, int[] natural, int[] aux,
                boolean repeat) {
            this.kind = kind;
            this.ordinal = ordinal;
            this.label = label;
            this.n = n;
            this.min = min;
            this.max = max;
            this.natural = natural;
            this.aux = aux;
            this.repeat = repeat;
        }
    }

    /** Surface callbacks traced on the path from the forced option to the
     *  leaf, in order (Surfaces.trace); read by the monitor after the copy. */
    public final List<Surface> surfaces = Collections.synchronizedList(new java.util.ArrayList<>());
    private final int[] seenOfKind = new int[Surfaces.KIND_NAMES.length];

    void noteSurface(int kind, String label, int n, int min, int max, int[] natural, int[] aux,
            boolean repeat) {
        int ord = seenOfKind[kind]++;
        if (surfaces.size() < 64) {
            surfaces.add(new Surface(kind, ord, label, n, min, max, natural, aux, repeat));
        }
    }

    private SearchDirective(String playerName, String optionLabel) {
        this.playerName = playerName;
        this.optionLabel = optionLabel;
    }

    private static final Map<Game, SearchDirective> armed =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static SearchDirective arm(Game copy, String playerName, String optionLabel) {
        SearchDirective d = new SearchDirective(playerName, optionLabel);
        armed.put(copy, d);
        return d;
    }

    public static SearchDirective directive(Game g) {
        return armed.get(g);
    }

    public static void clear(Game g) {
        armed.remove(g);
    }

    /** The seat's live search directive, or null (unarmed / other seat). */
    public static SearchDirective active(Game g, Player p) {
        final SearchDirective d = armed.get(g);
        return d != null && d.playerName.equals(p.getName()) ? d : null;
    }

    /** True for a search copy (a game armed by {@link #arm}); the mainline
     *  game never enters the map. Census rows on copies carry {@code copy:true}
     *  (09-21, ADR-0114 routed): the copies' forced asks are what made a
     *  searched arm's "mainline" veto rate unreadable (ADR-0112's banked 14%). */
    public static boolean isCopy(Game g) {
        return g != null && armed.containsKey(g);
    }

    /** The cast-window rule (see the class doc). */
    public Window window(List<SpellAbility> options, boolean quiescent) {
        return window(options, quiescent, Integer.MAX_VALUE);
    }

    /** @param turn the copy's current turn: under an end-of-turn leaf
     *              (leafAfterTurn ≥ 0) a quiescent window in a turn ≤ it is
     *              natural play, not the leaf */
    public Window window(List<SpellAbility> options, boolean quiescent, int turn) {
        seatWindows++;
        if (!applied) {
            applied = true;
            if (optionLabel == null) {
                return new Window(W_PASS, null);
            }
            if (replayNatural) {
                return new Window(W_FORCE, null); // the chooser decides; the controller verifies
            }
            for (SpellAbility sa : options) {
                if (optionLabel.equals(Census.str(sa))) {
                    return new Window(W_FORCE, new java.util.ArrayList<>(Collections.singletonList(sa)));
                }
            }
            outcome = "void";
            return new Window(W_VOID, null);
        }
        if (quiescent && !(leafAfterTurn >= 0 && turn <= leafAfterTurn)) {
            outcome = "leaf";
            return new Window(W_LEAF, null);
        }
        return new Window(W_NATURAL, null);
    }

    // ------------------------------------------------------------------
    // Mainline side: the searched window's row waits for the NATURAL pick
    // (what the policy did at that window), filled by the controller.

    public static final class Pending {
        final String rowPrefix;
        final Consumer<String> sink;
        /** M12 Build 2 acting rule (m12-plan canonical shape §2): the
         *  candidate labels (index 0 = pass, null) and their mean leaf values
         *  (NaN = unvalued: every roll void / crash / unserved); bar NaN =
         *  telemetry only (Build 0 behaviour). */
        public final String[] cands;
        public final double[] values;
        public final double bar;
        public final double temp;
        public final long sampleSeed;
        /** Evening 5 (ADR-0106 A): per candidate, the second round's answers
         *  on its path (null = not expanded / no surface / a kind not
         *  acted); the acting rule's second stage samples among them. */
        public final SurfAnswers[] surf;
        /** The partial-expansion slot (ADR-0106 C3): the deep round's runner
         *  (null = off), its set size B, the natural-margin band [lo, bar)
         *  that gates it, the floor rate on the other windows, and the bar
         *  the deep values act under. */
        public final DeepRound deep;
        public final int deepTop;
        public final double deepLo;
        public final double deepFloor;
        public final double deepBar;

        /** The partial-expansion slot's runner: re-expands the candidates in
         *  `set` (indices into cands; each with the answer index its stage-1
         *  sample chose on surf[c], or -1 = the natural answer) to the deep
         *  leaf under CRN and returns their mean deep values (NaN outside the
         *  set / unvalued) plus the row's "copies" fragment. */
        public interface DeepRound {
            Result run(int[] set, int[] ansIdx);

            final class Result {
                public final double[] v;
                public final String copies;

                public Result(double[] v, String copies) {
                    this.v = v;
                    this.copies = copies;
                }
            }
        }

        /** One expanded surface on a candidate's path: the enumerated
         *  answers with their mean leaf values (NaN = unvalued) and the
         *  natural answer's index among them (-1 = absent). */
        public static final class SurfAnswers {
            public final int kind;
            public final int ordinal;
            public final String label;
            public final int[][] answers;
            public final double[] values;
            public final int natIdx;

            public SurfAnswers(int kind, int ordinal, String label, int[][] answers, double[] values, int natIdx) {
                this.kind = kind;
                this.ordinal = ordinal;
                this.label = label;
                this.answers = answers;
                this.values = values;
                this.natIdx = natIdx;
            }
        }

        public Pending(String rowPrefix, Consumer<String> sink) {
            this(rowPrefix, sink, null, null, Double.NaN, 1.0, 0L, null);
        }

        public Pending(String rowPrefix, Consumer<String> sink, String[] cands, double[] values,
                double bar, double temp, long sampleSeed) {
            this(rowPrefix, sink, cands, values, bar, temp, sampleSeed, null);
        }

        public Pending(String rowPrefix, Consumer<String> sink, String[] cands, double[] values,
                double bar, double temp, long sampleSeed, SurfAnswers[] surf) {
            this(rowPrefix, sink, cands, values, bar, temp, sampleSeed, surf, null, 0, 0.0, 0.0, Double.NaN);
        }

        public Pending(String rowPrefix, Consumer<String> sink, String[] cands, double[] values,
                double bar, double temp, long sampleSeed, SurfAnswers[] surf, DeepRound deep, int deepTop,
                double deepLo, double deepFloor, double deepBar) {
            this.rowPrefix = rowPrefix;
            this.sink = sink;
            this.cands = cands;
            this.values = values;
            this.bar = bar;
            this.temp = temp;
            this.sampleSeed = sampleSeed;
            this.surf = surf;
            this.deep = deep;
            this.deepTop = deepTop;
            this.deepLo = deepLo;
            this.deepFloor = deepFloor;
            this.deepBar = Double.isNaN(deepBar) ? bar : deepBar;
        }

        public boolean acts() {
            return cands != null && values != null && !Double.isNaN(bar);
        }

        /** The acting rule's verdict at one window. by: natural (margin below
         *  the bar) | nat_unsearched (the natural pick is not a candidate —
         *  a mana ability or beyond the cap) | nat_unvalued (the natural's
         *  copies were all void) | search (a different candidate sampled) |
         *  search_nat (the natural itself sampled). */
        public static final class Decision {
            public int natIdx = -1;
            public int actIdx = -1;
            public double margin = Double.NaN;
            public double[] p = null;
            public double logp = Double.NaN;
            public String by = "natural";
            /** Evening 5: the answer stage on the ACTED option (the sampled
             *  option, else the natural). ansBy: off (no surface acting) |
             *  ans_unsearched (the acted option's path was not expanded) |
             *  ans_unvalued (its natural answer has no value) | natural
             *  (answer margin below the bar) | search_nat (the natural
             *  answer sampled) | search (another answer sampled → the
             *  mainline arms it). */
            public String ansBy = "off";
            public int ansCand = -1;
            public int ansIdx = -1;
            public int ansNatIdx = -1;
            public double ansMargin = Double.NaN;
            public double[] ansP = null;
            public double ansLogp = Double.NaN;
            /** The option values the option stage ran on: the first-ply
             *  values, each lifted to its sampled answer's value where the
             *  answer stage cleared the bar (null = no lift anywhere). */
            public double[] lifted = null;
            /** The partial-expansion slot (ADR-0106 C3). deepBy: off (no
             *  deep round) | single (one valued candidate — nothing to
             *  adjudicate) | gate (the shallow margin outside the band and
             *  the floor draw missed — the shallow rule decided) | band (the
             *  shallow margin in [lo, bar) — the deep round ran) | floor (the
             *  floor draw hit — it ran) | nat_unvalued (it ran; the natural's
             *  deep copies all void — the shallow rule decided). When it ran,
             *  `by` reads deep | deep_nat | natural on the DEEP values over
             *  the deep set (every other candidate pruned) and `margin` is
             *  the deep margin; shallowMargin / shallowArg keep the first
             *  ply's verdict for the allocation head's labels (fork L). */
            public String deepBy = "off";
            public int[] deepSet = null;
            public double[] deepV = null;
            public String deepCopies = null;
            public double shallowMargin = Double.NaN;
            public int shallowArg = -1;

            /** The surface answer to arm on the mainline, or null. */
            public SurfAnswers arm(Pending p) {
                return "search".equals(ansBy) && p.surf != null ? p.surf[ansCand] : null;
            }
        }

        /** The softmax sample over `v` at `temp` (temp ≤ 0 = argmax) with a
         *  private seeded RNG (never the game's stream); fills `p`; returns
         *  the sampled index. NaN entries carry no mass. */
        static int sample(double[] v, double max, int argmax, double temp, long seed, double[] p) {
            if (temp <= 0) {
                java.util.Arrays.fill(p, 0.0);
                p[argmax] = 1.0;
                return argmax;
            }
            double z = 0;
            for (int i = 0; i < v.length; i++) {
                p[i] = Double.isNaN(v[i]) ? 0.0 : Math.exp((v[i] - max) / temp);
                z += p[i];
            }
            for (int i = 0; i < v.length; i++) {
                p[i] /= z;
            }
            // One splitmix64 step → a uniform in [0, 1): java.util.Random's
            // first output is poorly distributed over nearby seeds.
            double u = (mix64(seed) >>> 11) * 0x1.0p-53;
            double acc = 0;
            int pick = argmax;
            for (int i = 0; i < v.length; i++) {
                if (p[i] <= 0) {
                    continue;
                }
                acc += p[i];
                if (u < acc) {
                    pick = i;
                    break;
                }
            }
            return pick;
        }

        /** The two-stage rule (ADR-0106 A, the bar-lifted joint pick).
         *  Stage 1 (answers, evening 5): for every expanded candidate whose
         *  natural answer is valued, answer margin = max V(answer) −
         *  V(natural answer); at or above the bar an answer is sampled from
         *  the answers' softmax at temp (its own seed stream) and the
         *  candidate's option value is LIFTED to that answer's value.
         *  Stage 2 (options, Build 2): margin = max V − V(natural) over the
         *  lifted values; margin ≥ bar → sample from the softmax of the
         *  valued candidates at temp, else the natural pick stands. The
         *  acted option's answer verdict is reported; a sampled non-natural
         *  answer is what the mainline arms. */
        public Decision decide(String natural) {
            Decision d = new Decision();
            for (int i = 0; i < cands.length; i++) {
                boolean hit = cands[i] == null ? "pass".equals(natural) : cands[i].equals(natural);
                if (hit) {
                    d.natIdx = i;
                    break;
                }
            }
            if (d.natIdx < 0) {
                d.by = "nat_unsearched";
                return d;
            }
            if (Double.isNaN(values[d.natIdx])) {
                d.by = "nat_unvalued";
                return d;
            }
            // ---- stage 1: the answers
            double[] v = values;
            int[] ansPick = null;
            double[][] ansP = null;
            double[] ansMargin = null;
            if (surf != null) {
                ansPick = new int[cands.length];
                ansP = new double[cands.length][];
                ansMargin = new double[cands.length];
                java.util.Arrays.fill(ansPick, -1);
                java.util.Arrays.fill(ansMargin, Double.NaN);
                for (int c = 0; c < cands.length; c++) {
                    SurfAnswers s = surf[c];
                    if (s == null || s.natIdx < 0 || Double.isNaN(s.values[s.natIdx])) {
                        continue;
                    }
                    double amax = Double.NEGATIVE_INFINITY;
                    int aarg = -1;
                    for (int a = 0; a < s.values.length; a++) {
                        if (!Double.isNaN(s.values[a]) && s.values[a] > amax) {
                            amax = s.values[a];
                            aarg = a;
                        }
                    }
                    ansMargin[c] = amax - s.values[s.natIdx];
                    if (ansMargin[c] < bar) {
                        continue;
                    }
                    double[] p = new double[s.values.length];
                    int pick = sample(s.values, amax, aarg, temp, sampleSeed ^ ((c + 1) * 0xD1B54A32D192ED03L), p);
                    ansPick[c] = pick;
                    ansP[c] = p;
                    if (v == values) {
                        v = values.clone();
                    }
                    v[c] = s.values[pick];
                }
                if (v != values) {
                    d.lifted = v;
                }
            }
            // ---- stage 2: the options
            double max = Double.NEGATIVE_INFINITY;
            int argmax = -1;
            int nValued = 0;
            for (int i = 0; i < v.length; i++) {
                if (!Double.isNaN(v[i])) {
                    nValued++;
                    if (v[i] > max) {
                        max = v[i];
                        argmax = i;
                    }
                }
            }
            d.margin = max - v[d.natIdx];
            double actBar = bar;
            // ---- the deep stage (ADR-0106 C3, the partial-expansion slot):
            // where the shallow margin sits in [lo, bar) — the first ply sees
            // something but not enough to act on — or on a seeded floor draw,
            // the natural ∪ the top-B (by the lifted first-ply value) are
            // re-expanded to the deep leaf; the option stage then runs on the
            // deep values over that set only (the rest pruned) under the deep
            // bar. Outside the band the shallow rule decides as before.
            if (deep != null) {
                d.shallowMargin = d.margin;
                d.shallowArg = argmax;
                if (nValued < 2) {
                    d.deepBy = "single";
                } else {
                    boolean band = d.margin >= deepLo && d.margin < bar;
                    boolean floor = !band && deepFloor > 0
                            && ((mix64(sampleSeed ^ 0xDEEBL) >>> 11) * 0x1.0p-53) < deepFloor;
                    if (!band && !floor) {
                        d.deepBy = "gate";
                    } else {
                        d.deepBy = band ? "band" : "floor";
                        // the deep set: the top-B valued candidates by the
                        // lifted first-ply value, plus the natural when it
                        // ranks outside them (B or B + 1 candidates)
                        java.util.List<Integer> order = new java.util.ArrayList<>();
                        for (int i = 0; i < v.length; i++) {
                            if (!Double.isNaN(v[i])) {
                                order.add(i);
                            }
                        }
                        final double[] fv = v;
                        order.sort((a, b) -> Double.compare(fv[b], fv[a]));
                        java.util.TreeSet<Integer> setIdx = new java.util.TreeSet<>();
                        for (int i = 0; i < order.size() && i < Math.max(1, deepTop); i++) {
                            setIdx.add(order.get(i));
                        }
                        setIdx.add(d.natIdx);
                        int[] set = new int[setIdx.size()];
                        int si = 0;
                        for (int i : setIdx) {
                            set[si++] = i;
                        }
                        int[] ans = new int[v.length];
                        java.util.Arrays.fill(ans, -1);
                        if (ansPick != null) {
                            for (int c = 0; c < v.length; c++) {
                                if (ansPick[c] >= 0 && surf[c] != null && ansPick[c] != surf[c].natIdx) {
                                    ans[c] = ansPick[c];
                                }
                            }
                        }
                        DeepRound.Result res = deep.run(set, ans);
                        d.deepSet = set;
                        d.deepV = res.v;
                        d.deepCopies = res.copies;
                        if (res.v == null || Double.isNaN(res.v[d.natIdx])) {
                            d.deepBy = "nat_unvalued";
                        } else {
                            double[] dv = new double[v.length];
                            java.util.Arrays.fill(dv, Double.NaN);
                            max = Double.NEGATIVE_INFINITY;
                            argmax = -1;
                            for (int i : set) {
                                dv[i] = res.v[i];
                                if (!Double.isNaN(dv[i]) && dv[i] > max) {
                                    max = dv[i];
                                    argmax = i;
                                }
                            }
                            v = dv;
                            d.margin = max - v[d.natIdx];
                            actBar = deepBar;
                        }
                    }
                }
            }
            final boolean deepRan = "band".equals(d.deepBy) || "floor".equals(d.deepBy);
            if (d.margin >= actBar) {
                d.p = new double[v.length];
                d.actIdx = sample(v, max, argmax, temp, sampleSeed, d.p);
                d.logp = Math.log(d.p[d.actIdx]);
                d.by = d.actIdx == d.natIdx ? (deepRan ? "deep_nat" : "search_nat") : (deepRan ? "deep" : "search");
            }
            // ---- the acted option's answer verdict
            if (surf != null) {
                int o = d.actIdx >= 0 ? d.actIdx : d.natIdx;
                SurfAnswers s = surf[o];
                d.ansCand = o;
                if (s == null) {
                    d.ansBy = "ans_unsearched";
                } else if (s.natIdx < 0 || Double.isNaN(s.values[s.natIdx])) {
                    d.ansBy = "ans_unvalued";
                } else {
                    d.ansNatIdx = s.natIdx;
                    d.ansMargin = ansMargin[o];
                    if (ansPick[o] < 0) {
                        d.ansBy = "natural";
                    } else {
                        d.ansIdx = ansPick[o];
                        d.ansP = ansP[o];
                        d.ansLogp = Math.log(d.ansP[d.ansIdx]);
                        d.ansBy = d.ansIdx == s.natIdx ? "search_nat" : "search";
                    }
                }
            }
            return d;
        }

        private static long mix64(long z) {
            z += 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }

        public void complete(String natural) {
            complete(natural, null, null);
        }

        /** applied: what the mainline did with the verdict — natural | act
         *  (the sampled option realized) | pass (the sampled pass) |
         *  act_void (the sampled option could not be applied; the natural
         *  line played). */
        public void complete(String natural, Decision d, String applied) {
            complete(natural, d, applied, null);
        }

        /** @param actVoid on act_void, why the sampled option could not be
         *                 applied on the mainline (no_option | no_plan | the
         *                 realizer's veto code | pass | heur_refuse); recorded
         *                 as {@code act_vr} (09-21, ADR-0114 routed) */
        public void complete(String natural, Decision d, String applied, String actVoid) {
            StringBuilder sb = new StringBuilder(rowPrefix.length() + 256);
            sb.append(rowPrefix).append(",\"nat\":").append(Obs.q(natural));
            if (d != null) {
                sb.append(",\"by\":\"").append(d.by).append('"');
                if (!Double.isNaN(d.margin)) {
                    sb.append(",\"margin\":").append(String.format(java.util.Locale.ROOT, "%.5f", d.margin));
                }
                if (d.actIdx >= 0) {
                    sb.append(",\"act_o\":").append(d.actIdx)
                            .append(",\"act\":").append(Obs.q(cands[d.actIdx] == null ? "pass" : cands[d.actIdx]))
                            .append(",\"logp\":").append(String.format(java.util.Locale.ROOT, "%.4f", d.logp))
                            .append(",\"p\":[");
                    for (int i = 0; i < d.p.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(String.format(java.util.Locale.ROOT, "%.4f", d.p[i]));
                    }
                    sb.append(']');
                }
                if (applied != null) {
                    sb.append(",\"applied\":\"").append(applied).append('"');
                }
                if (actVoid != null) {
                    sb.append(",\"act_vr\":\"").append(actVoid).append('"');
                }
                if (d.lifted != null) {
                    sb.append(",\"lifted\":[");
                    for (int i = 0; i < d.lifted.length; i++) {
                        if (i > 0) {
                            sb.append(',');
                        }
                        sb.append(Double.isNaN(d.lifted[i]) ? "null"
                                : String.format(java.util.Locale.ROOT, "%.5f", d.lifted[i]));
                    }
                    sb.append(']');
                }
                if (!"off".equals(d.deepBy)) {
                    // the partial-expansion slot: the gate's verdict, the
                    // first ply's margin + argmax, the deep set + values, the
                    // deep copies (per set candidate: leaf, v, kind, calls,
                    // snap, ms)
                    sb.append(",\"deep\":{\"by\":\"").append(d.deepBy).append('"')
                            .append(",\"shallow_margin\":").append(Double.isNaN(d.shallowMargin) ? "null"
                                    : String.format(java.util.Locale.ROOT, "%.5f", d.shallowMargin))
                            .append(",\"shallow_arg\":").append(d.shallowArg);
                    if (d.deepSet != null) {
                        sb.append(",\"set\":[");
                        for (int i = 0; i < d.deepSet.length; i++) {
                            if (i > 0) {
                                sb.append(',');
                            }
                            sb.append(d.deepSet[i]);
                        }
                        sb.append("],\"v\":[");
                        for (int i = 0; i < d.deepSet.length; i++) {
                            if (i > 0) {
                                sb.append(',');
                            }
                            double dv = d.deepV == null ? Double.NaN : d.deepV[d.deepSet[i]];
                            sb.append(Double.isNaN(dv) ? "null" : String.format(java.util.Locale.ROOT, "%.5f", dv));
                        }
                        sb.append(']');
                    }
                    if (d.deepCopies != null) {
                        sb.append(",\"copies\":").append(d.deepCopies);
                    }
                    sb.append('}');
                }
                if (!"off".equals(d.ansBy)) {
                    // evening 5: the answer stage on the acted option
                    sb.append(",\"ans\":{\"o\":").append(d.ansCand)
                            .append(",\"by\":\"").append(d.ansBy).append('"');
                    SurfAnswers s = surf != null && d.ansCand >= 0 ? surf[d.ansCand] : null;
                    if (s != null) {
                        sb.append(",\"kind\":\"").append(Surfaces.KIND_NAMES[s.kind]).append('"')
                                .append(",\"ord\":").append(s.ordinal)
                                .append(",\"nat_i\":").append(s.natIdx);
                    }
                    if (!Double.isNaN(d.ansMargin)) {
                        sb.append(",\"margin\":").append(String.format(java.util.Locale.ROOT, "%.5f", d.ansMargin));
                    }
                    if (d.ansIdx >= 0 && s != null) {
                        sb.append(",\"a_i\":").append(d.ansIdx).append(",\"a\":[");
                        int[] a = s.answers[d.ansIdx];
                        for (int i = 0; i < a.length; i++) {
                            if (i > 0) {
                                sb.append(',');
                            }
                            sb.append(a[i]);
                        }
                        sb.append("],\"logp\":").append(String.format(java.util.Locale.ROOT, "%.4f", d.ansLogp))
                                .append(",\"p\":[");
                        for (int i = 0; i < d.ansP.length; i++) {
                            if (i > 0) {
                                sb.append(',');
                            }
                            sb.append(String.format(java.util.Locale.ROOT, "%.4f", d.ansP[i]));
                        }
                        sb.append(']');
                    }
                    sb.append('}');
                }
            }
            sb.append('}');
            if (sink != null) {
                sink.accept(sb.toString());
            }
        }
    }

    private static final Map<Game, Pending> pending =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static void expectNatural(Game g, Pending p) {
        pending.put(g, p);
    }

    public static Pending takePending(Game g) {
        return pending.remove(g);
    }
}
