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

        Surface(int kind, int ordinal, String label, int n, int min, int max, int[] natural, int[] aux) {
            this.kind = kind;
            this.ordinal = ordinal;
            this.label = label;
            this.n = n;
            this.min = min;
            this.max = max;
            this.natural = natural;
            this.aux = aux;
        }
    }

    /** Surface callbacks traced on the path from the forced option to the
     *  leaf, in order (Surfaces.trace); read by the monitor after the copy. */
    public final List<Surface> surfaces = Collections.synchronizedList(new java.util.ArrayList<>());
    private final int[] seenOfKind = new int[Surfaces.KIND_NAMES.length];

    void noteSurface(int kind, String label, int n, int min, int max, int[] natural, int[] aux) {
        int ord = seenOfKind[kind]++;
        if (surfaces.size() < 64) {
            surfaces.add(new Surface(kind, ord, label, n, min, max, natural, aux));
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

        public Pending(String rowPrefix, Consumer<String> sink) {
            this.rowPrefix = rowPrefix;
            this.sink = sink;
        }

        public void complete(String natural) {
            sink.accept(rowPrefix + ",\"nat\":" + Obs.q(natural) + "}");
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
