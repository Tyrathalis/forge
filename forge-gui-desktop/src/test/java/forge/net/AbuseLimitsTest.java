package forge.net;

import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * F-07 and F-09: an unauthenticated peer must not be able to consume the host
 * without limit, or write into its logs.
 *
 * <p>Every accepted channel allocated a {@code RemoteClient} and a decoder
 * before the peer proved anything, and nothing bounded how many channels one
 * source could hold, how long they could sit unregistered, or how fast they
 * could push chat — which is broadcast to every peer and logged on arrival.
 */
public class AbuseLimitsTest implements IHasForgeLog {

    // ------------------------------------------------------------------
    // F-09: log and chat injection
    // ------------------------------------------------------------------

    @Test(timeOut = 30_000)
    public void testForLogEscapesRecordSeparators() {
        final String forged = "hi\n21:04:11 [INFO ] Server: host granted admin to mallory";
        final String safe = LogSafe.forLog(forged);
        Assert.assertFalse(safe.contains("\n"), "A newline survived into a log line: " + safe);
        Assert.assertTrue(safe.contains("\\n"), "The newline should still be visible as an escape");
    }

    @Test(timeOut = 30_000)
    public void testForLogEscapesOtherControlCharacters() {
        Assert.assertEquals(LogSafe.forLog("a\rb"), "a\\rb");
        Assert.assertEquals(LogSafe.forLog("a\tb"), "a\\tb");
        Assert.assertEquals(LogSafe.forLog("a\0b"), "a\\u0000b");
        // C1 range, which some terminals interpret
        Assert.assertEquals(LogSafe.forLog("a\u009bb"), "a\\u009bb");
    }

    @Test(timeOut = 30_000)
    public void testForDisplayStripsControlCharactersEntirely() {
        // A UI has no use for these, and a carriage return lets one player
        // paint fake system lines in another's chat pane.
        Assert.assertEquals(LogSafe.forDisplay("hi\nthere"), "hithere");
        Assert.assertEquals(LogSafe.forDisplay("a\u0007b"), "ab");
    }

    @Test(timeOut = 30_000)
    public void testTruncatesOverlongText() {
        final String huge = "x".repeat(10_000);
        final String safe = LogSafe.forLog(huge, 100);
        Assert.assertTrue(safe.length() < 200, "Should be bounded, was " + safe.length());
        Assert.assertTrue(safe.endsWith("[truncated]"), "Truncation should be visible: " + safe);
    }

    @Test(timeOut = 30_000)
    public void testLeavesOrdinaryTextAlone() {
        Assert.assertEquals(LogSafe.forLog("Alice: nice topdeck"), "Alice: nice topdeck");
        Assert.assertEquals(LogSafe.forDisplay("Alice: nice topdeck"), "Alice: nice topdeck");
        Assert.assertNull(LogSafe.forLog(null));
    }

    // ------------------------------------------------------------------
    // F-07: chat flood
    // ------------------------------------------------------------------

    /** Far above any legitimate burst, low enough to fail fast. */
    private static final int DRAIN_CEILING = 10_000;

    /**
     * Chat is accepted before login and rebroadcast to everyone, so it is the
     * cheapest amplifier on the protocol: one sender, N recipients, plus a log
     * line each time.
     */
    @Test(timeOut = 30_000)
    public void testChatIsRateLimited() {
        final RemoteClient client = new RemoteClient(null);

        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (client.allowChatMessage()) {
                allowed++;
            }
        }
        Assert.assertTrue(allowed > 0, "A burst of chat should be allowed — people do type");
        Assert.assertTrue(allowed < 100,
                "All 100 messages were accepted back-to-back; the bucket is not limiting");
    }

    @Test(timeOut = 30_000)
    public void testChatAllowanceRefillsOverTime() throws Exception {
        final RemoteClient client = new RemoteClient(null);
        // Bounded, not "while (allowChatMessage())". An unbounded drain loop
        // terminates only if the limiter works, so with the limiter removed —
        // the exact case this suite exists to detect — it spins forever and the
        // run hangs instead of failing. A hang reads as "still running", which
        // is strictly worse than a red test.
        int drained = 0;
        while (drained < DRAIN_CEILING && client.allowChatMessage()) {
            drained++;
        }
        Assert.assertTrue(drained < DRAIN_CEILING,
                "Bucket never emptied after " + DRAIN_CEILING + " messages; not rate limiting");
        Assert.assertFalse(client.allowChatMessage(), "Bucket should be empty");

        Thread.sleep(1200);
        Assert.assertTrue(client.allowChatMessage(),
                "Allowance must come back — a rate limit that never refills is a mute");
    }

    // ------------------------------------------------------------------
    // F-07: connection admission
    // ------------------------------------------------------------------

    @Test(timeOut = 120_000, description = "F-07: connections from one host are capped")
    public void testConnectionsAreCapped() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        final String previous = System.getProperty("forge.net.maxConnectionsPerHost");
        System.setProperty("forge.net.maxConnectionsPerHost", "2");
        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        final List<RawProtocolPeer> peers = new ArrayList<>();
        try {
            server.setLobby(new ServerGameLobby());

            for (int i = 0; i < 3; i++) {
                peers.add(new RawProtocolPeer(port));
                Thread.sleep(400);
            }

            // The first two are admitted; the third must be refused.
            Assert.assertTrue(peers.get(2).closed.await(15, TimeUnit.SECONDS),
                    "Third connection from the same host was admitted despite a cap of 2");
        } finally {
            System.clearProperty("forge.net.maxConnectionsPerHost");
            if (previous != null) {
                System.setProperty("forge.net.maxConnectionsPerHost", previous);
            }
            for (final RawProtocolPeer p : peers) {
                p.close();
            }
            server.stopServer();
        }
    }

    @Test(timeOut = 120_000, description = "F-07: a peer that never logs in is dropped")
    public void testSilentPeerIsClosedAfterTheLoginDeadline() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        System.setProperty("forge.net.loginDeadlineSeconds", "2");
        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            server.setLobby(new ServerGameLobby());

            try (RawProtocolPeer squatter = new RawProtocolPeer(port)) {
                // Connect and say nothing at all, forever.
                Assert.assertTrue(squatter.closed.await(30, TimeUnit.SECONDS),
                        "A peer that never logged in held its connection past the deadline");
            }
        } finally {
            System.clearProperty("forge.net.loginDeadlineSeconds");
            server.stopServer();
        }
    }

    /** The limits must not evict a peer that is behaving. */
    @Test(timeOut = 120_000, description = "F-07: a peer that logs in promptly is kept")
    public void testLoggedInPeerSurvivesTheDeadline() throws Exception {
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        System.setProperty("forge.net.loginDeadlineSeconds", "2");
        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            server.setLobby(new ServerGameLobby());

            try (RawProtocolPeer peer = new RawProtocolPeer(port)) {
                peer.login("Honest", null);
                Assert.assertTrue(peer.gotSlotAssignment.await(15, TimeUnit.SECONDS),
                        "Peer should have joined");

                Assert.assertFalse(peer.closed.await(6, TimeUnit.SECONDS),
                        "A peer that logged in was dropped by the login deadline anyway");
            }
        } finally {
            System.clearProperty("forge.net.loginDeadlineSeconds");
            server.stopServer();
        }
    }
}
