package forge.ai.simulation;

import java.util.Map;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiCardMemory;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.Player;

/**
 * Anvil ADR-0110 (the 2026-09-16 upstream merge): the payment probe's
 * memory snapshot must cover EVERY memory set, including the typed
 * {@code MemorySetMana.UNPAID_COSTS} that upstream #11667 added on the
 * test-mode payment path — the set the old named list would have leaked.
 */
public class AiMemorySnapshotTest extends SimulationTest {

    @Test
    public void snapshotRestoresEveryMemorySetIncludingTypedOnes() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card forest = addCard("Forest", p);
        Card island = addCard("Island", p);

        AiCardMemory.rememberCard(p, forest, AiCardMemory.MemorySet.PAYS_TAP_COST);
        Map<AiCardMemory.MemoryType, Set> snap = AiCardMemory.snapshotAll(p);
        AssertJUnit.assertNotNull(snap);

        // the probe's writes: a card set the old list named, one it did not, and the typed mana set
        AiCardMemory.rememberCard(p, island, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2);
        AiCardMemory.rememberCard(p, island, AiCardMemory.MemorySet.REVEALED_CARDS);
        AiCardMemory.forgetCard(p, forest, AiCardMemory.MemorySet.PAYS_TAP_COST);
        Set<ManaCostBeingPaid> unpaid = AiCardMemory.getMemorySet(p, AiCardMemory.MemorySetMana.UNPAID_COSTS);
        unpaid.add(new ManaCostBeingPaid(ManaCost.get(3)));
        AssertJUnit.assertEquals(1, unpaid.size());

        AiCardMemory.restoreAll(p, snap);

        AssertJUnit.assertTrue("the pre-snapshot entry survives",
                AiCardMemory.isRememberedCard(p, forest, AiCardMemory.MemorySet.PAYS_TAP_COST));
        AssertJUnit.assertTrue("a named set's write is undone",
                AiCardMemory.isMemorySetEmpty(p, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2));
        AssertJUnit.assertTrue("an unnamed set's write is undone",
                AiCardMemory.isMemorySetEmpty(p, AiCardMemory.MemorySet.REVEALED_CARDS));
        AssertJUnit.assertTrue("the typed mana set's write is undone (the #11667 leak)",
                AiCardMemory.getMemorySet(p, AiCardMemory.MemorySetMana.UNPAID_COSTS).isEmpty());
    }
}
