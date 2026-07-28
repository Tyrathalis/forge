package forge.net;

import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.RemoteClient;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.util.LogSafe;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

/**
 * F-07 and F-09: an unauthenticated peer must not be able to consume the host
 * without limit, or write into its logs. Every accepted channel allocates a
 * {@code RemoteClient} and a decoder before the peer proves anything, and chat
 * is accepted pre-login and rebroadcast to everyone.
 */
public class AbuseLimitsTest {

    /** Far above any legitimate burst, low enough to fail fast. */
    private static final int DRAIN_CEILING = 10_000;

    @Test(timeOut = 30_000)
    public void testNeutralisesRemoteText() {
        // A newline forges a log record; forLog keeps it visible as an escape.
        final String forged = "hi\n21:04:11 [INFO ] Server: host granted admin to mallory";
        Assert.assertFalse(LogSafe.forLog(forged).contains("\n"));
        Assert.assertEquals(LogSafe.forLog("a\rb"), "a\\rb");
        Assert.assertEquals(LogSafe.forLog("a\0b"), "a\\u0000b");
        Assert.assertEquals(LogSafe.forLog("a\u009bb"), "a\\u009bb");

        // A UI has no use for these: a carriage return lets one player paint
        // fake system lines in another's chat pane.
        Assert.assertEquals(LogSafe.forDisplay("hi\nthere"), "hithere");
        Assert.assertEquals(LogSafe.forDisplay("a\u0007b"), "ab");

        final String safe = LogSafe.forLog("x".repeat(10_000), 100);
        Assert.assertTrue(safe.length() < 200, "Should be bounded, was " + safe.length());
        Assert.assertTrue(safe.endsWith("[truncated]"), "Truncation should be visible");

        // Over-correction guard: ordinary text must survive untouched.
        Assert.assertEquals(LogSafe.forLog("Alice: nice topdeck"), "Alice: nice topdeck");
        Assert.assertEquals(LogSafe.forDisplay("Alice: nice topdeck"), "Alice: nice topdeck");
        Assert.assertNull(LogSafe.forLog(null));
    }

    @Test(timeOut = 30_000)
    public void testChatIsRateLimitedAndRefills() throws Exception {
        final RemoteClient client = new RemoteClient(null);

        // Bounded, not "while (allowChatMessage())". An unbounded drain loop
        // terminates only if the limiter works, so with the limiter removed —
        // the case this exists to detect — it spins forever and the run hangs
        // instead of failing. A hang reads as "still running", which is worse.
        int drained = 0;
        while (drained < DRAIN_CEILING && client.allowChatMessage()) {
            drained++;
        }
        Assert.assertTrue(drained > 0, "A burst should be allowed — people do type");
        Assert.assertTrue(drained < DRAIN_CEILING,
                "Bucket never emptied after " + DRAIN_CEILING + " messages; not rate limiting");

        // One token per CHAT_REFILL_MILLIS, so this cannot be shortened much.
        Thread.sleep(1100);
        Assert.assertTrue(client.allowChatMessage(),
                "Allowance must come back — a rate limit that never refills is a mute");
    }

    /**
     * Both halves of the login deadline against one server: the squatter is
     * dropped, and the peer that logs in promptly is not. The second is the
     * over-correction guard, and it is why this is not simply a timing check.
     */
    @Test(timeOut = 120_000, description = "F-07: unregistered peers are dropped, logged-in ones are not")
    public void testLoginDeadlineDropsOnlySilentPeers() throws Exception {
        NetworkTests.skipUnlessStressTestsEnabled();
        TestUtils.ensureFModelInitialized();
        final int port = PortAllocator.allocatePort();

        System.setProperty("forge.net.loginDeadlineSeconds", "2");
        final FServerManager server = FServerManager.getInstance();
        server.startServer(port);
        try {
            server.setLobby(new ServerGameLobby());

            try (RawProtocolPeer squatter = new RawProtocolPeer(port);
                    RawProtocolPeer honest = new RawProtocolPeer(port)) {
                honest.login("Honest", null);
                Assert.assertTrue(honest.gotSlotAssignment.await(15, TimeUnit.SECONDS),
                        "Peer should have joined");

                Assert.assertTrue(squatter.closed.await(30, TimeUnit.SECONDS),
                        "A peer that never logged in held its connection past the deadline");
                Assert.assertFalse(honest.closed.await(3, TimeUnit.SECONDS),
                        "A peer that logged in was dropped by the login deadline anyway");
            }
        } finally {
            System.clearProperty("forge.net.loginDeadlineSeconds");
            server.stopServer();
        }
    }
}
