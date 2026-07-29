package forge.ai.simulation;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Probe: does GameCopier survive a game whose monarchy changed hands?
 *
 * Zone.remove() never clears the removed card's zone field, so after a
 * monarchy transfer the ex-monarch's cached monarchEffect card keeps a STALE
 * zone pointer. copyEffectCardsToSnapshot's guard tested the pointer
 * (getZone() != null) rather than zone-list membership, so every
 * GameCopier.makeCopy of such a game threw "Couldn't map The Monarch"
 * (found by the M4 D2 drill sweep: 44/578 curated fork positions failed all
 * K completions this way). Initiative has the identical transfer lifecycle
 * and shares the fix.
 */
public class MonarchTransferCopyTest extends SimulationTest {

    private static int countNamed(Player p, ZoneType zone, String name) {
        int n = 0;
        for (Card c : p.getCardsIn(zone)) {
            if (name.equals(c.getName())) {
                n++;
            }
        }
        return n;
    }

    @Test
    public void testCopyAfterMonarchyTransfer() {
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);

        game.getAction().becomeMonarch(p0, null);
        game.getAction().becomeMonarch(p1, null); // p0's cached effect card goes stale

        Game copy = new GameCopier(game).makeCopy(); // threw before the fix

        Player c0 = copy.getPlayers().get(0);
        Player c1 = copy.getPlayers().get(1);
        Assert.assertEquals(copy.getMonarch(), c1);
        Assert.assertEquals(countNamed(c0, ZoneType.Command, "The Monarch"), 0);
        Assert.assertEquals(countNamed(c1, ZoneType.Command, "The Monarch"), 1);
    }

    @Test
    public void testCopyAfterMonarchyRegained() {
        // Regaining re-adds the SAME cached card to the zone list; it must
        // map normally again (the fix keys on membership, not the pointer).
        Game game = initAndCreateGame();
        Player p0 = game.getPlayers().get(0);
        Player p1 = game.getPlayers().get(1);

        game.getAction().becomeMonarch(p0, null);
        game.getAction().becomeMonarch(p1, null);
        game.getAction().becomeMonarch(p0, null);

        Game copy = new GameCopier(game).makeCopy();
        Assert.assertEquals(copy.getMonarch(), copy.getPlayers().get(0));
        Assert.assertEquals(
                countNamed(copy.getPlayers().get(0), ZoneType.Command, "The Monarch"), 1);
        Assert.assertEquals(
                countNamed(copy.getPlayers().get(1), ZoneType.Command, "The Monarch"), 0);
    }
}
