package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.combat.Combat;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;

import java.util.List;
import java.util.Set;

/**
 * Anvil's controller (override plan, M0 form): the bridged tag set is answered
 * through an AnvilBridge; every other decision inherits the heuristic AI
 * (via CensusPlayerController, so when Census logging is open every callback
 * is recorded — bridged ones tagged by="bridge", the rest implicitly
 * heuristic-fallback; provenance rule of the override plan).
 *
 * Priority semantics (M0 random-legal): options are materialized engine-side
 * (legal-actions-only invariant) as pass + engine-legal, payable spell
 * abilities + legal land drops; the bridge picks an index. A picked spell is
 * then run through the AI's canPlaySa so targets/X are pre-set the AI-path
 * way (census finding: targets and X are injected, never callbacks); if the
 * AI evaluation vetoes it (no valid targets etc.), the window passes. So M0
 * plays "random over engine-legal, AI-targeted" — documented delta from pure
 * random-legal, revisited when CastPlan lands at M1.
 */
public class PlayerControllerAnvil extends CensusPlayerController {
    public static final String TAG_PRIORITY = "mtg.priority";
    public static final String TAG_MULLIGAN = "mtg.mulligan_keep";
    public static final String TAG_TUCK = "mtg.mulligan_tuck";
    public static final String TAG_TRIGGER = "mtg.trigger";
    public static final String TAG_BINARY = "mtg.binary";
    public static final String TAG_NUMBER = "mtg.number";
    public static final String TAG_ATTACK = "mtg.attack";   // M2 D5
    public static final String TAG_BLOCK = "mtg.block";     // M2 D5

    private final AnvilBridge bridge;
    private final Set<String> bridgedTags;

    public PlayerControllerAnvil(Game game, Player p, LobbyPlayer lp, AnvilBridge bridge, Set<String> bridgedTags) {
        super(game, p, lp);
        this.bridge = bridge;
        this.bridgedTags = bridgedTags;
    }

    private boolean bridged(String tag) {
        return bridgedTags.contains(tag);
    }

    /**
     * D6 run-2 re-ask-on-veto (d6-vtrace-loop §6b): on an M1 CastPlan veto,
     * re-issue the priority decision with the vetoed candidate removed instead
     * of converting the window to a pass. Off = pre-amendment behavior.
     * Static because config is per-worker-JVM (AnvilRun sets it once from
     * -reask) and fork-created controllers must inherit it.
     */
    private static volatile boolean reaskOnVeto = false;
    /** Options shrink every re-ask, so termination is structural; the cap is
     *  insurance against pathologically wide windows re-vetoing in chains. */
    private static final int REASK_CAP = 8;

    public static void setReaskOnVeto(boolean v) {
        reaskOnVeto = v;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (!bridged(TAG_PRIORITY)) {
            return super.chooseSpellAbilityToPlay();
        }
        // Mutable copy: re-ask removes vetoed candidates between attempts.
        List<SpellAbility> options =
                Lists.newArrayList(AnvilOptions.priorityOptions(getGame(), player));

        for (int attempt = 0;; attempt++) {
            // Index 0 = pass; one round-trip per attempt.
            List<String> labels = Lists.newArrayListWithCapacity(options.size() + 1);
            labels.add("pass");
            for (SpellAbility sa : options) {
                labels.add(Census.str(sa));
            }
            // Structured-opts dec (same basis as the corpus label path) so D8
            // eval games are analyzable trajectories and ret() can label "oi".
            // A re-ask mints a fresh seq; the vetoed dec's ret(null) has
            // already run, so the single-slot oi bookkeeping is clear.
            long obsSeq = Obs.decPriority(getGame(), getPlayer(), "bridge", options);

            // M1 one-shot: the composite CastPlan path; null = M0-shape bridge.
            CastPlanAnswer plan = bridge.priorityCastPlan(TAG_PRIORITY, labels,
                    Obs.lastDecForBridge(getGame()), attempt);
            if (plan != null) {
                OneShot r = oneShotCast(options, plan, obsSeq, attempt);
                if (r.vetoedOption <= 0) {
                    return r.sas; // realized cast, model pass, or oor pass
                }
                if (!reaskOnVeto || attempt + 1 >= REASK_CAP) {
                    return null; // pre-amendment behavior: veto = pass
                }
                SpellAbility vetoed = options.get(r.vetoedOption - 1);
                if (plan.hostLevel && vetoed.getHostCard() != null) {
                    // Host-level plans exhausted the host's whole ladder.
                    final Card host = vetoed.getHostCard();
                    options.removeIf(sa -> sa.getHostCard() == host);
                } else {
                    options.remove(r.vetoedOption - 1);
                }
                if (options.isEmpty()) {
                    return null; // only pass remains; nothing left to ask
                }
                continue;
            }
            return selectOnePick(options, labels, obsSeq);
        }
    }

