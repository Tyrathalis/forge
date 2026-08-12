package forge.ai.simulation;

import forge.ai.ComputerUtil;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.keyword.Keyword;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Probe: what does the AI tap-type chooser do when a Station activation's
 * only candidates are 0-power creatures?
 *
 * Provenance: the carried normal-game IndexOutOfBounds crash class
 * (dc-863943 Krang artifact decks, seed-pinned across every finalarm rerun;
 * captured stack run13-finalarm-s0 game 351). chooseTapType's size guard
 * runs BEFORE the STATION power filter strips 0-power candidates, so a
 * board of Ornithopter-class artifacts passed the guard and then indexed
 * past the shrunk list. The contract: decline (null) instead of throwing —
 * AiCostDecision already maps null to a clean payment cancel.
 */
public class StationTapCostTest extends SimulationTest {

    private static SpellAbility stationSa(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) {
            if (sa.isKeyword(Keyword.STATION)) {
                return sa;
            }
        }
        return null;
    }

    @Test
    public void testStationWithOnlyZeroPowerCandidatesDeclines() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        Card labship = addCard("Synthesizer Labship", p0);
        Card thopter = addCard("Ornithopter", p0); // the only creature: power 0
        thopter.setSickness(false);
        game.getAction().checkStateEffects(true);

        SpellAbility station = stationSa(labship);
        Assert.assertNotNull(station, "Labship should carry a Station SA");

        // Pre-fix: IndexOutOfBoundsException (guard passed on size 1, the
        // power filter emptied the list, get(0) threw). Post-fix: null.
        CardCollection picked = ComputerUtil.chooseTapType(
                p0, "Creature.Other", labship, true, 1, new CardCollection(), station);
        Assert.assertNull(picked, "0-power-only board must decline, not crash");
    }

    @Test
    public void testStationPrefersPositivePowerCandidate() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);

        Card labship = addCard("Synthesizer Labship", p0);
        Card thopter = addCard("Ornithopter", p0);
        Card bears = addCard("Grizzly Bears", p0);
        thopter.setSickness(false);
        bears.setSickness(false);
        game.getAction().checkStateEffects(true);

        SpellAbility station = stationSa(labship);
        Assert.assertNotNull(station);

        CardCollection picked = ComputerUtil.chooseTapType(
                p0, "Creature.Other", labship, true, 1, new CardCollection(), station);
        Assert.assertNotNull(picked);
        Assert.assertEquals(picked.size(), 1);
        Assert.assertEquals(picked.get(0).getName(), "Grizzly Bears",
                "the 0-power candidate must be filtered, the 2-power kept");
    }
}
