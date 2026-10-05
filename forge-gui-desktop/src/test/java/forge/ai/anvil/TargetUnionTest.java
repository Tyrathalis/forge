package forge.ai.anvil;

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
 * Anvil ADR-0122 (2026-10-04): the union target mask on priority options.
 * The union lists exactly what the engine lets the option target right now
 * — players and cards the node accepts, spells on the stack as stack refs —
 * and declares itself unmasked where the scan cannot know (X-dependent
 * restrictions, parent-defined targets).
 */
public class TargetUnionTest extends SimulationTest {

    private static String json(Game g, Player p, SpellAbility sa) {
        StringBuilder sb = new StringBuilder();
        TargetUnion.append(sb, g, p, sa);
        return sb.toString();
    }

    @Test
    public void burnListsEveryLegalTargetAndNoHexproofOne() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        addCard("Mountain", p);
        Card bear = addCard("Grizzly Bears", opp);
        Card troll = addCard("Thrun, the Last Troll", opp); // hexproof
        Card own = addCard("Grizzly Bears", p);
        Card bolt = addCardToZone("Lightning Bolt", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility sa = bolt.getFirstSpellAbility();
        sa.setActivatingPlayer(p);

        TargetUnion.Union u = TargetUnion.of(game, p, sa);
        AssertJUnit.assertNotNull(u.refs);
        AssertJUnit.assertTrue(u.refs.contains("{\"e\":" + bear.getId() + "}"));
        AssertJUnit.assertTrue(u.refs.contains("{\"e\":" + own.getId() + "}"));
        AssertJUnit.assertFalse(u.refs.contains("{\"e\":" + troll.getId() + "}"));
        AssertJUnit.assertTrue(u.refs.contains("{\"pi\":0}"));
        AssertJUnit.assertTrue(u.refs.contains("{\"pi\":1}"));
        AssertJUnit.assertEquals(1, u.min);
        AssertJUnit.assertFalse(u.unfit);
        // the enumeration leaves the option untargeted, as it found it
        AssertJUnit.assertTrue(sa.getTargets() == null || sa.getTargets().isEmpty());
        String s = json(game, p, sa);
        AssertJUnit.assertTrue(s, s.startsWith(",\"tg\":[") && s.contains("],\"tn\":1") && !s.contains("\"tz\""));
    }

    @Test
    public void nonTargetingSpellCarriesAnEmptyUnion() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility sa = bear.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        AssertJUnit.assertEquals(",\"tg\":[],\"tn\":0", json(game, p, sa));
    }

    @Test
    public void counterspellIsUnfitOnAnEmptyStackAndListsTheStackEntryOtherwise() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        addCard("Island", p);
        addCard("Island", p);
        Card counter = addCardToZone("Counterspell", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        SpellAbility sa = counter.getFirstSpellAbility();
        sa.setActivatingPlayer(p);

        TargetUnion.Union empty = TargetUnion.of(game, p, sa);
        AssertJUnit.assertNotNull(empty.refs);
        AssertJUnit.assertTrue(empty.refs.isEmpty());
        AssertJUnit.assertTrue(empty.unfit);

        addCard("Forest", opp);
        addCard("Forest", opp);
        Card bear = addCardToZone("Grizzly Bears", opp, ZoneType.Hand);
        SpellAbility cast = bear.getFirstSpellAbility();
        cast.setActivatingPlayer(opp);
        game.getAction().moveToStack(bear, cast);
        game.getStack().add(cast);

        TargetUnion.Union u = TargetUnion.of(game, p, sa);
        AssertJUnit.assertNotNull(u.refs);
        AssertJUnit.assertTrue(u.refs.toString(), u.refs.contains("{\"e\":" + bear.getId() + ",\"stk\":1}"));
        AssertJUnit.assertFalse(u.unfit);
    }

    @Test
    public void xDependentTargetRestrictionIsUnmaskedAndAPlainXCostIsNot() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        addCard("Swamp", p);
        addCard("Mountain", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        Card bear = addCard("Grizzly Bears", game.getPlayers().get(1));
        // X B B B: "any number of target creature cards ... with mana value X or less"
        Card awakening = addCardToZone("Agadeem's Awakening", p, ZoneType.Hand);
        // X R R: "target creature gets +X/+0" — X in the effect, not the restriction
        Card shoal = addCardToZone("Blazing Shoal", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);

        SpellAbility xRestricted = awakening.getFirstSpellAbility();
        xRestricted.setActivatingPlayer(p);
        AssertJUnit.assertEquals("x", TargetUnion.unmaskable(xRestricted, true));
        TargetUnion.Union u = TargetUnion.of(game, p, xRestricted);
        AssertJUnit.assertNull(u.refs);
        AssertJUnit.assertEquals("x", u.why);
        AssertJUnit.assertEquals(",\"tg\":null,\"tu\":\"x\"", json(game, p, xRestricted));

        SpellAbility xPlain = shoal.getFirstSpellAbility();
        xPlain.setActivatingPlayer(p);
        AssertJUnit.assertNull(TargetUnion.unmaskable(xPlain, true));
        TargetUnion.Union v = TargetUnion.of(game, p, xPlain);
        AssertJUnit.assertNotNull(v.refs);
        AssertJUnit.assertTrue(v.refs.contains("{\"e\":" + bear.getId() + "}"));
    }
}
