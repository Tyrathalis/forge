package forge.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

public class LobbyTeamsTest extends SessionsTest {
    /** Each seat's team, with -1 for a seat that the table shows no team for. */
    private static List<Integer> teams(final JsonObject table) {
        final List<Integer> out = new ArrayList<>();
        for (final JsonElement seat : table.getAsJsonArray("seats")) {
            final JsonElement team = seat.getAsJsonObject().get("team");
            out.add(team == null || team.isJsonNull() ? -1 : team.getAsInt());
        }
        return out;
    }

    private TestBrowser tableOfFour() throws InterruptedException {
        final TestBrowser host = hostAt("invite");
        sessions.onMessage(host, message("setPlayerCount", "count", 4));
        host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 4);
        return host;
    }

    // Fails if a free-for-all table shows teams, or dealing two teams does not alternate the seats
    @Test(timeOut = 120_000)
    public void twoTeamsDealTheSeatsInTurn() throws Exception {
        final TestBrowser host = tableOfFour();
        Assert.assertEquals(teams(host.latestTable()), List.of(-1, -1, -1, -1), "a free-for-all table shows teams");
        Assert.assertEquals(host.latestTable().get("teams").getAsInt(), 0);

        sessions.onMessage(host, message("setTeams", "count", 2));
        final JsonObject dealt = host.awaitLobby(l -> l.get("teams").getAsInt() == 2);
        Assert.assertEquals(teams(dealt), List.of(0, 1, 0, 1));

        sessions.onMessage(host, message("setTeams", "count", 0));
        final JsonObject cleared = host.awaitLobby(l -> l.get("teams").getAsInt() == 0);
        Assert.assertEquals(teams(cleared), List.of(-1, -1, -1, -1), "going back to a free-for-all left a team showing");
    }

    // Fails if the host can start with every seat on one team, or a seat can be moved to a team the table does not have
    @Test(timeOut = 120_000)
    public void oneSideCannotStart() throws Exception {
        final TestBrowser host = tableOfFour();
        sessions.onMessage(host, message("setTeams", "count", 2));
        host.awaitLobby(l -> l.get("teams").getAsInt() == 2);

        sessions.onMessage(host, message("setTeam", "index", 1, "team", 5));
        sessions.onMessage(host, message("setTeam", "index", 1, "team", 0));
        final JsonObject moved = host.awaitLobby(l -> teams(l).equals(List.of(0, 0, 0, 1)));
        Assert.assertEquals(moved.get("teams").getAsInt(), 2);

        sessions.onMessage(host, message("setTeam", "index", 3, "team", 0));
        final JsonObject oneSide = host.awaitLobby(l -> teams(l).equals(List.of(0, 0, 0, 0)));
        Assert.assertFalse(oneSide.get("canStart").getAsBoolean(), "a table with one side can start");
    }

    // Fails if a seat added to a table with teams gets a team of its own, or joins the larger team
    @Test(timeOut = 120_000)
    public void aNewSeatJoinsTheSmallerTeam() throws Exception {
        final TestBrowser host = tableOfFour();
        sessions.onMessage(host, message("setTeams", "count", 2));
        host.awaitLobby(l -> l.get("teams").getAsInt() == 2);
        sessions.onMessage(host, message("setTeam", "index", 1, "team", 0));
        host.awaitLobby(l -> teams(l).equals(List.of(0, 0, 0, 1)));

        sessions.onMessage(host, message("setPlayerCount", "count", 5));
        final JsonObject five = host.awaitLobby(l -> l.getAsJsonArray("seats").size() == 5);
        Assert.assertEquals(teams(five), List.of(0, 0, 0, 1, 1));
        Assert.assertEquals(five.get("teams").getAsInt(), 2);
    }
}
