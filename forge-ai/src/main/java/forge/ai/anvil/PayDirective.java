package forge.ai.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 rung 3 (m9-rung3-draft.md): per-Game payment directive for the
 * drill-certification harness. Clones the PlayerControllerAnvil
 * Forced/SeqDirective idiom: armed by the runner before the game starts,
 * consulted inside PaymentTelemetry.rec at every in-scope payManaCost
 * window, keyed on Game identity in a WeakHashMap (an abandoned
 * hard-capped thread must never consume a directive armed for a later
 * game; dead games must not pin entries).
 *
 * Target window = the ordinal-th (0-based) in-scope window matching
 * (player, turn, sa-string-contains). Cost-modified windows count toward
 * the ordinal (they are census payManaCost rows) but cannot execute a
 * pick — spec §12b keeps them out of enumeration.
 *
 * Pick semantics mirror PlayerControllerAnvil.payManaCost's bridged path:
 * pick 0 = auto (the directive touches NOTHING — arm 0 is byte-identical
 * to unarmed telemetry play); pick in 1..|options| = executeDirected
 * floats the plan and the census controller's normal (heuristic) payment
 * completes from the float, pool-first; pick > |options| = no_such_option,
 * heuristic pays. Directed failures are reason codes on the outcome,
 * NEVER exceptions into the game thread.
 *
 * Determinization (reshuffleSeed != 0, i.e. roll > 0): both libraries are
 * reshuffled with the per-roll seed AT the matched window, not at game
 * start — the prefix trajectory stays census-identical across every
 * (arm, roll), and the K completions average over unseen library order
 * (the AnvilRun rollout-reshuffle rationale; Zone.setCards, so no shuffle
 * events/triggers fire). Roll 0 carries seed 0 = the true continuation.
 */
public final class PayDirective {

    // ---- job spec (immutable) ------------------------------------------
    final String playerName;
    final int turn;
    final String saSubstring;
    final int ordinal;
    final int pick;
    final long reshuffleSeed;

    // ---- outcome (game thread writes, runner reads after game end) -----
    /** Target window was reached (t_fired/reshuffle happened). */
    public volatile boolean resolved = false;
    /** The armed pick applied at the window (auto counts as applied). */
    public volatile boolean fired = false;
    /** Only when !fired: no_such_option / costmod / enumerr:* / never_fired. */
    public volatile String reason = null;
    /** auto | directed_ok | directed_salvage | directed_fail. */
    public volatile String exec = null;
    /** Salvage failure point ("canplay:"/"costs:" + host#id@atomIdx); null unless salvage. */
    public volatile String execWhy = null;
    /** Executor-order plan dump (PaymentEnumerator.describePlan); null unless salvage. */
    public volatile String planDesc = null;
    public volatile int tFired = -1;
    /** Options the enumerator surfaced at the window; -1 = never reached. */
    public volatile int availOptions = -1;
    /** Chosen option's goal names / kind codes (pick in 1..|options| only). */
    public volatile List<String> goals = null;
    public volatile List<Integer> kinds = null;

    private int seen = 0;
    private boolean decided = false;

    private PayDirective(String playerName, int turn, String saSubstring, int ordinal, int pick,
            long reshuffleSeed) {
        this.playerName = playerName;
        this.turn = turn;
        this.saSubstring = saSubstring;
        this.ordinal = ordinal;
        this.pick = pick;
        this.reshuffleSeed = reshuffleSeed;
    }

    private static final java.util.Map<Game, PayDirective> armed =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static PayDirective armPayDirective(Game g, String playerName, int turn,
            String saSubstring, int ordinal, int pick) {
        return armPayDirective(g, playerName, turn, saSubstring, ordinal, pick, 0L);
    }

    public static PayDirective armPayDirective(Game g, String playerName, int turn,
            String saSubstring, int ordinal, int pick, long reshuffleSeed) {
        PayDirective d = new PayDirective(playerName, turn, saSubstring, ordinal, pick, reshuffleSeed);
        armed.put(g, d);
        return d;
    }

