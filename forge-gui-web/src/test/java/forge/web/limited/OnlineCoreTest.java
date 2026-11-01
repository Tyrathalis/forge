package forge.web.limited;

import forge.StaticData;
import forge.deck.Deck;
import forge.gamemodes.limited.LimitedPoolType;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.EventFormat;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.web.WebTestSupport;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;

/** Tests the core entry points that online web events use and desktop leaves at their defaults. */
public class OnlineCoreTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    // Fails if configuring a sealed event from a built pool runs the prompting constructor. That constructor hangs until the time limit
    @Test(timeOut = 30_000)
    public void configureEventKeepsABuiltGenerator() {
        final ServerGameLobby lobby = new ServerGameLobby();
        lobby.createEvent(EventFormat.SEALED);
        final SealedCardPoolGenerator gen = SealedCardPoolGenerator.createFull(6);
        assertTrue(lobby.configureEvent(LimitedPoolType.Full, gen, 0, 0));
        assertSame(lobby.getCurrentEvent().getSealedGenerator(), gen);
    }

    // Fails if startGame seats a slot that sits out. Two ready seats with one sitting out are too few to play
    @Test
    public void startGameLeavesOutASeatSittingOut() {
        final ServerGameLobby lobby = new ServerGameLobby();
        final Deck deck = new Deck("Forests");
        deck.getMain().add(StaticData.instance().getCommonCards().getCard("Forest"), 40);
        for (int i = 0; i < 2; i++) {
            final LobbySlot slot = lobby.getSlot(i);
            slot.setType(LobbySlotType.AI);
            slot.setName("Seat " + i);
            slot.setDeck(deck);
            slot.setIsReady(true);
        }
        lobby.getSlot(1).setSittingOut(true);
        assertNull(lobby.startGame(), "a seat sitting out was counted as a player");
    }
}