    /** M0 selectOne path (never re-asks; heuristic canPlaySa veto = pass). */
    private List<SpellAbility> selectOnePick(List<SpellAbility> options, List<String> labels,
            long obsSeq) {
        int pick = bridge.selectOne(TAG_PRIORITY, labels);
        if (pick == 0) {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", "pass");
            Obs.ret(getGame(), obsSeq, null);
            return null;
        }
        SpellAbility chosen = options.get(pick - 1);
        if (!chosen.isLandAbility() && getAi().canPlaySa(chosen) != AiPlayDecision.WillPlay) {
            // Targets/X could not be set up; window passes. Counted so the
            // veto rate is visible (it biases the pick toward AI-playable).
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(chosen), "veto", true);
            Obs.ret(getGame(), obsSeq, null);
            return null;
        }
        Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                "by", "bridge", "options", options.size(), "pick", Census.str(chosen));
        Obs.ret(getGame(), obsSeq, chosen);
        return Lists.newArrayList(chosen);
    }

    /** One-shot attempt outcome: sas = the answer (null = window passes);
     *  vetoedOption = the 1-based option index the realizer vetoed (0 = no
     *  veto — the caller only re-asks on a veto). */
    private static final class OneShot {
        final List<SpellAbility> sas;
        final int vetoedOption;

        OneShot(List<SpellAbility> sas, int vetoedOption) {
            this.sas = sas;
            this.vetoedOption = vetoedOption;
        }
    }

    /**
     * M1 D8: realize a composite CastPlan answer. The realizer adjudicates
     * legality only (never the heuristic's judgment — the M0 65% veto class);
     * a veto passes the window (or re-asks, D6 run-2), with the reason in the
     * census/provenance log. Census lines gain "reask"=attempt on re-asked
     * attempts (attempt > 0), so a success line with reask>0 = a rescue.
     */
    private OneShot oneShotCast(List<SpellAbility> options, CastPlanAnswer plan,
            long obsSeq, int attempt) {
        if (plan.optionIndex <= 0 || plan.optionIndex > options.size()) {
            boolean oor = plan.optionIndex != 0;
            if (attempt > 0) {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", "pass",
                        "oneshot", true, "oor", oor, "reask", attempt);
            } else {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", "pass",
                        "oneshot", true, "oor", oor);
            }
            Obs.ret(getGame(), obsSeq, null);
            return new OneShot(null, 0);
        }
        SpellAbility picked = options.get(plan.optionIndex - 1);
        List<SpellAbility> hostSas;
        if (plan.hostLevel && picked.getHostCard() != null) {
            hostSas = Lists.newArrayListWithCapacity(2);
            for (SpellAbility sa : options) {
                if (sa.getHostCard() == picked.getHostCard()) {
                    hostSas.add(sa);
                }
            }
        } else {
            hostSas = Lists.newArrayList(picked);
        }
        CastPlanRealizer.Result r = CastPlanRealizer.realize(getGame(), player, hostSas, plan);
        if (r.sa == null) {
            if (attempt > 0) {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", Census.str(picked),
                        "oneshot", true, "veto", r.veto, "hostSas", r.hostSas, "fits", r.fitCount,
                        "reask", attempt);
            } else {
                Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                        "by", "bridge", "options", options.size(), "pick", Census.str(picked),
                        "oneshot", true, "veto", r.veto, "hostSas", r.hostSas, "fits", r.fitCount);
            }
            Obs.ret(getGame(), obsSeq, null);
            return new OneShot(null, plan.optionIndex);
        }
        if (attempt > 0) {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(r.sa),
                    "oneshot", true, "rung", r.rung, "hostSas", r.hostSas, "fits", r.fitCount,
                    "divided", r.divided, "reask", attempt);
        } else {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(r.sa),
                    "oneshot", true, "rung", r.rung, "hostSas", r.hostSas, "fits", r.fitCount,
                    "divided", r.divided);
        }
        Obs.ret(getGame(), obsSeq, Lists.newArrayList(r.sa));
        return new OneShot(Lists.newArrayList(r.sa), 0);
    }

    /** London mulligans are rules-unbounded (hand redraws to 7 every time), so
     *  a pathological bridge answer loops the game forever at turn 0 (D8
     *  smoke 1). Insurance cap, far beyond any sane line. */
    private static final int MULLIGAN_CAP = 12;
    private int mulligansAsked;

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (!bridged(TAG_MULLIGAN)) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "mulliganKeepHand", null);
        boolean keep = bridge.bool(TAG_MULLIGAN);
        if (!keep && ++mulligansAsked >= MULLIGAN_CAP) {
            keep = true;
            Census.rec(getGame(), getPlayer(), "mulliganKeepHand", "by", "bridge",
                    "keep", true, "mull_cap", true);
        } else {
            Census.rec(getGame(), getPlayer(), "mulliganKeepHand", "by", "bridge", "keep", keep);
        }
        Obs.ret(getGame(), obsSeq, keep);
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (!bridged(TAG_TUCK)) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        List<String> handLabels = Lists.newArrayListWithCapacity(hand.size());
        for (Card c : hand) {
            handLabels.add(Census.str(c));
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "tuckCardsViaMulligan", handLabels);
        int[] picks = bridge.selectK(TAG_TUCK, hand.size(), cardsToReturn);
        CardCollection tuck = new CardCollection();
        for (int i : picks) {
            tuck.add(hand.get(i));
        }
        Census.rec(getGame(), getPlayer(), "tuckCardsViaMulligan", "by", "bridge", "n", tuck.size());
        Obs.ret(getGame(), obsSeq, tuck);
        return tuck;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        if (!bridged(TAG_TRIGGER)) {
            return super.confirmTrigger(sa);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "confirmTrigger", null, "sa", Census.str(sa));
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "confirmTrigger", "by", "bridge", "yes", yes);
        Obs.ret(getGame(), obsSeq, yes);
        return yes;
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory || !bridged(TAG_TRIGGER)) {
            return super.playTrigger(host, wrapperAbility, isMandatory);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "playTrigger", null,
                "host", Census.str(host), "wrapperAbility", Census.str(wrapperAbility));
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "playTrigger", "by", "bridge", "yes", yes);
        Obs.ret(getGame(), obsSeq, yes);
        return yes && super.playTrigger(host, wrapperAbility, true);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        if (!bridged(TAG_BINARY)) {
            return super.chooseBinary(sa, question, kindOfChoice, defaultVal);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseBinary", null,
                "question", question, "kind", String.valueOf(kindOfChoice));
        boolean v = bridge.bool(TAG_BINARY);
        Census.rec(getGame(), getPlayer(), "chooseBinary", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (!bridged(TAG_NUMBER)) {
            return super.chooseNumber(sa, title, min, max);
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseNumber", null,
                "title", title, "min", min, "max", max);
        int v = bridge.intInRange(TAG_NUMBER, min, max);
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        if (!bridged(TAG_NUMBER)) {
            return super.chooseNumber(sa, title, values, relatedPlayer);
        }
        List<String> valueLabels = Lists.newArrayListWithCapacity(values.size());
        for (Integer n : values) {
            valueLabels.add(String.valueOf(n));
        }
        long obsSeq = Obs.decBridged(getGame(), getPlayer(), "chooseNumber", valueLabels, "title", title);
        int v = values.get(bridge.selectOne(TAG_NUMBER, valueLabels));
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
        Obs.ret(getGame(), obsSeq, v);
        return v;
    }

    /**
     * M2 D5 combat declarations. Labels are obs-join (post-declaration windows
     * carry the atk/blk flags), so no Obs.ret — same record shape as the
     * heuristic corpus. The realizer is engine-legality-only with
     * requirements repair (CombatRealizer); every deviation from the model's
     * raw map is census-counted (applied/dropped/forced/fallback). If the
     * bridge lacks the shape (misconfigured non-model arm), the AI brains
     * answer directly — super would double-log the dec.
     */
    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        if (!bridged(TAG_ATTACK)) {
            super.declareAttackers(attacker, combat);
            return;
        }
        Obs.decBridged(getGame(), getPlayer(), "declareAttackers", null,
                "attacker", Census.str(attacker));
        CombatMapAnswer ans = bridge.attackMap(TAG_ATTACK, Obs.lastDecForBridge(getGame()));
        if (ans == null) {
            Census.rec(getGame(), getPlayer(), "declareAttackers", "by", "bridge",
                    "noShape", true);
            getAi().declareAttackers(attacker, combat);
            return;
        }
        CombatRealizer.Result r = CombatRealizer.realizeAttack(
                getGame(), attacker, combat, getAi(), ans);
        Census.rec(getGame(), getPlayer(), "declareAttackers", "by", "bridge",
                "assign", ans.assignments.size(), "applied", r.applied,
                "dropped", r.dropped, "forced", r.forced, "fallback", r.fallback);
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        if (!bridged(TAG_BLOCK)) {
            super.declareBlockers(defender, combat);
            return;
        }
        Obs.decBridged(getGame(), getPlayer(), "declareBlockers", null,
                "defender", Census.str(defender));
        CombatMapAnswer ans = bridge.blockMap(TAG_BLOCK, Obs.lastDecForBridge(getGame()));
        if (ans == null) {
            Census.rec(getGame(), getPlayer(), "declareBlockers", "by", "bridge",
                    "noShape", true);
            getAi().declareBlockersFor(defender, combat);
            return;
        }
        CombatRealizer.Result r = CombatRealizer.realizeBlock(
                getGame(), defender, combat, ans);
        Census.rec(getGame(), getPlayer(), "declareBlockers", "by", "bridge",
                "assign", ans.assignments.size(), "applied", r.applied,
                "dropped", r.dropped, "forced", r.forced, "fallback", r.fallback);
    }
}
