package forge.ai.anvil;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil 2026-10-03: the priority option scan runs the realizer's
 * cast-restrictions clause. Spell.canPlay() admits Spider-Man 2099 on its
 * controller's first three turns (its CantBeCast static is checked by the
 * engine's AI in a second step), so the scan offered an option the realizer
 * then vetoed as "restrictions". The scan and the realizer must agree.
 */
public class OptionMaskRestrictionsTest extends SimulationTest {

    private static List<String> names(List<SpellAbility> sas) {
        List<String> out = new ArrayList<>();
        for (SpellAbility sa : sas) {
            out.add(sa.getHostCard().getName());
        }
        return out;
    }

    @Test
    public void cantBeCastStaticMasksTheOptionUntilItsTurn() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Island", p);
        addCard("Mountain", p);
        Card spidey = addCardToZone("Spider-Man 2099", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility sa = spidey.getFirstSpellAbility();
        sa.setActivatingPlayer(p);

        // the bug's shape: the engine's own canPlay admits the spell on turn <= 3 ...
        AssertJUnit.assertTrue("turn " + p.getTurn(), p.getTurn() <= 3);
        AssertJUnit.assertTrue(sa.canPlay());
        // ... the restrictions clause refuses it, and so must the option mask
        AssertJUnit.assertFalse(CastPlanRealizer.passesRestrictions(game, p, sa));
        AnvilOptions.invalidate(game, p);
        AssertJUnit.assertFalse(names(AnvilOptions.priorityOptions(game, p)).contains("Spider-Man 2099"));

        // from the controller's fourth turn the static is silent: offered again
        while (p.getTurn() <= 3) {
            p.incrementTurn();
        }
        AssertJUnit.assertTrue(CastPlanRealizer.passesRestrictions(game, p, sa));
        AnvilOptions.invalidate(game, p);
        AssertJUnit.assertTrue(names(AnvilOptions.priorityOptions(game, p)).contains("Spider-Man 2099"));
    }
}
