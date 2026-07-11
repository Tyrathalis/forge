package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
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

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (!bridged(TAG_PRIORITY)) {
            return super.chooseSpellAbilityToPlay();
        }
        List<SpellAbility> options = AnvilOptions.priorityOptions(getGame(), player);

        // Index 0 = pass; one round-trip per priority window regardless.
        List<String> labels = Lists.newArrayListWithCapacity(options.size() + 1);
        labels.add("pass");
        for (SpellAbility sa : options) {
            labels.add(Census.str(sa));
        }
        // Structured-opts dec (same basis as the corpus label path) so D8 eval
        // games are analyzable trajectories and ret() can label "oi".
        long obsSeq = Obs.decPriority(getGame(), getPlayer(), "bridge", options);

        // M1 one-shot: the composite CastPlan path; null = M0-shape bridge.
        CastPlanAnswer plan = bridge.priorityCastPlan(TAG_PRIORITY, labels,
                Obs.lastDecForBridge(getGame()));
        if (plan != null) {
            return oneShotCast(options, plan, obsSeq);
        }
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

    /**
     * M1 D8: realize a composite CastPlan answer. The realizer adjudicates
     * legality only (never the heuristic's judgment — the M0 65% veto class);
     * a veto passes the window, with the reason in the census/provenance log.
     */
    private List<SpellAbility> oneShotCast(List<SpellAbility> options, CastPlanAnswer plan,
            long obsSeq) {
        if (plan.optionIndex <= 0 || plan.optionIndex > options.size()) {
            boolean oor = plan.optionIndex != 0;
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", "pass",
                    "oneshot", true, "oor", oor);
            Obs.ret(getGame(), obsSeq, null);
            return null;
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
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(picked),
                    "oneshot", true, "veto", r.veto, "hostSas", r.hostSas, "fits", r.fitCount);
            Obs.ret(getGame(), obsSeq, null);
            return null;
        }
        Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                "by", "bridge", "options", options.size(), "pick", Census.str(r.sa),
                "oneshot", true, "rung", r.rung, "hostSas", r.hostSas, "fits", r.fitCount,
                "divided", r.divided);
        Obs.ret(getGame(), obsSeq, Lists.newArrayList(r.sa));
        return Lists.newArrayList(r.sa);
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
}
