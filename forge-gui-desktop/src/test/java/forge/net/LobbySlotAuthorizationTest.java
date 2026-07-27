package forge.net;

import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * F-04: a client may configure its own seat, but not the server-owned state
 * that decides who occupies it.
 *
 * <p>The server applied every field of a client-supplied
 * {@code UpdateLobbyPlayerEvent} to the sender's slot with no field-level
 * authorization. Slot <b>type</b> is lifecycle state the server owns —
 * {@code connectPlayer} sets REMOTE, {@code disconnectPlayer} sets OPEN — so a
 * client that marked its own occupied slot OPEN kept its slot index while the
 * server handed the same index to the next joiner. Two channels, one seat.
 *
 * <p>The honest client cannot do this: the slot-type control is gated on
 * {@code mayControl()}, which {@code ClientGameLobby} answers false. That is
 * precisely why the test uses a {@link RawProtocolPeer} — client-side gating
 * is not enforcement, and the wire accepts whatever an attacker sends.
 *
 * <p>Assertions read the server's own lobby object directly rather than
 * inferring state from broadcasts, so they cannot pass for an incidental
 * reason.
 */
public class LobbySlotAuthorizationTest implements IHasForgeLog {

    private static final long AWAIT_SECONDS = 15;

    /** A crafted event of the kind no honest client constructs. */
    private static UpdateLobbyPlayerEvent forgedTypeChange(final LobbySlotType type) {
        return UpdateLobbyPlayerEvent.create(type, null, -1, -1, -1, false, false,
                Collections.emptySet(), null);
    }

    @Test(timeOut = 120_000, description = "F-04: a client must not change its own slot type")
    public void testClientCannotChangeItsOwnSlotType() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            try (RawProtocolPeer peer = new RawProtocolPeer(port)) {
                peer.login("Squatter", null);
                Assert.assertTrue(peer.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                        "Peer should have been assigned a slot");
                final int slot = peer.assignedSlot.get();
                Assert.assertEquals(lobby.getSlot(slot).getType(), LobbySlotType.REMOTE,
                        "A joined client's slot should be REMOTE");

                peer.send(forgedTypeChange(LobbySlotType.OPEN));
                Thread.sleep(2000);

                Assert.assertEquals(lobby.getSlot(slot).getType(), LobbySlotType.REMOTE,
                        "Client rewrote its own slot type to OPEN — the seat is now claimable "
                                + "by the next joiner while this client keeps driving it");
            }
        } finally {
            server.stopServer();
        }
    }

    /**
     * The consequence the type rewrite buys: two live channels mapped to one
     * seat. Asserted end-to-end rather than by inspecting the type alone,
     * because the duplicate seat is the actual harm.
     */
    @Test(timeOut = 120_000, description = "F-04: a forged type change must not produce a duplicate seat")
    public void testForgedTypeChangeCannotProduceDuplicateSeat() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            try (RawProtocolPeer squatter = new RawProtocolPeer(port)) {
                squatter.login("Squatter", null);
                Assert.assertTrue(squatter.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS));
                final int squatterSlot = squatter.assignedSlot.get();

                // Re-open the seat we are sitting in.
                squatter.send(forgedTypeChange(LobbySlotType.OPEN));
                Thread.sleep(2000);

                try (RawProtocolPeer newcomer = new RawProtocolPeer(port)) {
                    newcomer.login("Newcomer", null);
                    final boolean assigned =
                            newcomer.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS);

                    Assert.assertFalse(assigned && newcomer.assignedSlot.get() == squatterSlot,
                            "Newcomer was assigned slot " + squatterSlot + ", already held by the "
                                    + "squatter — two channels now drive one seat");
                }
            }
        } finally {
            server.stopServer();
        }
    }

    /**
     * The other half of the claim: sanitizing must not cost an honest client
     * anything it is entitled to do. Ready state is gated on {@code mayEdit()},
     * so it must still apply.
     */
    @Test(timeOut = 120_000, description = "F-04: a client's own legitimate seat settings still apply")
    public void testClientCanStillConfigureItsOwnSeat() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            try (RawProtocolPeer peer = new RawProtocolPeer(port)) {
                peer.login("Honest", null);
                Assert.assertTrue(peer.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS));
                final int slot = peer.assignedSlot.get();
                Assert.assertFalse(lobby.getSlot(slot).isReady(), "Should start not ready");

                peer.send(UpdateLobbyPlayerEvent.isReadyUpdate(true));
                Thread.sleep(2000);

                Assert.assertTrue(lobby.getSlot(slot).isReady(),
                        "Ready state is the client's to set — sanitizing server-owned fields "
                                + "must not strip what a client is entitled to change");
            }
        } finally {
            server.stopServer();
        }
    }
}
