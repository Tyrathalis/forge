package forge.web;

import com.google.gson.JsonObject;
import forge.web.protocol.JsonCodec;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class WebSessionTest {
    /** Each holds a timer thread until it is shut down. */
    private final List<WebSessions> opened = new ArrayList<>();

    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    @AfterMethod(alwaysRun = true)
    public void shutDown() {
        opened.forEach(WebSessions::shutdown);
        opened.clear();
    }

    private WebSessions opened() {
        final WebSessions sessions = new WebSessions(new WebGuiBase(), 60_000, () -> { });
        opened.add(sessions);
        return sessions;
    }

    /** Connects a browser and has it claim the host's seat. No browser gets the seat just by connecting. */
    private static void connectAsHost(final WebSessions sessions, final TestBrowser r, final String id) {
        sessions.connected(r, id, true);
        sessions.onMessage(r, JsonCodec.message("claimHost"));
    }

    /** Fails if a browser gets the host's seat just by connecting, or if two browsers can both hold it. */
    @Test
    public void theFirstBrowserToAskGetsTheHostSeat() throws Exception {
        final WebSessions sessions = opened();
        final TestBrowser first = new TestBrowser();
        final TestBrowser second = new TestBrowser();
        sessions.connected(first, "first", true);
        sessions.connected(second, "second", true);
        Assert.assertFalse(first.awaitNewest("hello").get("host").getAsBoolean(), "a browser hosted by arriving");
        Assert.assertTrue(first.awaitNewest("hello").get("canClaimHost").getAsBoolean(), "the seat was not offered");

        sessions.onMessage(first, JsonCodec.message("claimHost"));
        Assert.assertTrue(first.awaitNewest("hello").get("host").getAsBoolean(), "asking did not take the seat");

        sessions.onMessage(second, JsonCodec.message("claimHost"));
        Assert.assertFalse(second.awaitNewest("hello").get("host").getAsBoolean(), "two browsers took the same seat");
        Assert.assertNotNull(second.awaitMatching("error", m -> true), "the second browser was not told why");
    }

    private static JsonObject claim(final String password) {
        final JsonObject m = JsonCodec.message("claimHost");
        m.addProperty("password", password);
        return m;
    }

    /** Fails if a browser on another machine can take the host's seat without the host password once one is set. The host's seat can stop the server or set up the table. */
    @Test
    public void anotherMachineNeedsTheHostPasswordWhenOneIsSet() throws Exception {
        WebService.hostPassword("open sesame");
        try {
            final WebSessions sessions = opened();
            final TestBrowser remote = new TestBrowser();
            sessions.connected(remote, "remote", false);
            Assert.assertTrue(remote.awaitNewest("hello").get("hostPassword").getAsBoolean(), "the page was not told to ask for the password");
            sessions.onMessage(remote, claim("wrong"));
            Assert.assertNotNull(remote.awaitMatching("error", m -> true), "a wrong password was not refused");
            Assert.assertFalse(remote.awaitNewest("hello").get("host").getAsBoolean(), "a wrong password took the host's seat");
            sessions.onMessage(remote, claim("open sesame"));
            Assert.assertNotNull(remote.awaitNewest("hello", m -> m.get("host").getAsBoolean()), "the right password did not take the host's seat");
        } finally {
            WebService.hostPassword("");
        }
    }

    /** Fails if this machine's own browser has to give the host password, or a browser on another machine has to when none is set. */
    @Test
    public void thisMachineAndAnUnguardedSeatNeedNoPassword() throws Exception {
        WebService.hostPassword("open sesame");
        try {
            final TestBrowser local = new TestBrowser();
            connectAsHost(opened(), local, "local");
            Assert.assertFalse(local.awaitNewest("hello").get("hostPassword").getAsBoolean());
            Assert.assertTrue(local.awaitNewest("hello").get("host").getAsBoolean(), "this machine's browser needed the password");
        } finally {
            WebService.hostPassword("");
        }
        final WebSessions sessions = opened();
        final TestBrowser remote = new TestBrowser();
        sessions.connected(remote, "remote", false);
        sessions.onMessage(remote, JsonCodec.message("claimHost"));
        Assert.assertNotNull(remote.awaitNewest("hello", m -> m.get("host").getAsBoolean()), "another machine could not take the seat with no password set");
    }

    /** Fails if anyone with a link can make the server track an unlimited number of browsers. */
    @Test
    public void browsersBeyondTheLimitAreTurnedAwayUntilOthersLeave() {
        final WebSessions sessions = opened();
        final List<TestBrowser> attached = new ArrayList<>();
        for (int i = 0; i < WebSessions.MOST_SESSIONS; i++) {
            final TestBrowser r = new TestBrowser();
            sessions.connected(r, "b" + i, false);
            attached.add(r);
        }
        final TestBrowser oneTooMany = new TestBrowser();
        sessions.connected(oneTooMany, "extra", false);
        Assert.assertTrue(oneTooMany.closed, "a browser past the limit was let in");
        Assert.assertFalse(attached.get(0).closed);

        // A browser that has disconnected and holds nothing frees a place
        sessions.disconnected(attached.get(0));
        final TestBrowser next = new TestBrowser();
        sessions.connected(next, "extra", false);
        Assert.assertFalse(next.closed, "a browser was turned away although another had left");
    }
}
