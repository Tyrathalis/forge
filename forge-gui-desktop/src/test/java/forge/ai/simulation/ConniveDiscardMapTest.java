package forge.ai.simulation;

import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import org.testng.annotations.Test;

/**
 * Probe: does a connive resolution survive SpellAbilityEffect.discard()?
 *
 * #11355 ("Recruit: add Effect") refactored ConniveEffect to hand discard() an
 * immutable Map.of(...), but discard() writes the post-move collection back
 * into that map (SpellAbilityEffect: discardedMap.put(p, discardedByPlayer)).
 * RecruitEffect, added by the same PR, has the identical call shape.
 */
public class ConniveDiscardMapTest extends SimulationTest {

    @Test
    public void testConniveResolvesWithoutImmutableMapCrash() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card shredder = addCard("Ledger Shredder", p);
        // connive = draw one, discard one: needs both a library and a hand
        addCardToZone("Mountain", p, ZoneType.Library);
        addCardToZone("Island", p, ZoneType.Library);
        addCardToZone("Runeclaw Bear", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility connive = AbilityFactory.getAbility(
                shredder.getSVar("TrigConnive"), shredder);
        connive.setActivatingPlayer(p);

        int handBefore = p.getCardsIn(ZoneType.Hand).size();
        connive.resolve();

        // Draw one, discard one: hand size is unchanged and the discard landed.
        System.out.println("[probe] hand " + handBefore + " -> "
                + p.getCardsIn(ZoneType.Hand).size()
                + ", graveyard " + p.getCardsIn(ZoneType.Graveyard).size());
    }
}
