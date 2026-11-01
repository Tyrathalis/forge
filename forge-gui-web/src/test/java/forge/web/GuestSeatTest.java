package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.gamemodes.match.HostedMatch;
import forge.web.deck.DeckCatalog;
import forge.web.protocol.JsonCodec;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

/** Tests a second browser taking a seat in the host's game. Both browsers run in this process and connect to the same loopback server. */
public class GuestSeatTest extends SessionsTest {
    // Fails if a guest cannot reach the host's game
    @Test(timeOut = 120_000)
    public void aGuestSitsDownWithTheHostAndLeavesWithTheGame() throws Exception {
        final TestBrowser hostBrowser = connect("host");
        sessions.onMessage(hostBrowser, JsonCodec.message("claimHost"));
        hostBrowser.awaitMatching("hello", h -> h.get("host").getAsBoolean(),
                "asking for the host's seat did not take it");
        // Another test may have left the host at a table, and a reconnect shows it again. send() clears it so only the new table counts
        send(hostBrowser, message("setName", "name", "Host"));
        sessions.onMessage(hostBrowser, JsonCodec.message("invite"));
        final JsonObject hosted = hostBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");
        Assert.assertTrue(hosted.get("shareable").getAsBoolean(), "an invited game offered no link");

        // All browsers share the server's preferences, so a guest has no name until it chooses one
        final TestBrowser guestBrowser = connect("guest");
        final JsonObject greeted = guestBrowser.awaitNewest("hello", "the guest was never greeted");
        Assert.assertFalse(greeted.has("playerName"), "the guest was given a name it never chose");
        sessions.onMessage(guestBrowser, message("setName", "name", "host"));
        guestBrowser.awaitMatching("error", e -> e.get("message").getAsString().contains("already called"),
                "the guest was let play under the host's name");
        Assert.assertTrue(guestBrowser.got.stream().noneMatch(m -> "lobby".equals(m.get("t").getAsString())),
                "the guest took a seat before it had a name");
        sessions.onMessage(guestBrowser, message("setName", "name", "Guest"));
        final JsonObject seated = guestBrowser.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        Assert.assertFalse(seated.get("host").getAsBoolean(), "the guest was treated as the host");
        Assert.assertNotEquals(seated.get("mySeat").getAsInt(), hosted.get("mySeat").getAsInt(),
                "the guest was given the host's seat");

        // The host's table is sent on every change, so the guest must appear there without a request
        final int guestSeat = seated.get("mySeat").getAsInt();
        hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                        && "REMOTE".equals(l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject()
                        .get("type").getAsString()),
                "the host's table never showed the guest arriving");

        // A reload returns to the same table. The browser cannot draw the table until it is sent again
        sessions.disconnected(guestBrowser);
        final TestBrowser reloaded = connect("guest");
        final JsonObject again = reloaded.awaitLobbyWithSeat();
        Assert.assertNotNull(again, "a guest that reloaded in match setup was never shown the table again");
        Assert.assertEquals(again.get("mySeat").getAsInt(), guestSeat, "a guest that reloaded lost its seat");
        sessions.disconnected(reloaded);
        sessions.connected(guestBrowser, "guest", false);

        // The guest chooses a deck from the guest's own list. The host must see it, or the host cannot start
        sessions.onMessage(guestBrowser, JsonCodec.message("decks"));
        final String deck = TestDecks.firstLegal(guestBrowser.awaitDecks());
        final JsonObject choose = message("setSeat", "index", guestSeat);
        choose.addProperty("deck", deck);
        sessions.onMessage(guestBrowser, choose);
        final JsonObject table = hostBrowser.awaitLobby(l -> l.getAsJsonArray("seats").size() > guestSeat
                && l.getAsJsonArray("seats").get(guestSeat).getAsJsonObject().has("deckName"), "the host's table never showed the guest's deck");
        // Read the table that shows the guest's deck, because the host's copy can be one update behind the server's
        for (final var problem : table.getAsJsonArray("problems")) {
            Assert.assertFalse(problem.getAsString().startsWith("Please specify") && problem.getAsString().endsWith(" Guest"),
                    "the host still counted the guest as having no deck: " + problem.getAsString());
        }

