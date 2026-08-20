package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.anvil.AnvilBridge;
import forge.ai.anvil.PaymentEnumerator;
import forge.ai.anvil.PlayerControllerAnvil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 D3 rung 2 wiring: PlayerControllerAnvil.payManaCost — the
 * consequential flag gates bridging, auto stays bit-identical, a class
 * answer executes directed and completes from the float
 * (m9-payment-surface-spec.md §1/§5/§7).
 */
public class PaymentWiringTest extends SimulationTest {

    /** Scripted SELECT_ONE bridge; records asks. */
    private static final class StubBridge implements AnvilBridge {
        int answer = 0;
        int asks = 0;
        List<String> lastLabels;
        String lastTag;

        @Override
        public int selectOne(String tag, List<String> optionLabels) {
            asks++;
            lastTag = tag;
            lastLabels = new ArrayList<>(optionLabels);
            return answer;
        }

        @Override
        public int[] selectK(String tag, int n, int k) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean bool(String tag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int intInRange(String tag, int min, int max) {
            throw new UnsupportedOperationException();
        }
    }

    private PlayerControllerAnvil controller(Player p, StubBridge bridge) {
        return new PlayerControllerAnvil(p.getGame(), p, p.getLobbyPlayer(), bridge,
                Set.of(PlayerControllerAnvil.TAG_PAY_CLASS));
    }

    private boolean pay(PlayerControllerAnvil pca, Card spell, Player p) {
        SpellAbility castSa = spell.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        return pca.payManaCost(castSa.getPayCosts().getTotalMana(),
                castSa.getPayCosts().getCostMana(), castSa, null, null, false);
    }

    /** One class ⇒ the bridge is never asked; auto pays (the sparsity contract). */
    @Test
    public void testNonConsequentialNeverBridges() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Forest", p);
        addCard("Forest", p);
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand); // {1}{G}
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        StubBridge bridge = new StubBridge();
        boolean paid = pay(controller(p, bridge), bear, p);

        AssertJUnit.assertTrue("auto pays the single-class window", paid);
        AssertJUnit.assertEquals("bridge never asked", 0, bridge.asks);
    }

    /** Consequential chained board: the class answer executes the directed
     *  chain (the payment the auto-payer cannot construct) and the window
     *  completes from the float. */
    @Test
    public void testClassAnswerExecutesChain() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Island", p);
        addCard("Island", p);
        Card signet = addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand); // {1}{U}{B}
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // find the chained class index the same way the controller enumerates
        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, castSa,
                castSa.getPayCosts().getTotalMana());
        AssertJUnit.assertTrue("forced-chain window is consequential",
                PaymentEnumerator.consequential(r, PaymentEnumerator.autoPayable(
                        p, castSa, castSa.getPayCosts().getTotalMana(), false)));
        int chainedIdx = -1;
        for (int i = 0; i < r.options.size(); i++) {
            for (PaymentEnumerator.Atom a : r.options.get(i).plan.atoms) {
                if (!a.activationMana.isZero()) {
                    chainedIdx = i;
                    break;
                }
            }
        }
        AssertJUnit.assertTrue("chained option surfaced", chainedIdx >= 0);

        StubBridge bridge = new StubBridge();
        bridge.answer = chainedIdx + 1; // option 0 = auto
        boolean paid = pay(controller(p, bridge), thief, p);

        AssertJUnit.assertTrue("directed chain pays the window", paid);
        AssertJUnit.assertEquals("bridged exactly once", 1, bridge.asks);
        AssertJUnit.assertEquals(PlayerControllerAnvil.TAG_PAY_CLASS, bridge.lastTag);
        AssertJUnit.assertEquals("auto + goal options on the wire",
                r.options.size() + 1, bridge.lastLabels.size());
        AssertJUnit.assertEquals("{\"auto\":true}", bridge.lastLabels.get(0));
        AssertJUnit.assertTrue("signet committed", signet.isTapped());
        AssertJUnit.assertEquals("no float residue", 0, p.getManaPool().totalMana());
    }

    /** The auto answer on the same board reproduces today's behavior exactly:
     *  the auto-payer cannot construct the chain, the window fails — the
     *  documented blind spot, now visible through the wire. */
    @Test
    public void testAutoAnswerKeepsBlindSpot() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        StubBridge bridge = new StubBridge();
        bridge.answer = 0; // auto
        boolean paid = pay(controller(p, bridge), thief, p);

        AssertJUnit.assertEquals(1, bridge.asks);
        AssertJUnit.assertFalse("auto cannot construct the chain", paid);
    }
}
