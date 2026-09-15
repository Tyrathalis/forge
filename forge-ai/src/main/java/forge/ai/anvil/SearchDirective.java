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
    final String optionLabel;
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
            this.rowPrefix = rowPrefix;
            this.sink = sink;
            this.cands = cands;
            this.values = values;
            this.bar = bar;
            this.temp = temp;
            this.sampleSeed = sampleSeed;
            this.surf = surf;
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
            for (int i = 0; i < v.length; i++) {
                if (!Double.isNaN(v[i]) && v[i] > max) {
                    max = v[i];
                    argmax = i;
                }
            }
            d.margin = max - v[d.natIdx];
            if (d.margin >= bar) {
                d.p = new double[v.length];
                d.actIdx = sample(v, max, argmax, temp, sampleSeed, d.p);
                d.logp = Math.log(d.p[d.actIdx]);
                d.by = d.actIdx == d.natIdx ? "search_nat" : "search";
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
