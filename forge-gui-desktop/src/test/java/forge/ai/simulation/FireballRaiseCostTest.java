package forge.ai.simulation;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Probe: does Fireball's "costs {1} more for each target beyond the first"
 * RaiseCost static (Amount$ IncreaseCost, Relative$ True) actually apply?
 */
public class FireballRaiseCostTest extends SimulationTest {

    private ManaCostBeingPaid costForTargets(Game game, SpellAbility sa, Player p, Card... targets) {
        sa.resetTargets();
        for (Card t : targets) {
            sa.getTargets().add(t);
        }
        // test=true, extraMana=2 -> X counted as 2
        return ComputerUtilMana.calculateManaCost(sa.getPayCosts(), sa, p, true, 2, false);
    }

    @Test
    public void testFireballCostsOneMorePerExtraTarget() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);

        addCards("Mountain", 10, p);
        Card bear1 = addCard("Runeclaw Bear", opp);
        Card bear2 = addCard("Grizzly Bears", opp);
        Card fireball = addCardToZone("Fireball", p, ZoneType.Hand);

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility sa = fireball.getFirstSpellAbility();
        sa.setActivatingPlayer(p);

        int oneTarget = costForTargets(game, sa, p, bear1).getConvertedManaCost();
        int twoTargets = costForTargets(game, sa, p, bear1, bear2).getConvertedManaCost();

        // Base X R with X=2 -> 3. Two targets should cost exactly one more.
        AssertJUnit.assertEquals("one-target cost", 3, oneTarget);
        AssertJUnit.assertEquals("second target should add {1}", oneTarget + 1, twoTargets);
    }
}
