package forge.ai.simulation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.anvil.AbilityKey;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Anvil M12 Build 3 (ADR-0105): the ability key is a deterministic function
 * of the ability's canonical text — the engine's script parameters, not the
 * display render — so abilities that read alike but resolve differently get
 * different keys, the same ability gets the same key every time, and the
 * offline enumeration of a card produces the keys its abilities carry in play.
 */
public class AbilityKeyTest extends SimulationTest {

    @Test
    public void manaAbilitiesDifferByProducedColor() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card forest = addCard("Forest", p);
        Card birds = addCard("Birds of Paradise", p);
        SpellAbility fsa = forest.getManaAbilities().get(0);
        SpellAbility bsa = birds.getManaAbilities().get(0);
        String fc = AbilityKey.canon(fsa);
        String bc = AbilityKey.canon(bsa);
        AssertJUnit.assertTrue(fc, fc.startsWith("H:Forest\n"));
        AssertJUnit.assertTrue(fc, fc.contains("Produced$G") || fc.contains("Produced$ G"));
        AssertJUnit.assertTrue(bc, bc.contains("Produced$Any") || bc.contains("Produced$ Any"));
        AssertJUnit.assertFalse(AbilityKey.key(fc).equals(AbilityKey.key(bc)));
        AssertJUnit.assertEquals(16, AbilityKey.key(fc).length());
        AssertJUnit.assertEquals(AbilityKey.key(fc), AbilityKey.key(AbilityKey.canon(fsa)));
    }

    @Test
    public void enumerationCoversTheAbilitiesInPlay() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card c = addCard("Llanowar Elves", p);
        Set<String> inPlay = new HashSet<>();
        for (SpellAbility sa : c.getSpellAbilities()) {
            inPlay.add(AbilityKey.key(AbilityKey.canon(sa)));
        }
        List<AbilityKey.Entry> dump = AbilityKey.enumerate(c);
        Set<String> dumped = new HashSet<>();
        for (AbilityKey.Entry e : dump) {
            dumped.add(e.key);
            AssertJUnit.assertEquals("Llanowar Elves", e.host);
        }
        AssertJUnit.assertTrue(dumped + " vs " + inPlay, dumped.containsAll(inPlay));
    }

    @Test
    public void modesAreDistinctKeys() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card c = addCardToZone("Heartless Act", p, forge.game.zone.ZoneType.Hand);
        List<AbilityKey.Entry> dump = AbilityKey.enumerate(c);
        int subs = 0;
        Set<String> keys = new HashSet<>();
        for (AbilityKey.Entry e : dump) {
            keys.add(e.key);
            if ("sub".equals(e.kind)) {
                subs++;
            }
        }
        AssertJUnit.assertEquals(dump.size(), keys.size());
        AssertJUnit.assertTrue("modes enumerated: " + subs, subs >= 2);
    }
}
