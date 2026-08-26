package forge.ai.anvil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Anvil M10 ceiling instrument (m10-ceiling-spec.md "Engine build owed"):
 * the within-turn -forceschedule directive. Armed per fork copy by the
 * AnvilRun sched-rollout mode, keyed on Game identity in a WeakHashMap
 * (the PayDirective/SeqDirective idiom — an abandoned hard-capped thread
 * must never consume a directive armed for a later copy).
 *
 * The directive owns the target seat's TARGET TURN: an ordered list of
 * schedule items (candidate labels in the Census.str sa_vocab basis — the
 * fork-9 pointer-over-candidates vocabulary; empty list = the hold-all
 * arm) is executed in order at the seat's priority windows, and in JOINT
 * mode every in-scope payManaCost window on that turn picks the
 * enumerated payment plan that maximizes feasibility of the REMAINING
 * scheduled items (tie-broken most-flexible-spare, then option order) —
 * knob (c) of the adjudicated pre-registration.
 *
 * Window rule (implemented by {@link #window}):
 *   1. Land-first: while the seat has not resolved its land question and
 *      a land option is present at a quiescent main-phase window, the ask
 *      is masked to the land options (forbid-decline; the policy picks
 *      WHICH land — per-window authority where the schedule is silent).
 *   2. Next item present in the options -> masked single-candidate
 *      forced ask (the policy supplies targets/X through the normal
 *      one-shot path — soft-emission semantics, never a synthetic cast).
 *   3. Next item ABSENT at a quiescent main-phase window -> DEGRADE to
 *      natural play for the remainder, counted (fork-5 divergence pin:
 *      degraded_at / degrade_why; the arm is VOID if nothing executed).
 *      Absent at a non-quiescent window (stack up, non-main phase) ->
 *      force-pass, counted as deferred (the schedule owns the turn).
 *   4. Schedule exhausted (or hold-all) -> force-pass the seat's
 *      remaining windows this turn (land rule 1 still applies first).
 *
 * All directive failures are counters/reason codes on this object,
 * NEVER exceptions into the game thread.
 */
public final class ScheduleDirective {

    // ---- job spec (immutable) ------------------------------------------
    final String playerName;
    final int turn;
    /** Ordered schedule item labels (Census.str basis); empty = hold-all. */
    final List<String> items;
    /** true = schedule-consistent directed payment (JOINT primary);
     *  false = auto-pay (the marginal-attribution stratum). */
    final boolean joint;

    // ---- trace (game thread writes, runner reads after completion) -----
    public volatile int executed = 0;
    /** Schedule index at degrade; -1 = never degraded. */
    public volatile int degradedAt = -1;
    public volatile String degradeWhy = null;
    /** Windows force-passed while the next item was absent at a
     *  non-actionable (non-quiescent / non-main) window. */
    public volatile int deferred = 0;
    /** Label of the land the policy played under rule 1; null = none. */
    public volatile String landPlayed = null;
    /** Per-item outcome trace: "ok:<label>" per executed item, then one
     *  terminal "degrade:<why>" if the arm degraded. */
    public final List<String> steps = Collections.synchronizedList(new ArrayList<>());
    // payment counters (joint mode; volatile ints written on the game thread only)
    public volatile int payWindows = 0;   // in-scope windows seen on the target turn
    public volatile int payDirected = 0;  // directed_ok executions
    public volatile int paySalvage = 0;
    public volatile int payFail = 0;      // directed but autoPay could not complete
    public volatile int payAuto = 0;      // non-consequential -> normal path
    public volatile int payCostmod = 0;   // cost-modified -> normal path (spec §12b)
    public volatile int payErr = 0;       // enumeration error -> normal path

    private int cursor = 0;
    private boolean landDone = false;
    private volatile boolean degraded = false;
    /** Window-transient: what the current masked ask is for. */
    private static final int ASK_NONE = 0, ASK_LAND = 1, ASK_ITEM = 2;
    private int pendingAsk = ASK_NONE;
    /** Last-seen mana cost per scheduled label (populated from live
     *  options as they appear) — the payment scorer's remaining-cost
     *  source. Labels never seen stay unknown and are simply not counted
     *  toward feasibility (deterministic, optimistic-consistent with the
     *  census conventions). */
    private final Map<String, ManaCost> costCache = new HashMap<>();

    private ScheduleDirective(String playerName, int turn, List<String> items, boolean joint) {
        this.playerName = playerName;
        this.turn = turn;
        this.items = items;
        this.joint = joint;
    }

    private static final Map<Game, ScheduleDirective> armed =
            Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static ScheduleDirective arm(Game g, String playerName, int turn,
            List<String> items, boolean joint) {
        ScheduleDirective d = new ScheduleDirective(playerName, turn,
                new ArrayList<>(items), joint);
        armed.put(g, d);
        return d;
    }

    /** Null when unarmed; trace fields live on the returned object. */
    public static ScheduleDirective directive(Game g) {
        return armed.get(g);
    }

    public static void clear(Game g) {
        armed.remove(g);
    }

    /** The seat's live schedule directive for the current cast window, or
     *  null (unarmed / other seat / wrong turn / already degraded). */
    public static ScheduleDirective active(Game g, Player p) {
        final ScheduleDirective d = armed.get(g);
        if (d == null || d.degraded || !d.playerName.equals(p.getName())) {
            return null;
        }
        try {
            if (g.getPhaseHandler().getTurn() != d.turn) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        return d;
    }

    /** The live directive for a payment window under JOINT mode, or null.
     *  Payment for scheduled item i fires after its cast window returns,
     *  same turn — the (seat, turn, !degraded) guard is identical. */
    public static ScheduleDirective paymentDirective(Game g, Player p) {
        final ScheduleDirective d = active(g, p);
        return d != null && d.joint ? d : null;
    }

    public boolean isDegraded() {
        return degraded;
    }

    public int scheduleSize() {
        return items.size();
    }

    /** VOID per the fork-5 pin: a non-empty schedule that executed
     *  nothing (hold-all arms execute nothing by design and never void). */
    public boolean isVoid() {
        return !items.isEmpty() && executed == 0;
    }

    // ------------------------------------------------------------------
    // Cast-window rule

    public static final int W_PASS = 0;     // force-pass this window
    public static final int W_NATURAL = 1;  // degraded: play free from here
    public static final int W_FORCE = 2;    // masked forbid-decline ask

    public static final class Window {
        public final int kind;
        public final List<SpellAbility> ask;

        Window(int kind, List<SpellAbility> ask) {
            this.kind = kind;
            this.ask = ask;
        }
    }

    /** Decide this window under the rule in the class doc. Called once per
     *  chooseSpellAbilityToPlay window by the controller; never throws. */
    public Window window(List<SpellAbility> options, boolean quiescentMain) {
        try {
            cacheCosts(options);
            // 1. land-first (quiescent main only — land drops are
            // sorcery-speed, and a non-quiescent land mask would be empty)
            if (!landDone && quiescentMain) {
                List<SpellAbility> lands = new ArrayList<>();
                for (SpellAbility sa : options) {
                    if (sa.isLandAbility()) {
                        lands.add(sa);
                    }
                }
                if (!lands.isEmpty()) {
                    pendingAsk = ASK_LAND;
                    return new Window(W_FORCE, lands);
                }
            }
            // 2./3. next scheduled item
            if (cursor < items.size()) {
                final String next = items.get(cursor);
                for (SpellAbility sa : options) {
                    if (next.equals(Census.str(sa))) {
                        pendingAsk = ASK_ITEM;
                        List<SpellAbility> one = new ArrayList<>(1);
                        one.add(sa);
                        return new Window(W_FORCE, one);
                    }
                }
                if (quiescentMain) {
                    degrade("absent");
                    return new Window(W_NATURAL, null);
                }
                deferred++;
                return new Window(W_PASS, null);
            }
            // 4. exhausted / hold-all
            return new Window(W_PASS, null);
        } catch (Exception e) {
            // the directive must never take down a window: fail open
            degrade("windowerr:" + e.getClass().getSimpleName());
            return new Window(W_NATURAL, null);
        }
    }

    /** A forced ask realized a cast (sa != null) or the server passed
     *  despite the mask (sa == null). */
    public void onCast(SpellAbility sa) {
        final int ask = pendingAsk;
        pendingAsk = ASK_NONE;
        if (ask == ASK_LAND) {
            landDone = true;
            if (sa != null) {
                landPlayed = Census.str(sa);
            }
            return;
        }
        if (sa == null) {
            degrade("pass_response");
            return;
        }
        steps.add("ok:" + Census.str(sa));
        executed++;
        cursor++;
    }

    /** The forced ask exhausted (veto chain / re-ask cap / M0 bridge).
     *  Land asks just settle the land question; item asks degrade. */
    public void onExhaust(String why) {
        final int ask = pendingAsk;
        pendingAsk = ASK_NONE;
        if (ask == ASK_LAND) {
            landDone = true;
            return;
        }
        degrade(why);
    }

    private void degrade(String why) {
        if (!degraded) {
            degraded = true;
            degradedAt = cursor;
            degradeWhy = why;
            steps.add("degrade:" + why);
        }
    }

    private void cacheCosts(List<SpellAbility> options) {
        for (int i = cursor; i < items.size(); i++) {
            final String label = items.get(i);
            if (costCache.containsKey(label)) {
                continue;
            }
            for (SpellAbility sa : options) {
                if (label.equals(Census.str(sa))) {
                    if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana() != null) {
                        costCache.put(label, sa.getPayCosts().getTotalMana());
                    }
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Schedule-consistent payment selection (knob c)

    /** Pick the option (0-based index into r.options) whose plan
     *  maximizes the number of REMAINING scheduled items still payable
     *  from the residual capacity after the plan, tie-broken by residual
     *  flexibility (sum of color-option bits over spare units), then by
     *  option order. Deterministic given the schedule; never throws to
     *  the caller beyond what enumerate already did.
     *
     *  The feasibility model deliberately mirrors the census/instrument
     *  optimism (X=0, phyrexian life-payable, snow untracked): residual
     *  units = untapped enumeration atoms outside the plan (chained atoms
     *  net of their activation cost, executor order) + floating pool
     *  minus the plan's pool spend; each remaining cost is checked in
     *  schedule order with a greedy least-flexible-unit-first matcher and
     *  consumes its units on success. */
    public int selectPlan(PaymentEnumerator.Result r, Player payer) {
        final List<ManaCost> rem = new ArrayList<>();
        for (int i = cursor; i < items.size(); i++) {
            final ManaCost c = costCache.get(items.get(i));
            if (c != null) {
                rem.add(c);
            }
        }
        int bestIdx = 0;
        int bestFeas = -1;
        long bestFlex = -1;
        for (int oi = 0; oi < r.options.size(); oi++) {
            final List<Unit> units = residualUnits(r, r.options.get(oi).plan, payer);
            long flex = 0;
            for (Unit u : units) {
                flex += Integer.bitCount(u.mask & 0xFF);
            }
            int feas = 0;
            for (ManaCost mc : rem) {
                if (tryPay(mc, units)) {
                    feas++;
                }
            }
            if (feas > bestFeas || (feas == bestFeas && flex > bestFlex)) {
                bestFeas = feas;
                bestFlex = flex;
                bestIdx = oi;
            }
        }
        return bestIdx;
    }

    private static final class Unit {
        final byte mask;
        boolean spent;

        Unit(byte mask) {
            this.mask = mask;
        }
    }

    /** Residual capacity after a plan: pool minus the plan's pool spend,
     *  plus every enumeration atom not in the plan — chained atoms only
     *  if their activation cost is payable from (and consumed out of)
     *  the residual built so far, in executor order. */
    private static List<Unit> residualUnits(PaymentEnumerator.Result r,
            PaymentEnumerator.PaymentClass pc, Player payer) {
        final List<Unit> units = new ArrayList<>();
        for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
            final byte t = ManaAtom.MANATYPES[i];
            int n = payer.getManaPool().getAmountOfColor(t) - pc.poolSpend[i];
            for (int u = 0; u < n; u++) {
                units.add(new Unit(t));
            }
        }
        final Set<PaymentEnumerator.Atom> used = new HashSet<>(pc.atoms);
        final List<PaymentEnumerator.Atom> chained = new ArrayList<>();
        for (PaymentEnumerator.Atom a : r.allAtoms) {
            if (used.contains(a)) {
                continue;
            }
            if (a.activationMana.isZero()) {
                for (byte m : a.unitMasks) {
                    units.add(new Unit(m));
                }
            } else {
                chained.add(a);
            }
        }
        chained.sort(PaymentEnumerator.EXEC_ORDER);
        for (PaymentEnumerator.Atom a : chained) {
            if (tryPay(a.activationMana, units)) {
                for (byte m : a.unitMasks) {
                    units.add(new Unit(m));
                }
            }
        }
        return units;
    }

    /** Greedy matcher: colored shards first (least-flexible matching unit
     *  each), then generic from the least-flexible leftovers. Phyrexian
     *  shards are life-payable (optimistic, the census convention);
     *  twobrid falls back to 2 generic when uncolorable. Consumes units
     *  on success; restores them on failure. */
    private static boolean tryPay(ManaCost cost, List<Unit> units) {
        if (cost == null || cost.isZero()) {
            return true;
        }
        final List<Unit> taken = new ArrayList<>();
        int generic = cost.getGenericCost();
        boolean ok = true;
        for (ManaCostShard s : cost) {
            if (s.isPhyrexian()) {
                continue;
            }
            Unit pick = null;
            for (Unit u : units) {
                if (u.spent || !colorPayable(s, u.mask)) {
                    continue;
                }
                if (pick == null
                        || Integer.bitCount(u.mask & 0xFF) < Integer.bitCount(pick.mask & 0xFF)) {
                    pick = u;
                }
            }
            if (pick != null) {
                pick.spent = true;
                taken.add(pick);
            } else if (s.isOr2Generic()) {
                generic += 2;
            } else {
                ok = false;
                break;
            }
        }
        while (ok && generic > 0) {
            Unit pick = null;
            for (Unit u : units) {
                if (u.spent) {
                    continue;
                }
                if (pick == null
                        || Integer.bitCount(u.mask & 0xFF) < Integer.bitCount(pick.mask & 0xFF)) {
                    pick = u;
                }
            }
            if (pick == null) {
                ok = false;
                break;
            }
            pick.spent = true;
            taken.add(pick);
            generic--;
        }
        if (!ok) {
            for (Unit u : taken) {
                u.spent = false;
            }
        }
        return ok;
    }

    private static boolean colorPayable(ManaCostShard s, byte mask) {
        for (byte c : ManaAtom.MANATYPES) {
            if ((mask & c) != 0 && s.canBePaidWithManaOfColor(c)) {
                return true;
            }
        }
        return false;
    }

    /** Compact per-arm trace for the labels row: the steps list joined
     *  with ';' (already jstr-escaped by the writer). */
    public String traceSummary() {
        synchronized (steps) {
            return String.join(";", steps);
        }
    }
}
