package forge.ai.anvil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import forge.ai.AiCostDecision;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPayment;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 D3 (§3c): legality-derived payment-class enumeration +
 * directed float-then-apply executor.
 *
 * Design: docs/design/m9-payment-surface-spec.md (Anvil repo, pinned
 * 2026-08-19); capability audit ADR-0065.
 *
 * HARD RULE (the interface trap, ADR-0065 finding 5): enumeration
 * builds from Card.getManaAbilities() + canPlay() directly.
 * ComputerUtilMana.getAIPlayableMana is NEVER called here — it is
 * auto-payer-derived filtering (it drops mana abilities that cost mana,
 * i.e. exactly the chained-activation class this surface exists to
 * expose).
 */
public final class PaymentEnumerator {

    /**
     * Pre-D4 revisit (spec §12, 2026-08-19): the decision object is a
     * preservation GOAL, not a composition. The census truncation gate fired
     * (0.3911 vs 0.05) and the tail probe measured cap-raising out
     * (consequential p90 = 55 classes, tail past 64) with the explosion
     * being assignment combinatorics over ≤11 source classes — so options
     * now scale with source classes: spare(k) per class + min_life on
     * phyrexian costs, per-goal argmax compositions, outcome-deduped.
     */
    /** Defensive cap on the surfaced option list (incl. auto). Expected never hit (≤11 source classes measured); logged, never silent. */
    public static final int GOAL_MAX = 16;
    /** DFS node budget — the only enumeration bound. Re-pinned 200k→2M at the
     *  paygoals census read (2026-08-19): the 1% nodecap gate fired at 1.25%
     *  (141 late-game monster boards, atoms p50 16 / p90 36); on the goal
     *  surface a nodecap is a degraded REPRESENTATIVE, never a censored
     *  option list — every goal still surfaces a plan (capped windows still
     *  carried 6–7 options) — but the gate is honored by measurement, not
     *  reinterpretation. */
    public static final int NODE_BUDGET = 2000000;
    /** Plan size cap: at most (shard count + PLAN_SLACK) activations. */
    public static final int PLAN_SLACK = 2;

    private PaymentEnumerator() { }

    /** Directed-execution activation order — the ONE definition shared by
     *  executeDirected and chainOrderFeasible (2026-08-21 salvage fix):
     *  the feasibility check replays exactly this order, so the two must
     *  never diverge. */
    static final Comparator<Atom> EXEC_ORDER =
            Comparator.comparingInt((Atom a) -> a.activationMana.getCMC())
                    .thenComparingInt(a -> a.host.getId());

    // ------------------------------------------------------------------
    // Data model

    /** One (card, mana ability) activation candidate. */
    public static final class Atom {
        public final Card host;
        public final SpellAbility ma;
        /** Predicted units produced (includes TapsForMana boosts via predictManafromSpellAbility). */
        public final int yield;
        /** Per produced unit: mask of colors this unit can be (fixed producers: exact bit; combo/any: option mask). */
        public final byte[] unitMasks;
        /** Mana part of the activation cost (never null; may be zero) — nonzero = chained activation. */
        public final ManaCost activationMana;
        public final String classKey;

        Atom(Card host, SpellAbility ma, int yield, byte[] unitMasks, ManaCost activationMana, String classKey) {
            this.host = host;
            this.ma = ma;
            this.yield = yield;
            this.unitMasks = unitMasks;
            this.activationMana = activationMana;
            this.classKey = classKey;
        }
    }

    /** One enumerated payment class = the wire option. */
    public static final class PaymentClass {
        /** (multiset of source classes, pool spend, phyrexian life) — the equivalence key. */
        public final String key;
        public final Map<String, Integer> classCounts;
        /** Representative concrete plan: atoms (deterministic pick) with per-unit color assignments. */
        public final List<Atom> atoms;
        public final Map<Atom, byte[]> unitColors;
        /** Pool spend per ManaAtom.MANATYPES index. */
        public final int[] poolSpend;
        public final int phyrexianLife;

        PaymentClass(String key, Map<String, Integer> classCounts, List<Atom> atoms,
                Map<Atom, byte[]> unitColors, int[] poolSpend, int phyrexianLife) {
            this.key = key;
            this.classCounts = classCounts;
            this.atoms = atoms;
            this.unitColors = unitColors;
            this.poolSpend = poolSpend;
            this.phyrexianLife = phyrexianLife;
        }
    }

