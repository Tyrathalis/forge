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

    /** Max distinct payment classes surfaced (+ implicit auto). Truncation is logged, never silent. */
    public static final int K_MAX = 8;
    /** DFS node budget — worst-case guard; hitting it flags truncation. */
    public static final int NODE_BUDGET = 20000;
    /** Plan size cap: at most (shard count + PLAN_SLACK) activations. */
    public static final int PLAN_SLACK = 2;

    private PaymentEnumerator() { }

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

    public static final class Result {
        public final List<PaymentClass> classes = new ArrayList<>();
        public boolean truncated = false;
        public int nodesVisited = 0;
        public int atomCount = 0;
        public int sourceClassCount = 0;

        /** The consequential flag (m9-payment-surface-spec §4). */
        public boolean consequential() {
            return classes.size() >= 2;
        }
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
        final List<List<Atom>> classes;      // atoms grouped by classKey, deterministic order
        final int[] remaining;               // atoms left per class
        final int[] poolRemaining;           // per MANATYPES index
        final List<ManaCostShard> shards;    // work queue (activation costs get appended)
        final List<float_unit> floats = new ArrayList<>();
        final Map<String, Integer> planCounts = new TreeMap<>();
        final List<Atom> planAtoms = new ArrayList<>();
        final Map<Atom, byte[]> planColors = new LinkedHashMap<>();
        final int[] poolSpend = new int[ManaAtom.MANATYPES.length];
        int phyrexianLife = 0;
        final Map<String, PaymentClass> found = new LinkedHashMap<>();
        final Result result;

        DfsState(Player payer, SpellAbility paidFor, List<List<Atom>> classes, List<ManaCostShard> shards, int planCap, Result result) {
            this.payer = payer;
            this.paidFor = paidFor;
            this.classes = classes;
            this.shards = shards;
            this.planCap = planCap;
            this.result = result;
            this.remaining = new int[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                remaining[i] = classes.get(i).size();
            }
            this.poolRemaining = new int[ManaAtom.MANATYPES.length];
            for (int i = 0; i < ManaAtom.MANATYPES.length; i++) {
                poolRemaining[i] = payer.getManaPool().getAmountOfColor(ManaAtom.MANATYPES[i]);
            }
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

        final DfsState st = new DfsState(payer, sa, classes, shards, shards.size() + PLAN_SLACK, result);
        dfs(st, 0);

        result.classes.addAll(st.found.values());
        return result;
    }

    private static void dfs(final DfsState st, final int shardIdx) {
        if (st.found.size() >= K_MAX) {
            st.result.truncated = true;
            return;
        }
        if (++st.result.nodesVisited > NODE_BUDGET) {
            st.result.truncated = true;
            return;
        }
        if (shardIdx == st.shards.size()) {
            completePlan(st);
            return;
        }
        final ManaCostShard shard = st.shards.get(shardIdx);

        // (a) pay from an open floating unit
        for (final float_unit fu : st.floats) {
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
                if (st.remaining[k] <= 0) {
                    continue;
                }
                final List<Atom> cls = st.classes.get(k);
                final Atom a = cls.get(cls.size() - st.remaining[k]); // deterministic: lowest unused id
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
                    st.remaining[k]--;
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
                    st.remaining[k]++;
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
        if (st.found.containsKey(key)) {
            return;
        }
        // materialize the representative: default any open unit colors
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
        st.found.put(key, new PaymentClass(key, new TreeMap<>(st.planCounts),
                new ArrayList<>(st.planAtoms), colors, st.poolSpend.clone(), st.phyrexianLife));
    }

    /** Greedy activation-order feasibility (spec §3): zero-cost first, then
     *  net-positive, require running float covers each activation cost.
     *  Count-based v0 approximation (colored-exactness adjudicated by the executor). */
    private static boolean chainOrderFeasible(final DfsState st) {
        int avail = 0;
        for (int s : st.poolSpend) {
            avail += s;
        }
        final List<Atom> pending = new ArrayList<>(st.planAtoms);
        pending.sort(Comparator.comparingInt((Atom a) -> a.activationMana.getCMC())
                .thenComparingInt(a -> -a.yield));
        boolean progress = true;
        while (!pending.isEmpty() && progress) {
            progress = false;
            for (int i = 0; i < pending.size(); i++) {
                final Atom a = pending.get(i);
                if (a.activationMana.getCMC() <= avail) {
                    avail += a.yield - a.activationMana.getCMC();
                    pending.remove(i);
                    progress = true;
                    break;
                }
            }
        }
        return pending.isEmpty();
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
        final List<Atom> ordered = new ArrayList<>(pc.atoms);
        ordered.sort(Comparator.comparingInt((Atom a) -> a.activationMana.getCMC())
                .thenComparingInt(a -> a.host.getId()));
        for (final Atom a : ordered) {
            a.ma.setActivatingPlayer(p);
            if (!a.ma.canPlay()) {
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
                return ExecOutcome.DIRECTED_SALVAGE;
            }
            p.getGame().getStack().addAndUnfreeze(a.ma);
        }
        return ExecOutcome.DIRECTED_OK;
    }
}
