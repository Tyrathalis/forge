package forge.ai;

import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * ComputerUtilAbility.paysOffFor reads a cast trigger's ValidCard, which is a comma-separated list of restrictions.
 */
public class CastPayoffValidCardListTest extends AITest {

    private SpellAbility inHand(String name, Player ai) {
        SpellAbility sa = addCardToZone(name, ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    @Test
    public void aListOfTypesMatchesEachEntry() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        // "Whenever you cast an Aura, Equipment, or Vehicle spell, draw a card."
        SpellAbility sram = inHand("Sram, Senior Edificer", ai);
        SpellAbility equipment = inHand("Bonesplitter", ai);
        SpellAbility creature = inHand("Grizzly Bears", ai);

        AssertJUnit.assertTrue(ComputerUtilAbility.paysOffFor(sram, equipment));
        AssertJUnit.assertFalse(ComputerUtilAbility.paysOffFor(sram, creature));
    }

    @Test
    public void aListWithPropertiesDoesNotThrow() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        // "Whenever you cast an instant or sorcery spell from your library, copy it."
        // ValidCard$ Instant.wasCastFromYourLibrary,Sorcery.wasCastFromYourLibrary
        SpellAbility melek = inHand("Melek, Izzet Paragon", ai);
        SpellAbility bolt = inHand("Lightning Bolt", ai);

        // a spell in hand was not cast from the library
        AssertJUnit.assertFalse(ComputerUtilAbility.paysOffFor(melek, bolt));
    }
}