    /** One surfaced wire option: the goals that induced it (outcome-deduped)
     *  and the representative composition the executor runs. `kinds` are the
     *  goal-kind codes the obs label ships as "gk" (rung 3: the pointer key
     *  carries no label text, so the model reads goal semantics through this
     *  small vocab — MUST match anvil.training.dataset.PAY_KINDS):
     *  0=pay 1=spare_creature 2=spare_land 3=spare_artifact 4=spare_other
     *  5=min_life. */
    public static final class GoalOption {
        public final List<String> goals;
        public final List<Integer> kinds;
        public final PaymentClass plan;

        GoalOption(List<String> goals, List<Integer> kinds, PaymentClass plan) {
            this.goals = goals;
            this.kinds = kinds;
            this.plan = plan;
        }
    }

    public static final class Result {
        /** Outcome-distinct goal options (spec §12a); the wire list is {auto} ∪ these. */
        public final List<GoalOption> options = new ArrayList<>();
        /** Distinct feasible compositions found (node-budget-bounded, uncensored). */
        public int planCount = 0;
        public boolean truncated = false;
        /** Truncation cause split: GOAL_MAX (expected ~never) vs node budget. */
        public boolean goalCapHit = false;
        public boolean nodeCapHit = false;
        public int nodesVisited = 0;
        public int atomCount = 0;
        public int sourceClassCount = 0;
    }

    /**
     * The auto-payability probe (the §4-amendment forced channel input).
     * Auto-payer-derived but used only to WIDEN the surface — never to
     * filter options — so the interface-trap direction is safe.
     */
    public static boolean autoPayable(final Player p, final SpellAbility sa,
            final ManaCost toPay, final boolean effect) {
        return ComputerUtilMana.canPayManaCost(new forge.game.mana.ManaCostBeingPaid(toPay), sa, p, effect);
    }

    /**
     * The consequential flag (spec §4 as amended, restated over goals at
     * §12a): ≥2 outcome-distinct options = a real choice — OR at least one
     * feasible plan the auto-payer cannot construct (the FORCED window; the
     * I+I+Signet motivating board). Callers gate costmod windows out BEFORE
     * this (spec §12b) — cost-modified traffic never reaches the flag.
     * Day-zero bit-identity holds: auto is option 0 and the auto-biased
     * init answers it.
     */
    public static boolean consequential(final Result r, final boolean autoPayable) {
        return r.options.size() >= 2 || (r.planCount >= 1 && !autoPayable);
    }

