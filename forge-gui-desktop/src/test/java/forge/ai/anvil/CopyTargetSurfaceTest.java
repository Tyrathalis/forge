package forge.ai.anvil;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * The target surface on a spell copy's new targets (ADR-0128): the AI picks them inside
 * PlayerControllerAi.chooseNewTargetsForCopy, and the preparedCopy hook offers the window to a
 * bridged seat under the chooseTargetsFor name.
 */
public class CopyTargetSurfaceTest extends SimulationTest {

    /** Answers a target window with the option whose label names the wanted card. */
    private static final class StubBridge implements AnvilBridge {
        String want;
        int asks = 0;
        String lastTag;
        List<String> lastLabels;

        @Override
        public int[] selectSet(String tag, List<String> optionLabels, int min, int max) {
            asks++;
            lastTag = tag;
            lastLabels = new ArrayList<>(optionLabels);
            for (int i = 0; i < optionLabels.size(); i++) {
                if (optionLabels.get(i).contains(want)) {
                    return new int[] { i };
                }
            }
            return null;
        }

        @Override
        public int selectOne(String tag, List<String> optionLabels) {
            throw new UnsupportedOperationException();
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

    private SpellAbility boltCopy(Game game, Player p, Card firstTarget) {
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        SpellAbility boltSa = bolt.getFirstSpellAbility();
        boltSa.setActivatingPlayer(p);
        boltSa.getTargets().add(firstTarget);
        Card twincast = addCardToZone("Twincast", p, ZoneType.Hand);
        SpellAbility twinSa = twincast.getFirstSpellAbility();
        twinSa.setActivatingPlayer(p);
        SpellAbility copy = CardFactory.copySpellAbilityAndPossiblyHost(twinSa, boltSa, p);
        copy.setMayChooseNewTargets(true);
        return copy;
    }

    private static GameEntity onlyTarget(SpellAbility sa) {
        AssertJUnit.assertEquals(1, sa.getTargets().size());
        return (GameEntity) sa.getTargets().get(0);
    }

    @Test
    public void aBridgedSeatRetargetsItsCopy() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        Card bears = addCard("Grizzly Bears", opp);
        Card ox = addCard("Giant Ox", opp); // 0/6: not what the heuristic bolts
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        StubBridge bridge = new StubBridge();
        bridge.want = "Giant Ox";
        PlayerControllerAnvil pca = new PlayerControllerAnvil(game, p, p.getLobbyPlayer(), bridge,
                Set.of(PlayerControllerAnvil.TAG_SURFACE_TARGET));
        p.dangerouslySetController(pca);

        SpellAbility copy = boltCopy(game, p, bears);
        List<SpellAbility> sas = new ArrayList<>();
        sas.add(copy);
        pca.orderAndPlaySimultaneousSa(sas);

        AssertJUnit.assertEquals("the copy's targets were offered once", 1, bridge.asks);
        AssertJUnit.assertEquals(PlayerControllerAnvil.TAG_SURFACE_TARGET, bridge.lastTag);
        AssertJUnit.assertTrue("several legal targets on the wire", bridge.lastLabels.size() >= 2);
        AssertJUnit.assertEquals("the bridged answer is the copy's target", ox, onlyTarget(copy));
        AssertJUnit.assertFalse("the copy is on the stack", game.getStack().isEmpty());
    }

    @Test
    public void anUnbridgedSeatKeepsTheHeuristicsPick() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        Card bears = addCard("Grizzly Bears", opp);
        addCard("Giant Ox", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        StubBridge bridge = new StubBridge();
        bridge.want = "Giant Ox";
        PlayerControllerAnvil pca = new PlayerControllerAnvil(game, p, p.getLobbyPlayer(), bridge, Set.of());
        p.dangerouslySetController(pca);

        SpellAbility copy = boltCopy(game, p, bears);
        List<SpellAbility> sas = new ArrayList<>();
        sas.add(copy);
        pca.orderAndPlaySimultaneousSa(sas);

        AssertJUnit.assertEquals("never asked", 0, bridge.asks);
        AssertJUnit.assertEquals(1, copy.getTargets().size());
        AssertJUnit.assertNotSame("the heuristic does not bolt a 0/6", "Giant Ox",
                ((GameEntity) copy.getTargets().get(0)).getName());
    }
}
