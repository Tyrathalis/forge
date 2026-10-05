package forge.ai.anvil;

import java.util.ArrayList;
import java.util.List;

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
 * Anvil ADR-0122 (2026-10-04, the agreement read's classes): the union
 * walks a modal spell's modes; a token's targeted ability lists its
 * candidates; X in any cost part unmasks an X-dependent restriction; a
 * parent-defined restriction is unmasked. Plus the cast-mask clause's own
 * class found by the same read: X spells must stay in the option mask.
 */
public class TargetUnionAgreementTest extends SimulationTest {

    private static List<String> names(List<SpellAbility> sas) {
        List<String> out = new ArrayList<>();
        for (SpellAbility sa : sas) {
            out.add(sa.getHostCard().getName());
        }
        return out;
    }

    @Test
    public void modalSpellListsItsModesTargets() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        Player opp = game.getPlayers().get(1);
        addCard("Mountain", p);
        addCard("Mountain", p);
        Card bear = addCard("Grizzly Bears", opp);
        Card relic = addCard("Sol Ring", opp);
        // "Choose one — Abrade deals 3 damage to target creature; or destroy target artifact."
        Card abrade = addCardToZone("Abrade", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility sa = abrade.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        TargetUnion.Union u = TargetUnion.of(game, p, sa);
        AssertJUnit.assertNotNull("unmasked: " + u.why, u.refs);
        AssertJUnit.assertTrue(u.refs.toString(), u.refs.contains("{\"e\":" + bear.getId() + "}"));
        AssertJUnit.assertTrue(u.refs.toString(), u.refs.contains("{\"e\":" + relic.getId() + "}"));
        AssertJUnit.assertEquals(0, u.min); // the modes' minimums are not the spell's
        AssertJUnit.assertFalse(u.unfit);
    }

    @Test
    public void staleBoundModeBelowACharmNeverMakesTheSpellUnfit() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        for (int i = 0; i < 4; i++) {
            addCard("Island", p);
        }
        Card bear = addCard("Grizzly Bears", game.getPlayers().get(1));
        Card cryptic = addCardToZone("Cryptic Command", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility sa = cryptic.getFirstSpellAbility();
        sa.setActivatingPlayer(p);
        // the AI's last evaluation leaves a mode bound below the Charm node:
        // "Counter target spell" on an empty stack
        List<forge.game.spellability.AbilitySub> modes = sa.getAdditionalAbilityList("Choices");
        AssertJUnit.assertTrue(modes.size() >= 4);
        for (forge.game.spellability.AbilitySub m : modes) {
            if (m.getApi() == forge.game.ability.ApiType.Counter) {
                sa.setSubAbility(m);
            }
        }
        AssertJUnit.assertNotNull(sa.getSubAbility());
        TargetUnion.Union u = TargetUnion.of(game, p, sa);
        AssertJUnit.assertNotNull("unmasked: " + u.why, u.refs);
        AssertJUnit.assertTrue(u.refs.toString(), u.refs.contains("{\"e\":" + bear.getId() + "}"));
        AssertJUnit.assertEquals(0, u.min);
        AssertJUnit.assertFalse("a bound mode must not flag the spell unfit", u.unfit);
    }

    @Test
    public void tokenAbilityListsTheControllersCreatures() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        addCard("Forest", p);
        Card bear = addCard("Grizzly Bears", p);
        Card map = addToken("c_a_map_sac_explore", p);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility explore = null;
        for (SpellAbility sa : map.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                explore = sa;
            }
        }
        AssertJUnit.assertNotNull(explore);
        explore.setActivatingPlayer(p);
        TargetUnion.Union u = TargetUnion.of(game, p, explore);
        AssertJUnit.assertNotNull("unmasked: " + u.why, u.refs);
        AssertJUnit.assertTrue("union " + u.refs + " unfit=" + u.unfit, u.refs.contains("{\"e\":" + bear.getId() + "}"));
        AssertJUnit.assertFalse(u.unfit);
    }

    @Test
    public void energyXAndParentDefinedRestrictionsAreUnmasked() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        addCard("Swamp", p);
        addCard("Mountain", p);
        addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        addCard("Grizzly Bears", game.getPlayers().get(1));
        Card nightmare = addCard("Chthonian Nightmare", p); // Pay X {E} ... target creature card with mana value X
        Card blaze = addCardToZone("Searing Blaze", p, ZoneType.Hand); // second target: creature that player controls
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        SpellAbility ret = null;
        for (SpellAbility sa : nightmare.getSpellAbilities()) {
            if (sa.isActivatedAbility()) {
                ret = sa;
            }
        }
        AssertJUnit.assertNotNull(ret);
        ret.setActivatingPlayer(p);
        AssertJUnit.assertEquals("x", TargetUnion.of(game, p, ret).why);
        SpellAbility sb = blaze.getFirstSpellAbility();
        sb.setActivatingPlayer(p);
        AssertJUnit.assertEquals("parent", TargetUnion.of(game, p, sb).why);
    }

    @Test
    public void xSpellsStayInTheOptionMask() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(0);
        for (int i = 0; i < 4; i++) {
            addCard("Forest", p);
            addCard("Island", p);
            addCard("Wastes", p); // {C} for Kozilek's Command's {X}{C}{C}
        }
        Card finale = addCardToZone("Finale of Devastation", p, ZoneType.Hand);
        Card command = addCardToZone("Kozilek's Command", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        for (Card c : new Card[] {finale, command}) {
            SpellAbility sa = c.getFirstSpellAbility();
            sa.setActivatingPlayer(p);
            AssertJUnit.assertTrue(c.getName() + " canPlay", sa.canPlay());
            AssertJUnit.assertTrue(c.getName() + " restrictions", CastPlanRealizer.passesRestrictions(game, p, sa));
        }
        AnvilOptions.invalidate(game, p);
        List<String> offered = names(AnvilOptions.priorityOptions(game, p));
        AssertJUnit.assertTrue(offered.toString(), offered.contains("Finale of Devastation"));
        AssertJUnit.assertTrue(offered.toString(), offered.contains("Kozilek's Command"));
    }
}