        // The hello sent before the guest sat down also says it is not at the table. So only later messages count
        guestBrowser.forget();
        sessions.onMessage(hostBrowser, JsonCodec.message("leaveLobby"));
        guestBrowser.awaitMatching("hello", h -> !"setup".equals(h.get("screen").getAsString()),
                "the guest was left in a lobby the host had closed");
    }

    // Fails if the guest's browser stays in match setup when the host starts the match, or is never shown the table
    @Test(timeOut = 120_000)
    public void aGuestFollowsTheHostIntoTheMatch() throws Exception {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        send(host, JsonCodec.message("invite"));
        final JsonObject hosted = host.awaitLobbyWithSeat();
        Assert.assertNotNull(hosted, "the host never got a seat in its own game");

        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat" + diagnosis(host, guest));

        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = TestDecks.firstLegal(host.awaitDecks());
        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(),
                "the host could not start once both seats had a deck and were ready");

        // Stops set in match setup belong to the player. The match must open with them, not apply them later
        final JsonObject stops = JsonCodec.message("setStops");
        stops.addProperty("mine", true);
        final JsonArray phases = new JsonArray();
        phases.add("MAIN2");
        stops.add("phases", phases);
        sessions.onMessage(guest, stops);

        guest.forget();
        sessions.onMessage(host, message("start", "spectate", false));
        Assert.assertNotNull(guest.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString())),
                "the guest was left in match setup when the host started the match" + diagnosis(host, guest));
        guest.awaitMatching("state", m -> m.get("full").getAsBoolean(),
                "the guest was taken into the match but never shown the table");
        final JsonObject controls = guest.awaitNewest("controls", "the guest was never sent its controls");
        Assert.assertEquals(controls.get("myStops").toString(), "[\"MAIN2\"]",
                "the match did not open with the stops the guest set in match setup");
    }

    // Fails if the host cannot give a disconnected guest's seat to another browser, the browser that left gets it back, a reload loses it, or removing the player keeps the seat or the name taken
    @Test(timeOut = 120_000)
    public void theHostSettlesADisconnectedSeatAtTheTable() throws Exception {
        final TestBrowser host = hostAt("invite");
        final TestBrowser first = connect("first");
        sessions.onMessage(first, message("setName", "name", "Ann"));
        final int seat = first.awaitLobbyWithSeat().get("mySeat").getAsInt();
        sessions.disconnected(first);
        host.awaitNewest("presence", p -> disconnected(p, "Ann"), "the host was not shown the guest as disconnected");

        final TestBrowser second = connect("second");
        sessions.onMessage(second, message("setName", "name", "Bob"));
        second.awaitMatching("hello", h -> "Bob".equals(str(h, "playerName")), "the second guest was not named");
        send(host, message("giveSeat", "from", "Ann", "to", "Bob"));
        final JsonObject given = second.awaitLobby(l -> l.get("mySeat").getAsInt() == seat, "the second guest was not given the seat");
        Assert.assertEquals(given.getAsJsonArray("seats").get(seat).getAsJsonObject().get("name").getAsString(), "Ann");
        host.awaitNewest("presence", p -> named(p, "Ann") && !disconnected(p, "Ann") && !named(p, "Bob"),
                "the seat's player still reads as disconnected, or the browser that took it is listed twice");

        final TestBrowser back = connect("first");
        final JsonObject greeted = back.awaitNewest("hello", "the browser that left was never greeted");
        Assert.assertFalse(greeted.has("playerName") || "setup".equals(greeted.get("screen").getAsString()), "the browser that left came back to the seat it had lost");
        sessions.disconnected(back);

        sessions.disconnected(second);
        final TestBrowser reloaded = connect("second");
        reloaded.awaitLobby(l -> l.get("mySeat").getAsInt() == seat, "the browser given the seat lost it on a reload");
        sessions.disconnected(reloaded);
        host.awaitNewest("presence", p -> disconnected(p, "Ann"), "the host was not shown the seat as disconnected again");
        send(host, message("dropPlayer", "name", "Ann"));
        host.awaitLobby(l -> "OPEN".equals(l.getAsJsonArray("seats").get(seat).getAsJsonObject().get("type").getAsString()),
                "the seat did not open when its player was removed");
        final TestBrowser third = connect("third");
        sessions.onMessage(third, message("setName", "name", "Ann"));
        Assert.assertNotNull(third.awaitLobbyWithSeat(), "the removed player's name or seat was still held");
        // A host that returns to a table is sent its decks once, before the next test can ask for them
        send(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !"setup".equals(h.get("screen").getAsString()), "the host did not leave its table");
    }

    // Fails if a guest can empty another guest's seat, or the host empties one and leaves its guest at the table, the seat taken, or the table one seat short
    @Test(timeOut = 120_000)
    public void onlyTheHostEmptiesAGuestsSeat() throws Exception {
        final TestBrowser host = hostAt("invite");
        send(host, message("setPlayerCount", "count", 3));
        send(host, message("openSeat", "index", 2));
        final TestBrowser ann = connect("ann");
        sessions.onMessage(ann, message("setName", "name", "Ann"));
        final int annSeat = ann.awaitLobbyWithSeat().get("mySeat").getAsInt();
        final TestBrowser bob = connect("bob");
        sessions.onMessage(bob, message("setName", "name", "Bob"));
        Assert.assertNotNull(bob.awaitLobbyWithSeat(), "the second guest never took a seat");

        // A command is handled before onMessage returns, so if the seat were emptied it would already be empty
        ann.forget();
        sessions.onMessage(bob, message("openSeat", "index", annSeat));
        sessions.onMessage(bob, message("removeSeat", "index", annSeat));
        Assert.assertTrue(ann.got.stream().noneMatch(m -> "hello".equals(m.get("t").getAsString()) && !"setup".equals(m.get("screen").getAsString())),
                "a guest sent another guest away from the table");

        send(host, message("removeSeat", "index", annSeat));
        ann.awaitMatching("hello", h -> !"setup".equals(h.get("screen").getAsString()), "the guest was left at the table when the host emptied its seat");
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3
                        && "OPEN".equals(l.getAsJsonArray("seats").get(annSeat).getAsJsonObject().get("type").getAsString()),
                "emptying a guest's seat did not leave it open, or took the seat away with the guest");
        send(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !"setup".equals(h.get("screen").getAsString()), "the host did not leave its table");
    }

    // Fails if a seat given to another browser mid-match does not show that browser the game, or a removed player's seat is not played by the AI
    @Test(timeOut = 120_000)
    public void theHostSettlesADisconnectedSeatInAMatch() throws Exception {
        final TestBrowser host = hostAt("invite");
        final JsonObject hosted = host.awaitLobbyWithSeat();
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = TestDecks.firstLegal(host.awaitDecks());
        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(), "the host could not start");
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the guest was not taken into the match");

        sessions.disconnected(guest);
        final TestBrowser other = connect("other");
        sessions.onMessage(other, message("setName", "name", "Sub"));
        other.awaitMatching("hello", h -> "Sub".equals(str(h, "playerName")), "the second guest was not named");
        send(host, message("giveSeat", "from", "Player", "to", "Sub"));
        other.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the browser given the seat was not taken into the match");
        other.awaitMatching("state", m -> m.get("full").getAsBoolean(), "the browser given the seat was never shown the table");

        sessions.disconnected(other);
        host.awaitNewest("presence", p -> disconnected(p, "Player"), "the host was not shown the seat as disconnected");
        // Only presence sent after the removal counts. send() would clear the list that this index points into
        final int dropped = host.got.size();
        sessions.onMessage(host, message("dropPlayer", "name", "Player"));
        final Game game = sessions.hostLobby().getHostedMatch().getGame();
        awaitTrue(() -> game.getPlayers().stream().anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi),
                "the removed player's seat was not handed to the AI");
        Assert.assertNotNull(host.awaitAfter(dropped, "presence", p -> !named(p, "Player")), "the removed player is still listed");
    }

    // Fails if a match waits for a next-game choice from a seat the AI took over, or the next game returns that seat to a human
    @Test(timeOut = 120_000)
    public void theMatchGoesOnAfterASeatIsHandedToTheAi() throws Exception {
        final TestBrowser host = hostAt("invite");
        final JsonObject hosted = host.awaitLobbyWithSeat();
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        final JsonObject seated = guest.awaitLobbyWithSeat();
        Assert.assertNotNull(seated, "the guest never took a seat");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = TestDecks.firstLegal(host.awaitDecks());
        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", (browser == host ? hosted : seated).get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.get("canStart").getAsBoolean(), "the host could not start");
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()), "the guest was not taken into the match");
        final HostedMatch match = sessions.hostLobby().getHostedMatch();
        awaitTrue(() -> match.getGame() != null && match.getHumanControllers().stream().anyMatch(c -> c.getInputQueue().getInput() != null),
                "the first game never asked a player anything");
        final Game first = match.getGame();

        sessions.disconnected(guest);
        host.awaitNewest("presence", p -> disconnected(p, "Player"), "the host was not shown the seat as disconnected");
        send(host, message("dropPlayer", "name", "Player"));
        awaitTrue(() -> first.getPlayers().stream().anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi),
                "the removed player's seat was not handed to the AI");
        // A game is safe to end only while it waits for the host. If the removed seat had the who-plays-first question, the deal is still running
        awaitPriority(host);
        send(host, JsonCodec.message("concede"));
        host.awaitMatching("gameOver", g -> true, "the host was not shown the first game's result");
        Assert.assertFalse(first.getMatch().isMatchOver(), "one game decided a match of three");

        send(host, message("nextGame", "decision", "CONTINUE"));
        awaitTrue(() -> match.getGame() != null && match.getGame() != first, "the second game never started");
        Assert.assertTrue(match.getGame().getRegisteredPlayers().stream()
                .anyMatch(p -> "Player".equals(p.getName()) && p.getController() instanceof PlayerControllerAi), "the AI's seat went back to a human in the second game");
    }

    // Fails if a guest is left in match setup when the match starts after the host removed a seat below the guest's
    @Test(timeOut = 120_000)
    public void aGuestFollowsTheHostIntoTheMatchAfterASeatBelowIsRemoved() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, JsonCodec.message("decks"));
        final String deck = TestDecks.firstLegal(host.awaitDecks());
        send(host, message("setPlayerCount", "count", 3));
        send(host, message("aiSeat", "index", 1));
        send(host, message("openSeat", "index", 2));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3
                && "OPEN".equals(l.getAsJsonArray("seats").get(2).getAsJsonObject().get("type").getAsString()), "the third seat never opened");
        final TestBrowser guest = connect("player");
        sessions.onMessage(guest, message("setName", "name", "Player"));
        Assert.assertEquals(guest.awaitLobbyWithSeat().get("mySeat").getAsInt(), 2, "the guest did not take the third seat");

        for (final TestBrowser browser : List.of(host, guest)) {
            final JsonObject choose = message("setSeat", "index", browser.latestTable().get("mySeat").getAsInt());
            choose.addProperty("deck", deck);
            sessions.onMessage(browser, choose);
            sessions.onMessage(browser, message("ready", "ready", true));
        }
        host.awaitLobby(l -> l.getAsJsonArray("seats").get(2).getAsJsonObject().get("ready").getAsBoolean(), "the guest never readied");

        send(host, message("removeSeat", "index", 1));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2 && l.get("canStart").getAsBoolean(),
                "the host could not start once the computer's seat was removed");
        guest.awaitLobby(l -> l.get("mySeat").getAsInt() == 1, "the guest was not told its seat had moved down");
        guest.forget();
        sessions.onMessage(host, message("start", "spectate", false));
        guest.awaitMatching("hello", h -> "playing".equals(h.get("screen").getAsString()),
                "the guest was left in match setup when the match started" + diagnosis(host, guest));
    }

    private static boolean named(final JsonObject presence, final String name) {
        for (final var p : presence.getAsJsonArray("people")) {
            if (name.equals(p.getAsJsonObject().get("name").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean disconnected(final JsonObject presence, final String name) {
        for (final var p : presence.getAsJsonArray("people")) {
            final JsonObject person = p.getAsJsonObject();
            if (name.equals(person.get("name").getAsString())) {
                return person.has("disconnected") && person.get("disconnected").getAsBoolean();
            }
        }
        return false;
    }

/** The last messages each browser got and the stack of each relevant thread, for a wait that timed out. */
    private static String diagnosis(final TestBrowser... browsers) {
        final StringBuilder out = new StringBuilder();
        for (final TestBrowser b : browsers) {
            out.append("\n--- last messages:\n");
            final List<JsonObject> got = b.got;
            for (final JsonObject m : got.subList(Math.max(0, got.size() - 4), got.size())) {
                final String text = m.toString();
                out.append(text, 0, Math.min(300, text.length())).append('\n');
            }
        }
        for (final ThreadInfo t : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            final String name = t.getThreadName();
            if (name.startsWith("Web") || name.startsWith("Game") || name.startsWith("main")) {
                out.append("\n--- ").append(name).append(' ').append(t.getThreadState());
                for (final StackTraceElement e : t.getStackTrace()) {
                    out.append("\n    ").append(e);
                }
            }
        }
        return out.toString();
    }

    // Fails if lowering the count removes a person's seat, keeps an open seat instead of a computer's, or goes below two players
    @Test(timeOut = 120_000)
    public void aPersonKeepsTheirSeat() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        final TestBrowser guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest"));
        host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");

        sessions.onMessage(host, message("setPlayerCount", "count", 2));
        final JsonObject two = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 2, "the table never shrank to two");
        Assert.assertTrue(seatNames(two).contains("Guest"), "the guest lost their seat: " + seatNames(two));

        sessions.onMessage(host, message("setPlayerCount", "count", 1));
        // The table handles commands in order. Once the next command shows, the refused one has been handled
        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3, "the table never grew to three");
        Assert.assertTrue(seatCounts(host).allMatch(n -> n >= 2), "a table went below two players");
    }
    // Fails if a table cannot reach eight seats, or accepts a ninth
    @Test(timeOut = 120_000)
    public void aTableTakesEightSeats() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 9));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 8, "the table never grew to eight");
        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3, "the table never shrank to three");
        Assert.assertTrue(seatCounts(host).allMatch(n -> n <= 8), "a table went above eight players");
    }

    /** The seat count of each table sent to the browser, in order. */
    private static IntStream seatCounts(final TestBrowser browser) {
        return browser.got.stream().filter(m -> "lobby".equals(m.get("t").getAsString()) && m.has("table"))
                .mapToInt(m -> m.getAsJsonObject("table").getAsJsonArray("seats").size());
    }

    // Fails if two seats can get the same portrait, when a seat chooses a taken one or a guest arrives with one
    @Test(timeOut = 120_000)
    public void noTwoSeatsShareAPortrait() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 3));
        final JsonObject three = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 3, "the table never grew to three");
        final int mine = three.get("mySeat").getAsInt();
        final int taken = seatAvatars(three).get(mine == 0 ? 1 : 0);
        sessions.onMessage(host, message("setSeat", "index", mine, "avatar", taken));
        // The seat count changes after the portrait request, so a table of four already includes its result
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        final JsonObject four = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        Assert.assertEquals(new HashSet<>(seatAvatars(four)).size(), 4, "a seat took a portrait another holds: " + seatAvatars(four));

        final TestBrowser guest = connect("guest");
        sessions.onMessage(guest, message("setName", "name", "Guest", "avatar", seatAvatars(four).get(mine)));
        final JsonObject seated = host.awaitLobby(l -> seatNames(l).contains("Guest"), "the guest never sat down");
        Assert.assertEquals(new HashSet<>(seatAvatars(seated)).size(), 4, "a guest kept a portrait another holds: " + seatAvatars(seated));
    }

    // Fails if a seat added to a table others can join goes to the AI, or a seat added to a table with no invite is left open
    @Test(timeOut = 120_000)
    public void anAddedSeatIsOpenOnlyWhereOthersCanJoin() throws Exception {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        final JsonObject invited = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        Assert.assertEquals(seatTypes(invited).subList(2, 4), List.of("OPEN", "OPEN"), "seats added where others can join: " + seatTypes(invited));
        disconnectBrowsers();

        final TestBrowser alone = hostAt("lobby");
        sessions.onMessage(alone, message("setPlayerCount", "count", 4));
        final JsonObject offline = alone.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4, "the table never grew to four");
        Assert.assertEquals(seatTypes(offline).subList(1, 4), List.of("AI", "AI", "AI"), "seats at a table nobody was invited to: " + seatTypes(offline));
    }

    // Fails if a blank name leaves the computer's name unchanged, or makes it blank
    @Test(timeOut = 120_000)
    public void aBlankNameGivesTheComputerAnother() throws Exception {
        final TestBrowser host = hostAt("lobby");
        final int ai = seatTypes(host.latestTable()).indexOf("AI");
        final String before = seatNames(host.latestTable()).get(ai);
        sessions.onMessage(host, message("setSeat", "index", ai, "name", ""));
        final JsonObject renamed = host.awaitLobby(l -> !before.equals(seatNames(l).get(ai)), "the computer kept its name");
        Assert.assertFalse(seatNames(renamed).get(ai).isBlank(), "the computer was left with no name");
    }

    // Fails if the portrait chosen on your own seat is not the one the presence list shows for you
    @Test(timeOut = 120_000)
    public void yourSeatsPortraitIsTheOneYouAreKnownBy() throws Exception {
        final TestBrowser host = hostAt("invite");
        final JsonObject table = host.latestTable();
        final int mine = table.get("mySeat").getAsInt();
        final String name = seatNames(table).get(mine);
        int free = 0;
        while (seatAvatars(table).contains(free)) {
            free++;
        }
        final int chosen = free;
        sessions.onMessage(host, message("setSeat", "index", mine, "avatar", chosen));
        host.awaitNewest("presence", p -> avatarOf(p, name) == chosen, "the list of who is here kept the old portrait");
    }

    // Fails if a card-art sleeve the host removed is still offered
    @Test(timeOut = 120_000)
    public void theHostTakesASavedSleeveAway() throws Exception {
        final TestBrowser host = hostAt("lobby");
        final String key = "Sleeve Removal Test Card";
        DeckCatalog.rememberSleeveArt(key, 500);
        Assert.assertTrue(DeckCatalog.savedSleeveArt().stream().anyMatch(art -> art.key().equals(key)), "the sleeve was never saved");
        // The hello sent when the table opened never had this sleeve, so only a hello sent after the removal counts
        final int from = host.got.size();
        sessions.onMessage(host, message("forgetSleeveArt", "key", key));
        Assert.assertNotNull(host.awaitAfter(from, "hello", h -> !h.has("sleeveArt") || !h.get("sleeveArt").toString().contains(key)),
                "the removed sleeve is still offered");
        Assert.assertTrue(DeckCatalog.savedSleeveArt().stream().noneMatch(art -> art.key().equals(key)), "the sleeve is still saved");
    }

    private static int avatarOf(final JsonObject presence, final String name) {
        for (final var p : presence.getAsJsonArray("people")) {
            final JsonObject person = p.getAsJsonObject();
            if (name.equals(person.get("name").getAsString())) {
                return person.has("avatar") ? person.get("avatar").getAsInt() : 0;
            }
        }
        return -1;
    }

    private static List<String> seatTypes(final JsonObject table) {
        final List<String> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().get("type").getAsString()));
        return out;
    }

    private static List<Integer> seatAvatars(final JsonObject table) {
        final List<Integer> out = new ArrayList<>();
        table.getAsJsonArray("seats").forEach(s -> out.add(s.getAsJsonObject().get("avatar").getAsInt()));
        return out;
    }
}
