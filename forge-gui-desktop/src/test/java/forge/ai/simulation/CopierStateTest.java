package forge.ai.simulation;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * State a GameCopier copy used to drop (ADR-0128, addendum): an effect card's replacement effects
 * and triggers, the turn a card entered its zone, and the link from an exiled card to what exiled it.
 */
public class CopierStateTest extends SimulationTest {

    private static Card named(Player p, ZoneType zone, String name) {
        for (Card c : p.getCardsIn(zone)) {
            if (name.equals(c.getName())) {
                return c;
            }
        }
        return null;
    }

    /** CR 903.9b: a commander that would go to its owner's library may go to the command zone instead. */
    @Test
    public void commanderEffectKeepsItsReplacementEffect() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card commander = addCard("Grizzly Bears", p);
        p.addCommander(commander);
        game.getAction().checkStateEffects(true);

        Game copy = new GameCopier(game).makeCopy();
        Player cp = copy.getPlayers().get(1);
        Card cc = named(cp, ZoneType.Battlefield, "Grizzly Bears");
        Card effect = named(cp, ZoneType.Command, "Commander Effect");
        Assert.assertNotNull(effect);
        Assert.assertEquals(effect.getReplacementEffects().size(), 1);

        game.getAction().moveToLibrary(commander, 0, null);
        copy.getAction().moveToLibrary(cc, 0, null);
        Assert.assertEquals(game.getCardState(commander).getZone().getZoneType(), ZoneType.Command);
        Assert.assertEquals(copy.getCardState(cc).getZone().getZoneType(), ZoneType.Command);
    }

    @Test
    public void aCardKeepsTheTurnItEnteredItsZone() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card old = addCard("Grizzly Bears", p);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p, 5);
        old.setTurnInZone(2);
        Card fresh = addCard("Runeclaw Bear", p);
        fresh.setTurnInZone(5);
        Assert.assertFalse(old.enteredThisTurn());
        Assert.assertTrue(fresh.enteredThisTurn());

        Game copy = new GameCopier(game).makeCopy();
        Player cp = copy.getPlayers().get(1);
        Assert.assertFalse(named(cp, ZoneType.Battlefield, "Grizzly Bears").enteredThisTurn());
        Assert.assertTrue(named(cp, ZoneType.Battlefield, "Runeclaw Bear").enteredThisTurn());
    }

    @Test
    public void anExiledCardKeepsWhatExiledIt() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        Card bridge = addCard("Mosswort Bridge", p);
        Card hidden = addCardToZone("Craterhoof Behemoth", p, ZoneType.Exile);
        hidden.setExiledWith(bridge);
        hidden.setExiledBy(p);
        bridge.addExiledCard(hidden);
        game.getAction().checkStateEffects(true);

        Game copy = new GameCopier(game).makeCopy();
        Player cp = copy.getPlayers().get(1);
        Card cBridge = named(cp, ZoneType.Battlefield, "Mosswort Bridge");
        Card cHidden = named(cp, ZoneType.Exile, "Craterhoof Behemoth");
        Assert.assertSame(cHidden.getExiledWith(), cBridge);
        Assert.assertSame(cHidden.getExiledBy(), cp);
        Assert.assertEquals(cBridge.getExiledCards().size(), 1);
        Assert.assertSame(cBridge.getExiledCards().getFirst(), cHidden);
    }
}
