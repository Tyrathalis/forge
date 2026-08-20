package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiCostDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilMana;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.cost.CostPayment;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 D3 rung-1 engine capability audit (2026-08-19).
 *
 * Question: can the engine EXECUTE a directed mana payment it is handed,
 * including chained-activation payments (mana abilities that themselves
 * cost mana, e.g. Signets), which ComputerUtilMana cannot CONSTRUCT?
 *
 * The directed-activation primitive under test is exactly the execution
 * body of ComputerUtilMana.payManaCost (pay the payment-SA's own costs
 * via CostPayment.payComputerCosts, then stack.addAndUnfreeze) — with the
 * heuristic chooser replaced by an explicit, externally-supplied order.
 */
public class DirectedPaymentAuditTest extends SimulationTest {

    /** The execution primitive a directed payment executor would use:
     *  identical to ComputerUtilMana.payManaCost's non-test execution body,
     *  minus the heuristic chooser. */
    private boolean directedActivate(Player p, SpellAbility ma) {
        ma.setActivatingPlayer(p);
        final CostPayment pay = new CostPayment(ma.getPayCosts(), ma);
        if (!pay.payComputerCosts(new AiCostDecision(p, ma, false, true))) {
            return false;
        }
        p.getGame().getStack().addAndUnfreeze(ma);
        return true;
    }

    private SpellAbility firstManaAbility(Card c) {
        return c.getManaAbilities().iterator().next();
    }

    /** Negative control: the heuristic cannot construct the chained payment
     *  (Signet is excluded from its candidate set — getAIPlayableMana skips
     *  mana abilities with mana costs), even though the board is
     *  arithmetically sufficient. */
    @Test
    public void testHeuristicCannotConstructChainedPayment() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);

        // {1}{U}{B} with I, I, Signet: payable via I -> {U} -> activate
        // Signet -> {U}{B} float -> I. The heuristic says no.
        AssertJUnit.assertFalse("heuristic should NOT see the chained payment",
                ComputerUtilMana.canPayManaCost(castSa, p, 0, false));
    }

    /** Positive control for the negative control: same cost, chain replaced
     *  by a plain source — the heuristic pays fine, so the refusal above is
     *  the chain, not something else about the spell. */
    @Test
    public void testHeuristicPaysWithoutChain() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        addCard("Island", p);
        addCard("Island", p);
        addCard("Swamp", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);

        AssertJUnit.assertTrue("heuristic should pay I/I/Swamp",
                ComputerUtilMana.canPayManaCost(castSa, p, 0, false));
    }

    /** The core capability claim: a DIRECTED chained activation executes.
     *  Island -> {U} floating; Signet activation pays its own {1} from the
     *  floating {U} through CostPartMana.payAsDecided -> controller
     *  .payManaCost (one-level nesting, the production flow); Island #2.
     *  Then the spell is cast through the real AI cast path, paid entirely
     *  from the directed float. */
    @Test
    public void testDirectedChainedPaymentExecutesAndCasts() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card i1 = addCard("Island", p);
        Card i2 = addCard("Island", p);
        Card signet = addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Directed sequence the heuristic cannot construct:
        AssertJUnit.assertTrue("island 1 activation",
                directedActivate(p, firstManaAbility(i1)));
        AssertJUnit.assertEquals(1, p.getManaPool().totalMana());

        AssertJUnit.assertTrue("signet activation (nested {1} paid from float)",
                directedActivate(p, firstManaAbility(signet)));
        AssertJUnit.assertTrue("signet tapped", signet.isTapped());
        // {U} spent on the signet, {U}{B} produced:
        AssertJUnit.assertEquals(2, p.getManaPool().totalMana());
        AssertJUnit.assertEquals(1, p.getManaPool().getAmountOfColor(MagicColor.BLACK));

        AssertJUnit.assertTrue("island 2 activation",
                directedActivate(p, firstManaAbility(i2)));
        AssertJUnit.assertEquals(3, p.getManaPool().totalMana());

        // The float now covers {1}{U}{B}; the real cast path pays from pool.
        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        AssertJUnit.assertTrue("cast from directed float",
                ComputerUtil.handlePlayingSpellAbility(p, castSa, null));
        playUntilStackClear(game);

        AssertJUnit.assertEquals("Thief resolved to battlefield",
                1, countCardsWithName(game, "Thief of Sanity"));
        AssertJUnit.assertEquals(0, p.getManaPool().totalMana());
    }

    /** Color direction: an externally set express choice steers an
     *  any-color producer (the ManaEffect resolution path constrains the
     *  chooseColor menu to the express mask). */
    @Test
    public void testExpressChoiceDirectsAnyColorProducer() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);

        Card bop = addCard("Birds of Paradise", p);
        bop.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        SpellAbility ma = firstManaAbility(bop);
        ma.getManaPart().setExpressChoice("B");
        AssertJUnit.assertTrue("directed BoP activation", directedActivate(p, ma));

        AssertJUnit.assertEquals("produced the directed color",
                1, p.getManaPool().getAmountOfColor(MagicColor.BLACK));
    }
}
