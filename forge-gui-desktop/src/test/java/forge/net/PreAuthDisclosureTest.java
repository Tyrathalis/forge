package forge.net;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

/**
 * F-03: lobby state must not reach a peer that has not logged in.
 *
 * <p>{@code channelActive} used to broadcast the full lobby before any
 * {@code LoginEvent}, and each {@link LobbySlot} carries a {@link Deck} — so
 * anyone who could reach the port could take every decklist in the lobby
 * without joining the game. Worth a regression test because the removed call is
 * exactly what someone would add back to fix a "lobby doesn't refresh on join"
 * report.
 *
 * <p>The companion case — that a logged-in client still receives lobby state —
 * is already covered by {@code NetworkPlayIntegrationTest}: its client cannot
 * connect at all without a {@code LobbyUpdateEvent} carrying a valid slot.
 */
public class PreAuthDisclosureTest {

    /** The old code broadcast within milliseconds of accept. */
    private static final long QUIET_SECONDS = 3;

    @Test(timeOut = 120_000, description = "F-03: an unauthenticated peer receives no lobby state")
    public void testUnauthenticatedPeerGetsNoLobbyState() throws Exception {
        NetworkTests.skipUnlessStressTestsEnabled();
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            // Give the host slot a real decklist — the thing worth stealing.
            lobby.getSlot(0).setDeck(TestDeckLoader.createMinimalDeck("Mountain", 10));
            Assert.assertNotNull(lobby.getSlot(0).getDeck(),
                    "Fixture must actually hold a deck, or this test proves nothing");

            // Connect and say nothing at all. No LoginEvent, no handshake.
            try (RawProtocolPeer eavesdropper = new RawProtocolPeer(port)) {
                Assert.assertFalse(
                        eavesdropper.gotAnyLobbyUpdate.await(QUIET_SECONDS, TimeUnit.SECONDS),
                        "Server pushed lobby state to a peer that never logged in");
                Assert.assertNull(eavesdropper.lobbyData.get(),
                        "Lobby data reached an unauthenticated peer");
            }
        } finally {
            server.stopServer();
        }
    }
}
