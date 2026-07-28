package forge.net;

import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * F-04: a client may configure its own seat, but not the server-owned state
 * that decides who occupies it.
 *
 * <p>Slot <b>type</b> is lifecycle state the server owns — {@code connectPlayer}
 * sets REMOTE, {@code disconnectPlayer} sets OPEN — so a client that marked its
 * own occupied slot OPEN kept its index while the server handed the same index
 * to the next joiner. Two channels, one seat.
 *
 * <p>The honest client cannot do this, since the slot-type control is gated on
 * {@code mayControl()}. That is exactly why the end-to-end case uses a
 * {@link RawProtocolPeer}: client-side gating is not enforcement.
 */
public class LobbySlotAuthorizationTest {

    private static final long AWAIT_SECONDS = 15;

    /** A crafted event of the kind no honest client constructs. */
    private static UpdateLobbyPlayerEvent forgedTypeChange(final LobbySlotType type) {
        return UpdateLobbyPlayerEvent.create(type, null, -1, -1, -1, false, false,
                Collections.emptySet(), null);
    }

    /**
     * The rule itself, for free. This is the regression a maintainer creates by
     * adding a field to the event: the clearing must take the three
     * server-owned fields and nothing else.
     */
    @Test
    public void testClearsOnlyServerOwnedFields() {
        final UpdateLobbyPlayerEvent forged = UpdateLobbyPlayerEvent.create(
                LobbySlotType.OPEN, "Mallory", 3, 4, 1, true, true,
                Collections.emptySet(), "ai-profile");

        Assert.assertTrue(forged.clearServerOwnedFields(), "Server-owned fields were present");
        Assert.assertNull(forged.getType(), "Slot type is the server's to set");
        Assert.assertNull(forged.getAiProfile(), "AI profile does not belong to a REMOTE slot");

        // Everything a client is entitled to change must survive untouched, or
        // the fix costs honest players their own settings. isDevMode and
        // isArchenemy are the deliberate scoping call: gated on mayEdit(), so
        // clients set them legitimately today.
        Assert.assertEquals(forged.getName(), "Mallory");
        Assert.assertEquals(forged.getTeam(), Integer.valueOf(1));
        Assert.assertEquals(forged.getDevMode(), Boolean.TRUE);
        Assert.assertEquals(forged.getArchenemy(), Boolean.TRUE);

        // A no-op for honest traffic, which carries none of the three.
        Assert.assertFalse(UpdateLobbyPlayerEvent.isReadyUpdate(true).clearServerOwnedFields());
    }

    /** The consequence the type rewrite buys: two live channels, one seat. */
    @Test(timeOut = 120_000, description = "F-04: a forged type change must not produce a duplicate seat")
    public void testForgedTypeChangeCannotProduceDuplicateSeat() throws Exception {
        NetworkTests.skipUnlessStressTestsEnabled();
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

                Assert.assertEquals(lobby.getSlot(squatterSlot).getType(), LobbySlotType.REMOTE,
                        "Client rewrote its own slot type to OPEN");

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
}
