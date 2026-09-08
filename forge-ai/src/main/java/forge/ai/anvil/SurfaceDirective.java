package forge.ai.anvil;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import forge.game.Game;
import forge.game.player.Player;

/**
 * M12 Build 3 (m12-plan §6, ADR-0103): the surface answer forced on a search
 * copy. Armed per copy (Game identity, WeakHashMap — the directive idiom)
 * for one seat, one surface KIND and one ORDINAL: the ordinal-th callback of
 * that kind the seat answers on the copy (0-based, counted from the copy's
 * start) returns the directive's index answer instead of the heuristic's.
 * Every other surface callback plays natural. Rides alongside the
 * {@link SearchDirective} that forced the priority option (different hook
 * points, so the one-genre-per-copy rule is untouched). Inert unless armed;
 * never on a mainline. Failures are counters, never exceptions.
 */
public final class SurfaceDirective {

    final String playerName;
    public final int kind;
    public final int ordinal;
    public final int[] answer;

    public volatile boolean fired = false;
    /** Fired-with-miss reason (idx / sum / neg); null = clean. */
    public volatile String miss = null;
    /** Callbacks of this kind seen for the seat (fired or not). */
    public volatile int seen = 0;
    /** Option count at the fired window; -1 = never fired. */
    public volatile int ncand = -1;
    /** Evening 2 (ADR-0105): the fired callback's dec record — options, obs
     *  snapshot, history, the wire shape — captured on the copy by
     *  Surfaces.dec just before the answer applies: the sub row's frame, so
     *  the distillation term sees the state the answer was chosen in. Null
     *  until fired (a miss still carries it). */
    public volatile String frame = null;
    volatile String pendingFrame = null;

    private SurfaceDirective(String playerName, int kind, int ordinal, int[] answer) {
        this.playerName = playerName;
        this.kind = kind;
        this.ordinal = ordinal;
        this.answer = answer == null ? null : answer.clone();
    }

    private static final Map<Game, SurfaceDirective> armed =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static SurfaceDirective arm(Game copy, String playerName, int kind, int ordinal, int[] answer) {
        SurfaceDirective d = new SurfaceDirective(playerName, kind, ordinal, answer);
        armed.put(copy, d);
        return d;
    }

    public static SurfaceDirective directive(Game g) {
        return armed.get(g);
    }

    public static void clear(Game g) {
        armed.remove(g);
    }

    /** The directive if this callback is the one it targets (seat + kind +
     *  ordinal, not yet fired); null otherwise. Counts every callback of
     *  the kind for the seat. */
    static SurfaceDirective match(Game g, Player p, int kind, int n, int max) {
        final SurfaceDirective d = armed.get(g);
        if (d == null || d.kind != kind || !d.playerName.equals(p.getName())) {
            return null;
        }
        if (!Surfaces.nontrivial(kind, n, max)) {
            return null; // the same rule as the trace: trivial callbacks have no ordinal
        }
        // Ordinals count from the forced priority option onward, exactly as
        // the trace does (Surfaces.trace gates on SearchDirective.applied).
        final SearchDirective sr = SearchDirective.active(g, p);
        if (sr == null || !sr.applied) {
            return null;
        }
        int k = d.seen++;
        if (d.fired || k != d.ordinal) {
            return null;
        }
        d.frame = d.pendingFrame;
        d.pendingFrame = null;
        return d;
    }

    /** The directive whose next matching callback of this kind fires (seat +
     *  kind, the ordinal reached, not yet fired); null otherwise. Read by
     *  Surfaces.dec, which runs before the force hook, to stash the frame. */
    static SurfaceDirective pending(Game g, Player p, int kind) {
        final SurfaceDirective d = armed.get(g);
        if (d == null || d.kind != kind || d.fired || d.seen != d.ordinal || !d.playerName.equals(p.getName())) {
            return null;
        }
        return d;
    }

    void fired(int n) {
        fired = true;
        ncand = n;
    }

    void miss(String why) {
        fired = true;
        miss = why;
    }

    @Override
    public String toString() {
        return "SurfaceDirective[" + Surfaces.KIND_NAMES[kind] + " #" + ordinal + " " + Arrays.toString(answer) + "]";
    }
}
