package forge.net;

import forge.deck.Deck;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

/**
 * F-03: lobby state must not reach a peer that has not logged in.
 *
 * <p>On {@code channelActive} the server registered the new channel and
 * immediately broadcast the full {@code GameLobbyData} — before the peer sent
 * a {@code LoginEvent}, before it held a slot, before any authorization at
 * all. Each {@link LobbySlot} carries a {@link Deck}, so anyone who could
 * reach the port could take every decklist and sideboard in the lobby: a
 * direct competitive advantage, obtainable without ever joining the game.
 *
 * <p>The assertion is on the decoded payload rather than on connection
 * behaviour, so it fails loudly if a redaction is ever weakened to send
 * "just the slot names" that still drag a Deck along by reference.
 */
public class PreAuthDisclosureTest implements IHasForgeLog {

    private static final long QUIET_SECONDS = 8;
    private static final long AWAIT_SECONDS = 15;

    /**
     * How many decklists a recipient could actually read out of this payload.
     * Goes through {@link ClientGameLobby} rather than poking at internals,
     * because that is precisely what an attacker would do with the bytes.
     */
    private static int decksVisible(final GameLobbyData data) {
        if (data == null) {
            return 0;
        }
        final ClientGameLobby probe = new ClientGameLobby();
        probe.setData(data);
        int n = 0;
        for (int i = 0; i < probe.getNumberOfSlots(); i++) {
            final LobbySlot slot = probe.getSlot(i);
            if (slot != null && slot.getDeck() != null) {
                n++;
            }
        }
        return n;
    }

    @Test(timeOut = 120_000, description = "F-03: an unauthenticated peer receives no lobby state")
    public void testUnauthenticatedPeerGetsNoLobbyState() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            // Give the host slot a real decklist — the thing worth stealing.
            final Deck hostDeck = TestDeckLoader.createMinimalDeck("Mountain", 10);
            lobby.getSlot(0).setDeck(hostDeck);
            Assert.assertNotNull(lobby.getSlot(0).getDeck(),
                    "Fixture must actually hold a deck, or this test proves nothing");

            // Connect and say nothing at all. No LoginEvent, no handshake.
            try (RawProtocolPeer eavesdropper = new RawProtocolPeer(port)) {
                final boolean gotAnything =
                        eavesdropper.gotAnyLobbyUpdate.await(QUIET_SECONDS, TimeUnit.SECONDS);

                Assert.assertFalse(gotAnything,
                        "Server pushed lobby state to a peer that never logged in; it contained "
                                + decksVisible(eavesdropper.lobbyData.get()) + " decklist(s)");
                Assert.assertEquals(decksVisible(eavesdropper.lobbyData.get()), 0,
                        "Decklists reached an unauthenticated peer");
            }
        } finally {
            server.stopServer();
        }
    }

    /**
     * The other half: gating pre-auth must not stop a client that has properly
     * joined from receiving the lobby it needs to render.
     */
    @Test(timeOut = 120_000, description = "F-03: a logged-in client still receives lobby state")
    public void testAuthenticatedClientStillGetsLobbyState() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);
            lobby.getSlot(0).setType(LobbySlotType.AI);
            lobby.getSlot(0).setDeck(TestDeckLoader.createMinimalDeck("Mountain", 10));

            try (RawProtocolPeer peer = new RawProtocolPeer(port)) {
                peer.login("Joiner", null);

                Assert.assertTrue(peer.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                        "A logged-in client must still receive lobby state — gating pre-auth "
                                + "disclosure must not break joining");
                Assert.assertTrue(peer.assignedSlot.get() >= 0,
                        "Joined client should hold a real slot");
            }
        } finally {
            server.stopServer();
        }
    }
}