    /**
     * Cost-modified detection, static prong (spec §12b): the adjusted cost
     * is not determined until payment (delve exile choices), so this flags
     * MECHANISM PRESENCE — reduction-side keywords on the host, or any
     * ReduceCost static in play. Over-flagging is the conservative
     * direction (the window keeps day-zero auto behavior; surface loss is
     * measured at the census read) — per-spell applicability would mean
     * re-implementing CostAdjustment. The retrospective backstop (0 plans
     * on the raw cost while auto pays) catches leaks loudly.
     */
    public static boolean costModified(final SpellAbility sa) {
        final Card host = sa.getHostCard();
        if (host == null) {
            return false;
        }
        if (host.hasKeyword(forge.game.keyword.Keyword.DELVE)
                || host.hasKeyword(forge.game.keyword.Keyword.CONVOKE)
                || host.hasKeyword(forge.game.keyword.Keyword.IMPROVISE)
                || host.hasKeyword(forge.game.keyword.Keyword.ASSIST)
                || host.hasKeyword(forge.game.keyword.Keyword.OFFERING)
                || host.hasKeyword(forge.game.keyword.Keyword.EMERGE)) {
            return true;
        }
        final Player activator = sa.getActivatingPlayer();
        if (activator == null) {
            return false;
        }
        final java.util.List<Card> scan = new ArrayList<>();
        scan.addAll(activator.getGame().getCardsIn(ZoneType.Battlefield));
        scan.addAll(activator.getGame().getCardsIn(ZoneType.Command));
        if (!scan.contains(host)) {
            scan.add(host);
        }
        for (final Card c : scan) {
            for (final forge.game.staticability.StaticAbility stAb : c.getStaticAbilities()) {
                if (stAb.checkMode(forge.game.staticability.StaticAbilityMode.ReduceCost)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Atom collection + source-class signatures (spec §2)

    static List<Atom> collectAtoms(final Player payer, final SpellAbility sa) {
        final List<Atom> atoms = new ArrayList<>();
        final List<Card> cards = new ArrayList<>();
        cards.addAll(payer.getCardsIn(ZoneType.Battlefield));
        cards.addAll(payer.getCardsIn(ZoneType.Hand));
        cards.sort(Comparator.comparingInt(Card::getId));

        for (final Card c : cards) {
            for (final SpellAbility ma : c.getManaAbilities()) {
                ma.setActivatingPlayer(payer);
                if (!ma.canPlay()) {
                    continue;
                }
                final AbilityManaPart mp = ma.getManaPart();
                if (mp == null || !mp.meetsManaRestrictions(sa)) {
                    continue;
                }
                final Atom a = buildAtom(payer, c, ma, mp);
                if (a != null && a.yield > 0) {
                    atoms.add(a);
                }
            }
        }
        return atoms;
    }

    private static Atom buildAtom(Player payer, Card c, SpellAbility ma, AbilityManaPart mp) {
        // Yield + per-unit color masks. Fixed producers go through the
        // prediction arithmetic (captures TapsForMana boosts — the
        // yield-differing-taps pin); combo/any use their base amount.
        final byte[] unitMasks;
        if (mp.isComboMana() || mp.isAnyMana()) {
            int amount = 1;
            if (ma.hasParam("Amount")) {
                try {
                    amount = Integer.parseInt(ma.getParam("Amount"));
                } catch (NumberFormatException e) {
                    return null; // computed amounts: out of v0 enumeration, executor-adjudicated later
                }
            }
            byte mask = 0;
            if (mp.isAnyMana()) {
                mask = ManaAtom.ALL_MANA_COLORS;
            } else {
                for (String s : mp.getComboColors(ma).split(" ")) {
                    if (!s.isEmpty()) {
                        mask |= (byte) MagicColor.fromName(s);
                    }
                }
            }
            if (mask == 0) {
                return null;
            }
            unitMasks = new byte[amount];
            java.util.Arrays.fill(unitMasks, mask);
        } else {
            final String predicted = ComputerUtilMana.predictManafromSpellAbility(ma, payer, ManaCostShard.GENERIC);
            if (predicted == null || predicted.isEmpty()) {
                return null;
            }
            final String[] units = predicted.trim().split(" ");
            unitMasks = new byte[units.length];
            for (int i = 0; i < units.length; i++) {
                byte m = (byte) MagicColor.fromName(units[i]);
                if (m == 0 && "C".equals(units[i])) {
                    m = (byte) ManaAtom.COLORLESS;
                }
                if (m == 0) {
                    m = ManaAtom.ALL_MANA_COLORS; // unrecognized token (e.g. "Any" leak): permissive, executor adjudicates
                }
                unitMasks[i] = m;
            }
        }

        final Cost payCosts = ma.getPayCosts();
        final ManaCost actMana = payCosts.getTotalMana() == null ? ManaCost.ZERO : payCosts.getTotalMana();
        final String key = classKey(c, unitMasks, actMana);
        return new Atom(c, ma, unitMasks.length, unitMasks, actMana, key);
    }

    /** Source-class signature (spec §2). Card NAME deliberately excluded. */
    static String classKey(Card c, byte[] unitMasks, ManaCost activationMana) {
        final StringBuilder sb = new StringBuilder();
        sb.append("prod:");
        for (byte m : unitMasks) {
            sb.append(m).append(',');
        }
        sb.append("|act:").append(activationMana.getCMC()).append(activationMana.isZero() ? "z" : "m");
        sb.append("|res:");
        sb.append(c.isCreature() ? "c" + c.getNetPower() + "/" + c.getNetToughness() : "-");
        sb.append(c.isSnow() ? "s" : "-");
        sb.append(c.isLand() ? "L" : c.isArtifact() ? "A" : "O");
        boolean nonMana = false;
        for (SpellAbility other : c.getSpellAbilities()) {
            if (other.isActivatedAbility() && !other.isManaAbility()) {
                nonMana = true;
                break;
            }
        }
        sb.append(nonMana ? "x" : "-");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Enumeration DFS (spec §3)

    private static final class DfsState {
        final Player payer;
        final SpellAbility paidFor;
        final int planCap;
        final int nodeBudget;
        final List<List<Atom>> classes;      // atoms grouped by classKey, deterministic order
        /** Hosts committed to the plan-in-progress. A card with two mana
         *  abilities (dual lands: two classes, one card) must serve at most
         *  ONE atom per plan — availability is per HOST, not per class (the
         *  2026-08-20 certify-smoke salvage finding: per-class counts let a
         *  {W}{U} cost commit the same lowest-id dual twice — count-feasible,
         *  executor-infeasible). Conservative for the rare no-tap repeatable
         *  producer; the executor adjudicates those either way. */
        final java.util.Set<Card> usedHosts = new java.util.HashSet<>();
        final int[] poolRemaining;           // per MANATYPES index
        final List<ManaCostShard> shards;    // work queue (activation costs get appended)
        final List<float_unit> floats = new ArrayList<>();
        final Map<String, Integer> planCounts = new TreeMap<>();
        final List<Atom> planAtoms = new ArrayList<>();
        final Map<Atom, byte[]> planColors = new LinkedHashMap<>();
        final int[] poolSpend = new int[ManaAtom.MANATYPES.length];
        int phyrexianLife = 0;
        final java.util.Set<String> planKeys = new java.util.HashSet<>();
        /** Snapshot of the original cost's shards (the work queue mutates
         *  as activation costs append) — the feasibility check's main-cost
         *  requirement. */
        final List<ManaCostShard> mainShards;
        // per-goal argmax (spec §12a): goals 0..S-1 = spare(source class k);
        // optionally S = min_life (phyrexian costs); fallback single "pay"
        // goal when neither exists (pool-only boards, forced-window safety).
        final String[] goalNames;
        final List<Integer> goalKinds;  // parallel to goalNames (GoalOption doc)
        final String[] goalClassKeys;   // null entry = non-spare goal
        final int[] bestObj;
        final int[] bestSpread;
        final String[] bestKey;
        final PaymentClass[] bestPlan;
        final Result result;

        DfsState(Player payer, SpellAbility paidFor, List<List<Atom>> classes, List<ManaCostShard> shards, int planCap,
                int nodeBudget, boolean hasPhyrexian, Result result) {
            this.payer = payer;
            this.paidFor = paidFor;
            this.classes = classes;
            this.shards = shards;
            this.mainShards = new ArrayList<>(shards);
            this.planCap = planCap;
            this.nodeBudget = nodeBudget;
            this.result = result;
            this.poolRemaining = new int[ManaAtom.MANATYPES.length];
            for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
                poolRemaining[i] = payer.getManaPool().getAmountOfColor(ManaAtom.MANATYPES[i]);
            }
            final List<String> names = new ArrayList<>();
            final List<String> keys = new ArrayList<>();
            final List<Integer> kinds = new ArrayList<>();
            for (final List<Atom> cls : classes) {
                final Atom rep = cls.get(0);
                names.add("spare:" + rep.host.getName() + (cls.size() > 1 ? " x" + cls.size() : ""));
                keys.add(rep.classKey);
                kinds.add(rep.host.isCreature() ? 1 : rep.host.isLand() ? 2 : rep.host.isArtifact() ? 3 : 4);
            }
            if (hasPhyrexian) {
                names.add("pay_mana_not_life");
                keys.add(null);
                kinds.add(5);
            }
            if (names.isEmpty()) {
                names.add("pay"); // pool-only boards: one option so forced windows stay expressible
                keys.add(null);
                kinds.add(0);
            }
            this.goalKinds = kinds;
            this.goalNames = names.toArray(new String[0]);
            this.goalClassKeys = keys.toArray(new String[0]);
            this.bestObj = new int[goalNames.length];
            this.bestSpread = new int[goalNames.length];
            this.bestKey = new String[goalNames.length];
            this.bestPlan = new PaymentClass[goalNames.length];
        }
    }

    /** A floating unit produced mid-plan, its color possibly still open. */
    private static final class float_unit {
        final Atom atom;
        final int unitIdx;
        final byte mask;
        boolean spent = false;

        float_unit(Atom atom, int unitIdx, byte mask) {
            this.atom = atom;
            this.unitIdx = unitIdx;
            this.mask = mask;
        }
    }

    public static Result enumerate(final Player payer, final SpellAbility sa, final ManaCost toPay) {
        return enumerate(payer, sa, toPay, NODE_BUDGET);
    }

    /** Node-budget-parameterized variant (tests; the tail probe's cap
     *  parameterization retired with the §12 goal surface — there is no
     *  class cap to raise anymore). */
    public static Result enumerate(final Player payer, final SpellAbility sa, final ManaCost toPay,
            final int nodeBudget) {
        final Result result = new Result();
        if (toPay == null || toPay.isZero()) {
            return result;
        }

        final List<Atom> atoms = collectAtoms(payer, sa);
        result.atomCount = atoms.size();

        // group into source classes, deterministic order (key sort; atoms stay id-sorted)
        final Map<String, List<Atom>> byKey = new TreeMap<>();
        for (Atom a : atoms) {
            byKey.computeIfAbsent(a.classKey, k -> new ArrayList<>()).add(a);
        }
        final List<List<Atom>> classes = new ArrayList<>(byKey.values());
        result.sourceClassCount = classes.size();

        // shard queue: specific shards first, generic last (assignment flexibility)
        final List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard shard : toPay) {
            shards.add(shard);
        }
        for (int i = 0; i < toPay.getGenericCost(); i++) {
            shards.add(ManaCostShard.GENERIC);
        }

        boolean hasPhyrexian = false;
        for (final ManaCostShard shard : toPay) {
            if (shard.isPhyrexian()) {
                hasPhyrexian = true;
                break;
            }
        }

        final DfsState st = new DfsState(payer, sa, classes, shards, shards.size() + PLAN_SLACK,
                nodeBudget, hasPhyrexian, result);
        dfs(st, 0);

        result.planCount = st.planKeys.size();
        // outcome dedupe (spec §12a): goals whose argmax composition is
        // identical collapse into one option, labeled with the joined goals.
        final Map<String, GoalOption> byPlan = new LinkedHashMap<>();
        for (int g = 0; g < st.goalNames.length; g++) {
            if (st.bestPlan[g] == null) {
                continue;
            }
            final GoalOption existing = byPlan.get(st.bestKey[g]);
            if (existing != null) {
                existing.goals.add(st.goalNames[g]);
                existing.kinds.add(st.goalKinds.get(g));
            } else if (byPlan.size() < GOAL_MAX - 1) { // -1: auto occupies a wire slot
                final List<String> gn = new ArrayList<>();
                gn.add(st.goalNames[g]);
                final List<Integer> gk = new ArrayList<>();
                gk.add(st.goalKinds.get(g));
                byPlan.put(st.bestKey[g], new GoalOption(gn, gk, st.bestPlan[g]));
            } else {
                result.truncated = true;
                result.goalCapHit = true;
            }
        }
        result.options.addAll(byPlan.values());
        return result;
    }

    private static void dfs(final DfsState st, final int shardIdx) {
        if (++st.result.nodesVisited > st.nodeBudget) {
            // per-goal bests found so far still surface — degraded and
            // logged, never silently censored (spec §12a).
            st.result.truncated = true;
            st.result.nodeCapHit = true;
            return;
        }
        if (shardIdx == st.shards.size()) {
            completePlan(st);
            return;
        }
        final ManaCostShard shard = st.shards.get(shardIdx);

        // (a) pay from an open floating unit. Index-based: the recursive
        // call inside the body pushes/pops st.floats (option c), which
        // invalidates a for-each iterator (the census CME, 2026-08-19 —
        // caught by the telemetry guard, 144/14,974 windows). The list is
        // size-restored on unwind, so a bounded index scan is stable.
        final int nFloats = st.floats.size();
        for (int fi = 0; fi < nFloats; fi++) {
            final float_unit fu = st.floats.get(fi);
            if (fu.spent) {
                continue;
            }
            final byte payable = payableColors(shard, fu.mask, st);
            if (payable == 0) {
                continue;
            }
            final byte c = firstColor(payable);
            fu.spent = true;
            st.planColors.get(fu.atom)[fu.unitIdx] = c;
            dfs(st, shardIdx + 1);
            st.planColors.get(fu.atom)[fu.unitIdx] = 0;
            fu.spent = false;
        }

        // (b) pay from pool (snow shards: pool snow-ness untracked in v0 — atoms only)
        if (!shard.isSnow()) {
            for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
                if (st.poolRemaining[i] <= 0 || !shard.canBePaidWithManaOfColor(ManaAtom.MANATYPES[i])) {
                    continue;
                }
                st.poolRemaining[i]--;
                st.poolSpend[i]++;
                dfs(st, shardIdx + 1);
                st.poolSpend[i]--;
                st.poolRemaining[i]++;
            }
        }

        // (c) activate an atom from each source class; any of its units may
        // be the paying unit (a Signet pays a B shard with its B unit while
        // the U unit floats) — distinct masks only, to skip symmetric branches
        if (st.planAtoms.size() < st.planCap) {
            for (int k = 0; k < st.classes.size(); k++) {
                final List<Atom> cls = st.classes.get(k);
                Atom a = null; // deterministic: lowest id whose HOST is uncommitted
                for (final Atom cand : cls) {
                    if (!st.usedHosts.contains(cand.host)) {
                        a = cand;
                        break;
                    }
                }
                if (a == null) {
                    continue;
                }
                if (shard.isSnow() && !a.host.isSnow()) {
                    continue;
                }
                for (int payUnit = 0; payUnit < a.yield; payUnit++) {
                    boolean dup = false;
                    for (int prev = 0; prev < payUnit; prev++) {
                        if (a.unitMasks[prev] == a.unitMasks[payUnit]) {
                            dup = true;
                            break;
                        }
                    }
                    if (dup) {
                        continue;
                    }
                    final byte payable = payableColors(shard, a.unitMasks[payUnit], st);
                    if (payable == 0) {
                        continue;
                    }
                    final byte c = firstColor(payable);
                    st.usedHosts.add(a.host);
                    st.planAtoms.add(a);
                    st.planCounts.merge(a.classKey, 1, Integer::sum);
                    final byte[] colors = new byte[a.yield];
                    colors[payUnit] = c;
                    st.planColors.put(a, colors);
                    final List<float_unit> added = new ArrayList<>();
                    for (int u = 0; u < a.yield; u++) {
                        if (u == payUnit) {
                            continue;
                        }
                        final float_unit fu = new float_unit(a, u, a.unitMasks[u]);
                        st.floats.add(fu);
                        added.add(fu);
                    }
                    // chained activation: its own mana cost joins the work queue
                    final int shardsAdded = appendCostShards(st, a.activationMana);

                    dfs(st, shardIdx + 1);

                    for (int i = 0; i < shardsAdded; i++) {
                        st.shards.remove(st.shards.size() - 1);
                    }
                    st.floats.removeAll(added);
                    st.planColors.remove(a);
                    if (st.planCounts.merge(a.classKey, -1, Integer::sum) == 0) {
                        st.planCounts.remove(a.classKey);
                    }
                    st.planAtoms.remove(st.planAtoms.size() - 1);
                    st.usedHosts.remove(a.host);
                }
            }
        }

        // (d) phyrexian: pay 2 life
        if (shard.isPhyrexian() && st.payer.canPayLife(2 * (st.phyrexianLife + 1), false, st.paidFor)) {
            st.phyrexianLife++;
            dfs(st, shardIdx + 1);
            st.phyrexianLife--;
        }
    }

    private static int appendCostShards(DfsState st, ManaCost cost) {
        if (cost == null || cost.isZero()) {
            return 0;
        }
        int n = 0;
        for (ManaCostShard s : cost) {
            st.shards.add(s);
            n++;
        }
        for (int i = 0; i < cost.getGenericCost(); i++) {
            st.shards.add(ManaCostShard.GENERIC);
            n++;
        }
        return n;
    }

    private static byte payableColors(ManaCostShard shard, byte mask, DfsState st) {
        byte out = 0;
        for (byte c : ManaAtom.MANATYPES) {
            if ((mask & c) != 0 && shard.canBePaidWithManaOfColor(c)) {
                out |= c;
            }
        }
        return out;
    }

    private static byte firstColor(byte mask) {
        for (byte c : ManaAtom.MANATYPES) {
            if ((mask & c) != 0) {
                return c;
            }
        }
        return 0;
    }

    private static void completePlan(final DfsState st) {
        if (!chainOrderFeasible(st)) {
            return;
        }
        final String key = planKey(st);
        if (!st.planKeys.add(key)) {
            return;
        }
        // spread = max taps of any single class (spec §12a tie-break: the
        // max-entropy residual — prefer paying broadly over exhausting one class)
        int spread = 0;
        for (final int n : st.planCounts.values()) {
            spread = Math.max(spread, n);
        }
        PaymentClass materialized = null;
        for (int g = 0; g < st.goalNames.length; g++) {
            final int obj;
            if (st.goalClassKeys[g] != null) {
                final Integer taps = st.planCounts.get(st.goalClassKeys[g]);
                obj = taps == null ? 0 : taps;
            } else if ("pay_mana_not_life".equals(st.goalNames[g])) {
                obj = st.phyrexianLife;
            } else {
                obj = 0; // the fallback "pay" goal: any feasible plan
            }
            final boolean better = st.bestPlan[g] == null
                    || obj < st.bestObj[g]
                    || (obj == st.bestObj[g] && (spread < st.bestSpread[g]
                        || (spread == st.bestSpread[g] && key.compareTo(st.bestKey[g]) < 0)));
            if (!better) {
                continue;
            }
            if (materialized == null) {
                // materialize once per plan: default any open unit colors
                final Map<Atom, byte[]> colors = new LinkedHashMap<>();
                for (Map.Entry<Atom, byte[]> e : st.planColors.entrySet()) {
                    final byte[] cc = e.getValue().clone();
                    for (int i = 0; i < cc.length; i++) {
                        if (cc[i] == 0) {
                            cc[i] = firstColor(e.getKey().unitMasks[i]);
                        }
                    }
                    colors.put(e.getKey(), cc);
                }
                materialized = new PaymentClass(key, new TreeMap<>(st.planCounts),
                        new ArrayList<>(st.planAtoms), colors, st.poolSpend.clone(), st.phyrexianLife);
            }
            st.bestObj[g] = obj;
            st.bestSpread[g] = spread;
            st.bestKey[g] = key;
            st.bestPlan[g] = materialized;
        }
    }

    /** Feasibility-check backtracking budget (per completed plan). On
     *  exhaustion the plan is ADMITTED (pre-tightening behavior: the
     *  executor adjudicates) — a budget miss must degrade loud-at-census,
     *  never silently censor. Expected never hit at measured plan sizes. */
    static final int FEAS_NODE_BUDGET = 20000;

    /** Activation-order feasibility (spec §3; tightened 2026-08-21 — the
     *  certify2/3 "costs:Arena of Glory" salvage family, all 32+8 rows).
     *  The old check was count-based and color-blind: a Plains float
     *  "covered" a {R} activation cost, and an atom's OWN units could pay
     *  its own activation shard (mana that does not exist until after the
     *  cost is paid). This version replays the EXECUTOR's exact activation
     *  order (EXEC_ORDER) and requires each chained atom's activation cost
     *  to be color-coverable by mana that exists strictly before it
     *  executes — the initial pool plus earlier atoms' full yields,
     *  treated as fungible (executeDirected completes pool-first; DFS
     *  shard earmarks do not survive the pool) — with the main cost
     *  coverable by what remains. Exact small backtracking with symmetric
     *  -unit pruning. */
    private static boolean chainOrderFeasible(final DfsState st) {
        final List<Atom> ordered = new ArrayList<>(st.planAtoms);
        ordered.sort(EXEC_ORDER);

        // Requirement list, group order = execution order, main cost last.
        // limit = exec index the paying unit must strictly precede.
        final List<ManaCostShard> req = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        boolean anyChained = false;
        for (int i = 0; i < ordered.size(); i++) {
            final ManaCost act = ordered.get(i).activationMana;
            if (act.isZero()) {
                continue;
            }
            anyChained = true;
            for (final ManaCostShard sh : act) {
                req.add(sh);
                limits.add(i);
            }
            for (int g = 0; g < act.getGenericCost(); g++) {
                req.add(ManaCostShard.GENERIC);
                limits.add(i);
            }
        }
        if (!anyChained) {
            return true; // no activation costs — nothing temporal to violate
        }
        for (final ManaCostShard sh : st.mainShards) {
            req.add(sh);
            limits.add(Integer.MAX_VALUE);
        }

        // Unit inventory: {availAfter exec idx (-1 = initial pool), color mask, snow, spent}
        final List<int[]> units = new ArrayList<>();
        for (int t = 0; t < ManaAtom.MANATYPES.length; t++) {
            final int have = st.poolRemaining[t] + st.poolSpend[t]; // = pool at window entry
            for (int n = 0; n < have; n++) {
                units.add(new int[] { -1, ManaAtom.MANATYPES[t] & 0xFF, 0, 0 });
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            final Atom a = ordered.get(i);
            // Materialized colors, not masks: the executor expresses each
            // unit at its plan color (assigned by the DFS, else the
            // completePlan firstColor default) — at execution time a combo
            // unit has ONE color, so solving feasibility over masks
            // overcounts (the job-202 residual: a Shivan Reef U|R unit
            // committed to U cannot pay Arena's {R} activation cost).
            final byte[] assigned = st.planColors.get(a);
            for (int j = 0; j < a.unitMasks.length; j++) {
                final byte c = assigned != null && assigned[j] != 0
                        ? assigned[j] : firstColor(a.unitMasks[j]);
                units.add(new int[] { i, c & 0xFF, a.host.isSnow() ? 1 : 0, 0 });
            }
        }
        return satisfy(req, limits, units, 0, st.phyrexianLife, new int[] { FEAS_NODE_BUDGET });
    }

    private static boolean satisfy(final List<ManaCostShard> req, final List<Integer> limits,
            final List<int[]> units, final int idx, final int lifeBudget, final int[] budget) {
        if (idx == req.size()) {
            return true;
        }
        if (--budget[0] < 0) {
            return true; // budget exhausted: admit, executor adjudicates (never silently censor)
        }
        final ManaCostShard sh = req.get(idx);
        final int lim = limits.get(idx);
        final java.util.Set<Integer> tried = new java.util.HashSet<>();
        for (final int[] u : units) {
            if (u[3] == 1 || u[0] >= lim) {
                continue;
            }
            if (sh.isSnow() && u[2] == 0) {
                continue;
            }
            boolean colorOk = false;
            for (final byte c : ManaAtom.MANATYPES) {
                if ((u[1] & c) != 0 && sh.canBePaidWithManaOfColor(c)) {
                    colorOk = true;
                    break;
                }
            }
            if (!colorOk || !tried.add((u[0] << 16) | (u[1] << 8) | u[2])) {
                continue; // symmetric units: one try per (availAfter, mask, snow)
            }
            u[3] = 1;
            if (satisfy(req, limits, units, idx + 1, lifeBudget, budget)) {
                return true;
            }
            u[3] = 0;
        }
        if (sh.isPhyrexian() && lifeBudget > 0) {
            return satisfy(req, limits, units, idx + 1, lifeBudget - 1, budget);
        }
        return false;
    }

    private static String planKey(final DfsState st) {
        final StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : st.planCounts.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        sb.append("pool:");
        for (int s : st.poolSpend) {
            sb.append(s).append(',');
        }
        sb.append("phy:").append(st.phyrexianLife);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Directed executor (spec §7): float-then-apply over the ADR-0065 primitive

    public enum ExecOutcome { DIRECTED_OK, DIRECTED_SALVAGE }

    /**
     * Execute the directed activations of a payment class. The caller then
     * hands the window back to the normal payment path (pool-first), which
     * completes the payment from the float — including on SALVAGE, where
     * whatever floated stays available to auto-payment.
     */
    public static ExecOutcome executeDirected(final Player p, final PaymentClass pc) {
        return executeDirected(p, pc, null);
    }

    /** `why` (nullable): on SALVAGE, receives the failure point as
     *  "canplay:" / "costs:" + host name#id @ atom index — the certify
     *  harness's diagnosis channel (the 2026-08-20 salvage finding was
     *  blind without it). */
    public static ExecOutcome executeDirected(final Player p, final PaymentClass pc,
            final StringBuilder why) {
        final List<Atom> ordered = new ArrayList<>(pc.atoms);
        ordered.sort(EXEC_ORDER);
        int idx = 0;
        for (final Atom a : ordered) {
            a.ma.setActivatingPlayer(p);
            if (!a.ma.canPlay()) {
                salvageWhy(why, "canplay", a, idx);
                return ExecOutcome.DIRECTED_SALVAGE;
            }
            final AbilityManaPart mp = a.ma.getManaPart();
            if (mp != null && (mp.isComboMana() || mp.isAnyMana())) {
                final byte[] colors = pc.unitColors.get(a);
                if (colors != null) {
                    final StringBuilder choice = new StringBuilder();
                    for (byte c : colors) {
                        if (choice.length() > 0) {
                            choice.append(' ');
                        }
                        choice.append(MagicColor.toShortString(c));
                    }
                    mp.setExpressChoice(choice.toString());
                }
            }
            final CostPayment pay = new CostPayment(a.ma.getPayCosts(), a.ma);
            if (!pay.payComputerCosts(new AiCostDecision(p, a.ma, false, true))) {
                salvageWhy(why, "costs", a, idx);
                return ExecOutcome.DIRECTED_SALVAGE;
            }
            p.getGame().getStack().addAndUnfreeze(a.ma);
            idx++;
        }
        return ExecOutcome.DIRECTED_OK;
    }

    /** Executor-order plan dump for the salvage diagnosis channel
     *  (ADR-0066/0067 genre: a salvage row without the plan is blind).
     *  host#id[activation]->unit colors per atom, then pool spend. */
    public static String describePlan(final PaymentClass pc) {
        final List<Atom> ordered = new ArrayList<>(pc.atoms);
        ordered.sort(EXEC_ORDER);
        final StringBuilder sb = new StringBuilder();
        for (final Atom a : ordered) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(a.host.getName()).append('#').append(a.host.getId());
            if (!a.activationMana.isZero()) {
                sb.append('[').append(a.activationMana).append(']');
            }
            sb.append("->");
            final byte[] cc = pc.unitColors.get(a);
            for (int i = 0; i < a.unitMasks.length; i++) {
                final byte c = cc != null && cc[i] != 0 ? cc[i] : a.unitMasks[i];
                final String s = MagicColor.toShortString(c);
                sb.append(s != null && s.length() == 1 ? s : "m" + (c & 0xFF));
            }
        }
        sb.append("|pool:");
        for (final int s : pc.poolSpend) {
            sb.append(s).append(',');
        }
        return sb.toString();
    }

    private static void salvageWhy(StringBuilder why, String check, Atom a, int idx) {
        if (why != null) {
            why.append(check).append(':').append(a.host.getName())
                    .append('#').append(a.host.getId()).append('@').append(idx);
        }
    }
}
