package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.LobbyPlayer;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
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
        List<SpellAbility> options = Lists.newArrayList();
        CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(getGame(), player));
        for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(cards, player)) {
            sa.setActivatingPlayer(player);
            if (!sa.isLandAbility() && sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false)) {
                options.add(sa);
            }
        }
        CardCollectionView lands = ComputerUtilAbility.getAvailableLandsToPlay(getGame(), player);
        if (lands != null) {
            for (Card land : lands) {
                for (SpellAbility sa : land.getAllPossibleAbilities(player, true)) {
                    if (sa.isLandAbility()) {
                        sa.setActivatingPlayer(player);
                        if (sa.canPlay()) {
                            options.add(sa);
                        }
                    }
                }
            }
        }

        // Index 0 = pass; one round-trip per priority window regardless.
        List<String> labels = Lists.newArrayListWithCapacity(options.size() + 1);
        labels.add("pass");
        for (SpellAbility sa : options) {
            labels.add(Census.str(sa));
        }
        int pick = bridge.selectOne(TAG_PRIORITY, labels);
        if (pick == 0) {
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", "pass");
            return null;
        }
        SpellAbility chosen = options.get(pick - 1);
        if (!chosen.isLandAbility() && getAi().canPlaySa(chosen) != AiPlayDecision.WillPlay) {
            // Targets/X could not be set up; window passes. Counted so the
            // veto rate is visible (it biases the pick toward AI-playable).
            Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                    "by", "bridge", "options", options.size(), "pick", Census.str(chosen), "veto", true);
            return null;
        }
        Census.rec(getGame(), getPlayer(), "chooseSpellAbilityToPlay",
                "by", "bridge", "options", options.size(), "pick", Census.str(chosen));
        return Lists.newArrayList(chosen);
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (!bridged(TAG_MULLIGAN)) {
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        boolean keep = bridge.bool(TAG_MULLIGAN);
        Census.rec(getGame(), getPlayer(), "mulliganKeepHand", "by", "bridge", "keep", keep);
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (!bridged(TAG_TUCK)) {
            return super.tuckCardsViaMulligan(hand, cardsToReturn);
        }
        int[] picks = bridge.selectK(TAG_TUCK, hand.size(), cardsToReturn);
        CardCollection tuck = new CardCollection();
        for (int i : picks) {
            tuck.add(hand.get(i));
        }
        Census.rec(getGame(), getPlayer(), "tuckCardsViaMulligan", "by", "bridge", "n", tuck.size());
        return tuck;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        if (!bridged(TAG_TRIGGER)) {
            return super.confirmTrigger(sa);
        }
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "confirmTrigger", "by", "bridge", "yes", yes);
        return yes;
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        if (isMandatory || !bridged(TAG_TRIGGER)) {
            return super.playTrigger(host, wrapperAbility, isMandatory);
        }
        boolean yes = bridge.bool(TAG_TRIGGER);
        Census.rec(getGame(), getPlayer(), "playTrigger", "by", "bridge", "yes", yes);
        return yes && super.playTrigger(host, wrapperAbility, true);
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultVal) {
        if (!bridged(TAG_BINARY)) {
            return super.chooseBinary(sa, question, kindOfChoice, defaultVal);
        }
        boolean v = bridge.bool(TAG_BINARY);
        Census.rec(getGame(), getPlayer(), "chooseBinary", "by", "bridge", "v", v);
        return v;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (!bridged(TAG_NUMBER)) {
            return super.chooseNumber(sa, title, min, max);
        }
        int v = bridge.intInRange(TAG_NUMBER, min, max);
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
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
        int v = values.get(bridge.selectOne(TAG_NUMBER, valueLabels));
        Census.rec(getGame(), getPlayer(), "chooseNumber", "by", "bridge", "v", v);
        return v;
    }
}
