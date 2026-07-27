package forge.net;

import forge.deck.Deck;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.event.LoginEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.IHasForgeLog;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

/**
 * F-02: a disconnected player's seat must not be reclaimable by anyone who
 * knows their username.
 *
 * <p>Usernames are public — broadcast in lobby state, printed in chat — so
 * before reconnect capabilities existed the reconnect path
 * ({@code disconnectedClients.remove(username)} → {@code swapChannel} →
 * {@code resumeAndResync}) handed the seat, and the resynchronised private
 * game state, to whoever asked for it first inside the five-minute window.
 *
 * <p><b>These tests require a live match.</b> The server only parks a
 * disconnected client for reclaim when {@code isMatchActive()}; in lobby phase
 * {@code channelInactive} just frees the slot via
 * {@code localLobby.disconnectPlayer()}. An earlier version of this test
 * started only a lobby and was therefore vacuous — the "attacker" was taking a
 * genuinely open seat, and the assertions passed with the capability check
 * removed. Hence {@link #assertMatchIsActive}, which pins the precondition the
 * whole test rests on.
 *
 * <p>The attacker is a raw socket rather than an {@code FGameClient} because
 * that is the real threat model: a modified or hand-written client simply
 * omits the token. Nothing constrains what {@link LoginEvent} it constructs.
 */
public class ReconnectCapabilityTest implements IHasForgeLog {

    private static final long AWAIT_SECONDS = 15;
    private static final long GAME_START_TIMEOUT_MS = 60_000;

    /** A started 2-player match: AI on slot 0, a real remote client on slot 1. */
    private static final class LiveMatch implements AutoCloseable {
        final FServerManager server;
        final int port;
        final HeadlessNetworkClient victim;
        final int victimSlot;
        final String victimToken;

        LiveMatch(final String victimName) throws Exception {
            TestUtils.ensureFModelInitialized();
            port = PortAllocator.allocatePort();

            server = FServerManager.getInstance();
            server.startServer(port);

            final ServerGameLobby lobby = new ServerGameLobby();
            server.setLobby(lobby);

            // Slot 0: local AI host. Slot 1: OPEN with a deck preloaded, for
            // the remote client to drop into. Ten-card mono-land decks keep
            // the game short; it pauses the moment the remote drops anyway.
            final Deck hostDeck = TestDeckLoader.createMinimalDeck("Mountain", 10);
            final Deck remoteDeck = TestDeckLoader.createMinimalDeck("Forest", 10);

            final LobbySlot host = lobby.getSlot(0);
            host.setType(LobbySlotType.AI);
            host.setName("AiHost");
            host.setDeck(hostDeck);
            host.setIsReady(true);

            final LobbySlot remote = lobby.getSlot(1);
            remote.setType(LobbySlotType.OPEN);
            remote.setDeck(remoteDeck);
            remote.setIsReady(false);

            Thread.sleep(500);

            victim = new HeadlessNetworkClient(victimName, "127.0.0.1", port);
            Assert.assertTrue(victim.connect(30_000), "Victim client should connect");
            victim.setReady();
            Thread.sleep(1000);

            final Runnable start = lobby.startGame();
            Assert.assertNotNull(start, "startGame() returned null — deck validation failed");
            start.run();

            Assert.assertTrue(victim.waitForGameStart(GAME_START_TIMEOUT_MS),
                    "Match should have started");

            victimSlot = victim.getAssignedSlot();
            victimToken = victim.getClient().getReconnectToken();
            Assert.assertTrue(victimSlot >= 0, "Victim should hold a real slot");
            Assert.assertNotNull(victimToken, "Server must issue a reconnect capability at login");
        }

        /**
         * Drop the victim's socket, then let the server park the seat. Parking
         * is internal state, so we assert the condition that gates it
         * ({@code isMatchActive()}) and give channelInactive time to run.
         */
        void dropVictimAndAwaitParking() throws Exception {
            assertMatchIsActive(server);
            victim.getClient().close();
            Thread.sleep(2000);
            assertMatchIsActive(server);
        }

        @Override
        public void close() {
            try {
                victim.close();
            } catch (final Exception ignored) {
                // best effort
            }
            server.stopServer();
        }
    }

    /**
     * The precondition every assertion here depends on. Without an active
     * match the server never parks a disconnected client, the seat is simply
     * freed, and a takeover test degenerates into "can a new player take an
     * open slot" — which passes whether or not the capability is enforced.
     */
    private static void assertMatchIsActive(final FServerManager server) {
        Assert.assertTrue(server.isMatchActive(),
                "Match must be active for the reconnect path to be reachable — "
                        + "without it this test proves nothing");
    }

    @Test(timeOut = 180_000, description = "F-02: username alone must not reclaim a disconnected seat")
    public void testSeatIsNotReclaimableByUsernameAlone() throws Exception {
        try (LiveMatch match = new LiveMatch("Victim")) {
            match.dropVictimAndAwaitParking();

            try (RawProtocolPeer attacker = new RawProtocolPeer(match.port)) {
                attacker.login("Victim", null);

                final boolean gotSlot = attacker.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                Assert.assertFalse(gotSlot && attacker.assignedSlot.get() == match.victimSlot,
                        "Attacker presenting only a username took over the victim's seat "
                                + match.victimSlot + " — the reconnect capability was not enforced");
                Assert.assertNull(attacker.token.get(),
                        "A refused reclaim must not be issued a capability");
                Assert.assertFalse(attacker.gotGameState.await(5, TimeUnit.SECONDS),
                        "Attacker received resynced game state — the seat's private "
                                + "information leaked despite the seat not being handed over");
            }
        }
    }

    @Test(timeOut = 180_000, description = "F-02: a forged capability must not reclaim a seat")
    public void testSeatIsNotReclaimableWithAForgedCapability() throws Exception {
        try (LiveMatch match = new LiveMatch("Victim2")) {
            match.dropVictimAndAwaitParking();

            try (RawProtocolPeer attacker = new RawProtocolPeer(match.port)) {
                attacker.login("Victim2", "not-the-real-capability");

                final boolean gotSlot = attacker.gotSlotAssignment.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                Assert.assertFalse(gotSlot && attacker.assignedSlot.get() == match.victimSlot,
                        "Attacker presenting a forged capability took over the victim's seat");
                Assert.assertFalse(attacker.gotGameState.await(5, TimeUnit.SECONDS),
                        "Attacker with a forged capability received resynced game state");
            }
        }
    }

    @Test(timeOut = 180_000, description = "F-02: the legitimate holder can still reclaim its seat")
    public void testSeatIsReclaimableWithTheIssuedCapability() throws Exception {
        try (LiveMatch match = new LiveMatch("Player")) {
            match.dropVictimAndAwaitParking();

            try (RawProtocolPeer returning = new RawProtocolPeer(match.port)) {
                returning.login("Player", match.victimToken);

                // A reclaim resumes the match rather than re-running lobby
                // assignment, so the observable is resynced game state, not a
                // LobbyUpdateEvent.
                Assert.assertTrue(returning.gotGameState.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                        "Legitimate holder should reclaim its seat and be resynced — a hardening "
                                + "patch that breaks reconnect is a hardening patch that gets reverted");
                Assert.assertTrue(returning.gotToken.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                        "A fresh capability should be issued on reconnect");
                Assert.assertNotEquals(returning.token.get(), match.victimToken,
                        "Capability must rotate so a captured copy cannot be replayed");
            }
        }
    }
}
