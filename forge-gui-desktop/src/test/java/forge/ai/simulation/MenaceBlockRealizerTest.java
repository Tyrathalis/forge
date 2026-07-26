package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import forge.ai.anvil.CastPlanAnswer;
import forge.ai.anvil.CombatMapAnswer;
import forge.ai.anvil.CombatRealizer;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Probe: what does the bridged block realizer do when the model declares an
 * under-strength block against a min-blocker attacker?
 *
 * Provenance: run3-i000 g363 (Troll of Khazad-dûm, "can't be blocked except by
 * three or more creatures" = a MinMaxBlocker static) and g269 (Hive of the Eye
 * Tyrant, animated with menace). The upstream worklist recorded these as
 * "passes the realizer untouched, engine silently discards" — this test
 * establishes what actually happens, and pins the behavior we want: one illegal
 * assignment costs that assignment, not the whole window.
 */
public class MenaceBlockRealizerTest extends SimulationTest {

    private static CastPlanAnswer.Ref ref(Card c) {
        return new CastPlanAnswer.Ref(false, -1, c.getId(), false);
    }

    @Test
    public void testUnderStrengthBlockDoesNotCostTheWholeWindow() {
        Game game = initAndCreateGame();
        Player atk = game.getPlayers().get(0);
        Player def = game.getPlayers().get(1);

        // Min$ 3 attacker: one blocker on it is an illegal declaration.
        Card troll = addCard("Troll of Khazad-dûm", atk);
        Card giant = addCard("Hill Giant", atk);
        Card bear = addCard("Runeclaw Bear", def);
        Card bears = addCard("Grizzly Bears", def);

        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_BLOCKERS, atk);
        game.getAction().checkStateEffects(true);

        Combat combat = new Combat(atk);
        combat.initConstraints();
        combat.addAttacker(troll, def);
        combat.addAttacker(giant, def);

        // Model intent: one (illegal) block on the troll, one (legal) block on
        // the giant. Only the first is a rules violation.
        List<CombatMapAnswer.Assignment> as = new ArrayList<>();
        as.add(new CombatMapAnswer.Assignment(ref(bear), ref(troll)));
        as.add(new CombatMapAnswer.Assignment(ref(bears), ref(giant)));

        CombatRealizer.Result r = CombatRealizer.realizeBlock(game, def, combat,
                new CombatMapAnswer(as));

        System.out.println("[probe] applied=" + r.applied + " dropped=" + r.dropped
                + " forced=" + r.forced + " fallback=" + r.fallback);
        System.out.println("[probe] troll blockers=" + combat.getBlockers(troll)
                + " giant blockers=" + combat.getBlockers(giant));

        // The illegal declaration must not survive.
        AssertJUnit.assertFalse("under-strength block on a Min$ 3 attacker must not stand",
                combat.getBlockers(troll).contains(bear));

        // ...and the model's other, legal block must.
        AssertJUnit.assertTrue("the legal block on the vanilla attacker must survive",
                combat.getBlockers(giant).contains(bears));

        // One illegal assignment is a drop, not a surrender of the window to
        // the heuristic block controller.
        AssertJUnit.assertFalse("a single illegal assignment must not trip the wholesale fallback",
                r.fallback);
        AssertJUnit.assertEquals("the illegal assignment is counted as dropped", 1, r.dropped);
    }
}