    /** Null when unarmed; outcome fields live on the returned object. */
    public static PayDirective directive(Game g) {
        return armed.get(g);
    }

    public static void clear(Game g) {
        armed.remove(g);
    }

    /** The row's reason field (only meaningful when !fired). */
    public String resolvedReason() {
        return reason != null ? reason : (resolved ? "unresolved" : "never_fired");
    }

    /** Census-record rider: what the directive did at this window. */
    String summary() {
        return exec != null ? exec : "miss:" + resolvedReason();
    }

    /**
     * Target-window gate, called from PaymentTelemetry.rec at every
     * in-scope window. Returns the directive iff THIS window is the
     * target, and marks it reached (tFired + the roll>0 reshuffle happen
     * here, before any costmod/enumeration branching, so determinization
     * is identical across arms). Never throws into the game thread.
     */
    static PayDirective match(Game g, Player p, SpellAbility sa) {
        final PayDirective d = armed.get(g);
        if (d == null || d.resolved) {
            return null;
        }
        try {
            if (!d.playerName.equals(p.getName())) {
                return null;
            }
            final int t = g.getPhaseHandler().getTurn();
            if (t != d.turn) {
                return null;
            }
            final String s = Census.str(sa);
            if (s == null || !s.contains(d.saSubstring)) {
                return null;
            }
            if (d.seen++ != d.ordinal) {
                return null;
            }
            d.resolved = true;
            d.tFired = t;
            if (d.reshuffleSeed != 0) {
                d.reshuffle(g);
            }
            return d;
        } catch (Exception e) {
            return null;
        }
    }

    private void reshuffle(Game g) {
        final Random rng = new Random(reshuffleSeed);
        for (final Player q : g.getPlayers()) {
            final List<Card> lib = new ArrayList<>();
            for (final Card c : q.getZone(ZoneType.Library)) {
                lib.add(c);
            }
            Collections.shuffle(lib, rng);
            q.getZone(ZoneType.Library).setCards(lib);
        }
    }

    /** Matched window turned out cost-modified: no enumeration, no pick. */
    void resolveCostmod() {
        if (!decided) {
            decided = true;
            fired = false;
            reason = "costmod";
        }
    }

    /** Enumeration path threw before the pick could apply. */
    void resolveError(String why) {
        if (!decided) {
            decided = true;
            fired = false;
            reason = why;
        }
    }

    /** Apply the armed pick at the matched window. Never throws. */
    void resolve(Player p, PaymentEnumerator.Result r) {
        if (decided) {
            return;
        }
        decided = true;
        availOptions = r.options.size();
        if (pick == 0) {
            fired = true;
            exec = "auto";
            return;
        }
        if (pick > r.options.size()) {
            fired = false;
            reason = "no_such_option";
            return;
        }
        final PaymentEnumerator.GoalOption opt = r.options.get(pick - 1);
        goals = new ArrayList<>(opt.goals);
        kinds = new ArrayList<>(opt.kinds);
        try {
            final StringBuilder why = new StringBuilder();
            final PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, opt.plan, why);
            fired = true;
            exec = out == PaymentEnumerator.ExecOutcome.DIRECTED_OK ? "directed_ok" : "directed_salvage";
            execWhy = why.length() > 0 ? why.toString() : null;
            if (out == PaymentEnumerator.ExecOutcome.DIRECTED_SALVAGE) {
                try {
                    planDesc = PaymentEnumerator.describePlan(opt.plan);
                } catch (Exception e) {
                    planDesc = null; // diagnosis channel must never take down the row
                }
            }
        } catch (Exception e) {
            // partial float stays available to the heuristic completion;
            // recorded, never thrown (the payManaCost bridged-path rule)
            fired = true;
            exec = "directed_fail";
        }
    }
}
