package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ComputerUtil;
import forge.ai.anvil.PaymentEnumerator;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 D3 rung 2: unit tests for the legality-derived payment-class
 * enumeration + directed executor (m9-payment-surface-spec.md §10;
 * capability audit ADR-0065 = DirectedPaymentAuditTest).
 */
public class PaymentEnumeratorTest extends SimulationTest {

    private Player setUp(Game game) {
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        return p;
    }

    private PaymentEnumerator.Result enumerate(Player p, Card spellInHand) {
        SpellAbility castSa = spellInHand.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        return PaymentEnumerator.enumerate(p, castSa,
                castSa.getPayCosts().getTotalMana());
    }

    /** Single basic vs {G}: one class, never consequential, never bridges. */
    @Test
    public void testSingleSourceNotConsequential() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Forest", p);
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand); // {1}{G} — unpayable, but enumeration is over classes for the G shard
        addCard("Forest", p); // second forest, same class
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, bear);
        AssertJUnit.assertEquals("two same-signature forests = one source class", 1, r.sourceClassCount);
        AssertJUnit.assertEquals("one payment class", 1, r.classes.size());
        SpellAbility bearSa = bear.getFirstSpellAbility();
        bearSa.setActivatingPlayer(p);
        AssertJUnit.assertFalse("one class + auto-payable = not consequential",
                PaymentEnumerator.consequential(r, p, bearSa, bearSa.getPayCosts().getTotalMana(), false));
        AssertJUnit.assertFalse(r.truncated);
    }

    /** Dork vs land for {G}: residual-relevance splits the classes — the
     *  dork-as-blocker distinction the surface exists to expose. */
    @Test
    public void testCreatureVsLandSplitsClasses() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Forest", p);
        Card elves = addCard("Llanowar Elves", p);
        elves.setSickness(false);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand); // {1}{G}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, bear);
        AssertJUnit.assertEquals("creature and land are distinct source classes", 2, r.sourceClassCount);
        // {1}{G} from {Forest, Elves}: both tapped in every full payment — one class.
        // The split shows on the G shard alone:
        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result rG = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("G")));
        AssertJUnit.assertEquals("paying {G} via dork vs land = two classes = consequential",
                2, rG.classes.size());
    }

    /** The ADR-0065 chained board: I + I + Dimir Signet vs {1}{U}{B}.
     *  Enumeration must surface the chained class the auto-payer cannot
     *  construct, and the directed executor must execute it. */
    @Test
    public void testChainedClassEnumeratedAndExecutes() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand); // {1}{U}{B}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, thief);
        AssertJUnit.assertTrue("chained class found", r.classes.size() >= 1);
        PaymentEnumerator.PaymentClass chained = null;
        for (PaymentEnumerator.PaymentClass pc : r.classes) {
            for (PaymentEnumerator.Atom a : pc.atoms) {
                if (!a.activationMana.isZero()) {
                    chained = pc;
                    break;
                }
            }
        }
        AssertJUnit.assertNotNull("a class uses the Signet (nonzero activation mana)", chained);
        AssertJUnit.assertEquals("all three sources committed", 3, chained.atoms.size());

        // Directed execution: float the plan, then cast from the float.
        PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, chained);
        AssertJUnit.assertEquals(PaymentEnumerator.ExecOutcome.DIRECTED_OK, out);
        AssertJUnit.assertEquals("plan floats exactly the cost", 3, p.getManaPool().totalMana());

        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        AssertJUnit.assertTrue("cast completes from the float",
                ComputerUtil.handlePlayingSpellAbility(p, castSa, null));
        playUntilStackClear(game);
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Thief of Sanity"));
        AssertJUnit.assertEquals(0, p.getManaPool().totalMana());
    }

    /** Yield-differing taps are distinct classes by definition (the D2a
     *  pin): an Overgrowth-enchanted forest is not a forest. */
    @Test
    public void testBoostedYieldSplitsClasses() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        Card f1 = addCard("Forest", p);
        addCard("Forest", p);
        Card overgrowth = addCard("Overgrowth", p);
        overgrowth.attachToEntity(f1, null, true);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result rG = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("G")));
        AssertJUnit.assertEquals("boosted vs plain forest = two source classes", 2, rG.sourceClassCount);
        AssertJUnit.assertEquals("yield difference is consequential", 2, rG.classes.size());
    }

    /** Phyrexian shards: pay-mana vs pay-life is a class distinction (a
     *  named D1 auto-payer artifact family). */
    @Test
    public void testPhyrexianLifeVsManaSplitsClasses() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        Card probe = addCardToZone("Gitaxian Probe", p, ZoneType.Hand); // {U/P}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, probe);
        AssertJUnit.assertEquals("island-pay and life-pay classes", 2, r.classes.size());
        boolean sawLife = false, sawMana = false;
        for (PaymentEnumerator.PaymentClass pc : r.classes) {
            if (pc.phyrexianLife > 0) {
                sawLife = true;
            } else {
                sawMana = true;
            }
        }
        AssertJUnit.assertTrue(sawLife && sawMana);
    }

    /** K_MAX truncation is loud: a wide board of distinct signatures vs a
     *  generic cost overflows 8 classes and flags it. */
    @Test
    public void testTruncationIsFlagged() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        // six distinct source classes
        addCard("Forest", p);
        addCard("Island", p);
        addCard("Mountain", p);
        addCard("Swamp", p);
        addCard("Sol Ring", p);
        Card bop = addCard("Birds of Paradise", p);
        bop.setSickness(false);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("2")));
        AssertJUnit.assertEquals("capped at K_MAX", PaymentEnumerator.K_MAX, r.classes.size());
        AssertJUnit.assertTrue("truncation flagged, never silent", r.truncated);
    }
}
