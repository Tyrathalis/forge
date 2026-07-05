package forge.ai.anvil;

import com.google.common.collect.Lists;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.List;

/**
 * Priority option candidates, materialized once per window (the
 * legal-actions-only invariant, ADR-0001). Shared by the bridged path
 * (PlayerControllerAnvil, M0) and the corpus label path (Obs.decPriority,
 * M1 D2) so both log the same basis: timing-legal spell abilities (see
 * PAYCHECK note — payability is deliberately NOT filtered) plus legal land
 * drops. Pass is not an option here — callers represent it themselves
 * (index 0 on the bridge; a null answer in the log).
 */
public final class AnvilOptions {
    private AnvilOptions() {
    }

    /**
     * The logged option set is TIMING-LEGAL CANDIDATES, not payable actions
     * (M1 D3 decision). Exact payability is not cheaply computable at scan
     * time: cost reductions/additional costs are priced only after the AI's
     * canPlaySa sets up targets and X ("can only be checked late" —
     * AiController.canPlayAndPayForFace), which is why the old canPayCost
     * filter both diverged from the expert's own picks (Mystical Dispute,
     * Dargo, X spells — D3's 320-game validation, 11 errors) and duplicated
     * the AI's most expensive work per window. canPlay() is the same
     * predicate the AI itself requires (Spell.canPlay == canPlayFromHost
     * != null), so the set is a superset of the expert's castable actions
     * by construction; affordability is the model's to learn (it must price
     * costs anyway to emit CastPlans). -Danvil.scan.paycheck=on restores the
     * old filter for comparison runs only.
     */
    private static final boolean PAYCHECK =
            "on".equals(System.getProperty("anvil.scan.paycheck", "off"));

    public static List<SpellAbility> priorityOptions(Game game, Player player) {
        List<SpellAbility> options = Lists.newArrayList();
        CardCollection cards = ComputerUtilCard.dedupeCards(ComputerUtilAbility.getAvailableCards(game, player));
        // getOriginalAndAltCostAbilities is the AI's own iteration set
        // (AiController.chooseSpellAbilityToPlay): it re-expands the
        // alternative/additional-cost variants that getSpellAbilities
        // collapses, so each variant gets its own payability check — a spell
        // payable ONLY via its alternative cost (e.g. Snuff Out's 4 life)
        // must appear as an option or the logged legality mask would forbid
        // the heuristic's own pick (found by the D2 smoke validator).
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                ComputerUtilAbility.getSpellAbilities(cards, player), player)) {
            if (!sa.isLandAbility() && sa.canPlay()
                    && (!PAYCHECK || ComputerUtilCost.canPayCost(sa, player, false))) {
                options.add(sa);
            }
        }
        CardCollectionView lands = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
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
        return options;
    }
}
