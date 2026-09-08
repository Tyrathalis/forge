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
        if (quiescent) {
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

        public Pending(String rowPrefix, Consumer<String> sink) {
            this(rowPrefix, sink, null, null, Double.NaN, 1.0, 0L);
        }

        public Pending(String rowPrefix, Consumer<String> sink, String[] cands, double[] values,
                double bar, double temp, long sampleSeed) {
            this.rowPrefix = rowPrefix;
            this.sink = sink;
            this.cands = cands;
            this.values = values;
            this.bar = bar;
            this.temp = temp;
            this.sampleSeed = sampleSeed;
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
        }

        /** margin = max V − V(natural); margin ≥ bar → sample from the
         *  softmax of the valued candidates' leaf values at temp (temp ≤ 0 =
         *  argmax) with a private seeded RNG (never the game's stream). */
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
            double max = Double.NEGATIVE_INFINITY;
            int argmax = -1;
            for (int i = 0; i < values.length; i++) {
                if (!Double.isNaN(values[i]) && values[i] > max) {
                    max = values[i];
                    argmax = i;
                }
            }
            d.margin = max - values[d.natIdx];
            if (d.margin < bar) {
                return d;
            }
            d.p = new double[values.length];
            if (temp <= 0) {
                java.util.Arrays.fill(d.p, 0.0);
                d.p[argmax] = 1.0;
                d.actIdx = argmax;
            } else {
                double z = 0;
                for (int i = 0; i < values.length; i++) {
                    d.p[i] = Double.isNaN(values[i]) ? 0.0 : Math.exp((values[i] - max) / temp);
                    z += d.p[i];
                }
                for (int i = 0; i < values.length; i++) {
                    d.p[i] /= z;
                }
                // One splitmix64 step → a uniform in [0, 1): java.util.Random's
                // first output is poorly distributed over nearby seeds.
                double u = (mix64(sampleSeed) >>> 11) * 0x1.0p-53;
                double acc = 0;
                d.actIdx = argmax;
                for (int i = 0; i < values.length; i++) {
                    if (d.p[i] <= 0) {
                        continue;
                    }
                    acc += d.p[i];
                    if (u < acc) {
                        d.actIdx = i;
                        break;
                    }
                }
            }
            d.logp = Math.log(d.p[d.actIdx]);
            d.by = d.actIdx == d.natIdx ? "search_nat" : "search";
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
