package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ComputerUtilMana;
import forge.ai.anvil.PayDirective;
import forge.ai.anvil.PaymentEnumerator;
import forge.ai.anvil.PaymentTelemetry;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 rung 3: PayDirective — the certification harness's per-Game
 * payment directive (m9-rung3-draft.md). The consult site is
 * PaymentTelemetry.rec, so each test mirrors the census controller's
 * exact sequence: rec (directive check-and-execute + record), then the
 * normal heuristic payment, which completes from any directed float
 * pool-first (the PlayerControllerAnvil.payManaCost bridged semantics).
 */
public class PaymentCertifyTest extends SimulationTest {

    /** The census controller's payment step after rec: PlayerControllerAi's
     *  body via ComputerUtilMana (the PlayerControllerAnvil.autoPay shape). */
    private boolean heuristicPay(Player p, SpellAbility castSa, ManaCost toPay) {
        return ComputerUtilMana.payManaCost(new Cost(toPay, false), p, castSa, false);
    }

    /** 3 Islands + Dimir Signet paying {1}{U}: the spare(Island) argmax is
     *  the chained Signet plan the auto-payer cannot construct. */
    private Game chainBoard() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        addCardToZone("Merfolk Looter", p, ZoneType.Hand); // {1}{U}
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        return game;
    }

    private Card find(Game game, Player p, String name, ZoneType zone) {
        for (Card c : p.getCardsIn(zone)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /** The directive fires on the matching window and executes the chosen
     *  option: the Signet chain commits, the heuristic completes payment
     *  from the float. */
    @Test
    public void testDirectiveFiresAndExecutesChosenOption() {
        Game game = chainBoard();
        Player p = game.getPlayers().get(1);
        Card signet = find(game, p, "Dimir Signet", ZoneType.Battlefield);
        Card looter = find(game, p, "Merfolk Looter", ZoneType.Hand);

        SpellAbility castSa = looter.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        ManaCost toPay = castSa.getPayCosts().getTotalMana();

        // the chained option's index, enumerated as the directive will
        PaymentEnumerator.Result probe = PaymentEnumerator.enumerate(p, castSa, toPay);
        int chained = -1;
        for (int i = 0; i < probe.options.size(); i++) {
            for (PaymentEnumerator.Atom a : probe.options.get(i).plan.atoms) {
                if (!a.activationMana.isZero()) {
                    chained = i;
                }
            }
        }
        AssertJUnit.assertTrue("chained option surfaced", chained >= 0);

        int turn = game.getPhaseHandler().getTurn();
        PayDirective d = PayDirective.armPayDirective(game, p.getName(), turn,
                "Merfolk Looter", 0, chained + 1);
        boolean was = PaymentTelemetry.enabled;
        PaymentTelemetry.enabled = true;
        try {
            PaymentTelemetry.rec(game, p, toPay, castSa, null, false);
        } finally {
            PaymentTelemetry.enabled = was;
        }

        AssertJUnit.assertTrue("directive fired", d.fired);
        AssertJUnit.assertEquals("directed_ok", d.exec);
        AssertJUnit.assertEquals(turn, d.tFired);
        AssertJUnit.assertEquals(probe.options.size(), d.availOptions);
        AssertJUnit.assertNotNull("goal labels recorded", d.goals);
        AssertJUnit.assertEquals(d.goals.size(), d.kinds.size());
        AssertJUnit.assertTrue("signet committed by the directed chain", signet.isTapped());

        AssertJUnit.assertTrue("payment completes from the float",
                heuristicPay(p, castSa, toPay));
        AssertJUnit.assertEquals("no float residue", 0, p.getManaPool().totalMana());
        int untappedIslands = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if ("Island".equals(c.getName()) && !c.isTapped()) {
                untappedIslands++;
            }
        }
        AssertJUnit.assertEquals("chain spares two Islands", 2, untappedIslands);
        PayDirective.clear(game);
    }

    /** Arm 0 / unarmed = identical heuristic behavior: no directive record
     *  when unarmed, no game mutation from the directive, payment succeeds
     *  through the normal path. */
    @Test
    public void testArmZeroAndUnarmedAreHeuristicIdentical() {
        Game game = chainBoard();
        Player p = game.getPlayers().get(1);
        Card signet = find(game, p, "Dimir Signet", ZoneType.Battlefield);
        Card looter = find(game, p, "Merfolk Looter", ZoneType.Hand);

        SpellAbility castSa = looter.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        ManaCost toPay = castSa.getPayCosts().getTotalMana();

        boolean was = PaymentTelemetry.enabled;
        PaymentTelemetry.enabled = true;
        try {
            // unarmed window: no directive record, nothing touched
            PaymentTelemetry.rec(game, p, toPay, castSa, null, false);
            AssertJUnit.assertNull("no directive record when unarmed",
                    PayDirective.directive(game));
            AssertJUnit.assertFalse(signet.isTapped());
            AssertJUnit.assertEquals(0, p.getManaPool().totalMana());

            // arm 0: the directive fires as auto and touches nothing
            int turn = game.getPhaseHandler().getTurn();
            PayDirective d = PayDirective.armPayDirective(game, p.getName(), turn,
                    "Merfolk Looter", 0, 0);
            PaymentTelemetry.rec(game, p, toPay, castSa, null, false);
            AssertJUnit.assertTrue("arm 0 fires", d.fired);
            AssertJUnit.assertEquals("auto", d.exec);
            AssertJUnit.assertTrue(d.availOptions >= 1);
            AssertJUnit.assertNull("no goal labels on auto", d.goals);
            AssertJUnit.assertFalse("arm 0 touches nothing", signet.isTapped());
            AssertJUnit.assertEquals(0, p.getManaPool().totalMana());
        } finally {
            PaymentTelemetry.enabled = was;
        }

        AssertJUnit.assertTrue("heuristic pays", heuristicPay(p, castSa, toPay));
        AssertJUnit.assertFalse("heuristic never taps the signet", signet.isTapped());
        PayDirective.clear(game);
    }

    /** Pick beyond the surfaced options: fired=false / no_such_option, the
     *  heuristic pays untouched. */
    @Test
    public void testPickBeyondOptionsFallsThroughToHeuristic() {
        Game game = chainBoard();
        Player p = game.getPlayers().get(1);
        Card signet = find(game, p, "Dimir Signet", ZoneType.Battlefield);
        Card looter = find(game, p, "Merfolk Looter", ZoneType.Hand);

        SpellAbility castSa = looter.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        ManaCost toPay = castSa.getPayCosts().getTotalMana();

        int turn = game.getPhaseHandler().getTurn();
        PayDirective d = PayDirective.armPayDirective(game, p.getName(), turn,
                "Merfolk Looter", 0, 99);
        boolean was = PaymentTelemetry.enabled;
        PaymentTelemetry.enabled = true;
        try {
            PaymentTelemetry.rec(game, p, toPay, castSa, null, false);
        } finally {
            PaymentTelemetry.enabled = was;
        }

        AssertJUnit.assertTrue("window reached", d.resolved);
        AssertJUnit.assertFalse("no such option", d.fired);
        AssertJUnit.assertEquals("no_such_option", d.resolvedReason());
        AssertJUnit.assertTrue("avail recorded", d.availOptions >= 1);
        AssertJUnit.assertFalse(signet.isTapped());
        AssertJUnit.assertEquals(0, p.getManaPool().totalMana());

        AssertJUnit.assertTrue("heuristic pays", heuristicPay(p, castSa, toPay));
        PayDirective.clear(game);
    }
}
