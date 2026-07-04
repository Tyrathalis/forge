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
 * Engine-legal priority options, materialized once per window (the
 * legal-actions-only invariant, ADR-0001). Shared by the bridged path
 * (PlayerControllerAnvil, M0) and the corpus label path (Obs.decPriority,
 * M1 D2) so both log the same legality basis: engine-legal, payable spell
 * abilities plus legal land drops. Pass is not an option here — callers
 * represent it themselves (index 0 on the bridge; a null answer in the log).
 */
public final class AnvilOptions {
    private AnvilOptions() {
    }

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
            if (!sa.isLandAbility() && sa.canPlay() && ComputerUtilCost.canPayCost(sa, player, false)) {
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
