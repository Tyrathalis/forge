package forge.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.protocol.JsonCodec;
import forge.web.protocol.ToBrowser.HostChoiceKind;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Tests that a browser on a guest's link is refused what only the host may do. */
public class GuestPermissionsTest extends SessionsTest {
    private WebGuiBase ui;

    @Override
    WebGuiBase gui() {
        ui = new WebGuiBase();
        return ui;
    }

    // Fails if a guest can change the host's deck generation, open the host's Limited pages, or change the host's preferences through a setting
    @Test(timeOut = 60_000)
    public void aGuestCannotUseTheHostsProfile() throws Exception {
        final String singletons = FModel.getPreferences().getPref(FPref.DECKGEN_SINGLETONS);
        final String artifacts = FModel.getPreferences().getPref(FPref.DECKGEN_ARTIFACTS);
        final String volume = FModel.getPreferences().getPref(FPref.UI_VOL_SOUNDS);
        final TestBrowser guest = connect("guest");
        guest.awaitNewest("hello", "the guest was never greeted");
        sessions.onMessage(guest, message("setDeckGen", "singletons", !Boolean.parseBoolean(singletons), "noArtifacts", !Boolean.parseBoolean(artifacts)));
        sessions.onMessage(guest, message("limitedOpen", "kind", "sealed"));
        sessions.onMessage(guest, message("setSetting", "key", "soundVolume", "value", "37".equals(volume) ? "38" : "37"));
        Assert.assertEquals(FModel.getPreferences().getPref(FPref.DECKGEN_SINGLETONS), singletons);
        Assert.assertEquals(FModel.getPreferences().getPref(FPref.DECKGEN_ARTIFACTS), artifacts);
        Assert.assertEquals(FModel.getPreferences().getPref(FPref.UI_VOL_SOUNDS), volume, "a guest's setting changed the host's preferences");
        Assert.assertNotEquals(guest.hello.get("screen").getAsString(), "event", "a guest opened the host's Limited pages");
    }

    // Fails if a guest's answer to a question for the host counts, or the host's own answer does not
    @Test(timeOut = 60_000)
    public void onlyTheHostAnswersTheHostsQuestion() throws Exception {
        final TestBrowser host = connect("host");
        sessions.onMessage(host, JsonCodec.message("claimHost"));
        host.awaitMatching("hello", h -> h.get("host").getAsBoolean(), "the host's seat was not taken");
        final TestBrowser guest = connect("guest");
        final CompletableFuture<List<Integer>> waiting = CompletableFuture.supplyAsync(() ->
                ui.hostRequests().ask(HostChoiceKind.confirm, "Title", "Keep going?", List.of("Yes", "No"), 1, 1, null, null));
        final int id = host.awaitMatching("hostChoice", m -> true, "the question never reached the host").get("id").getAsInt();
        sessions.onMessage(guest, answer(id, 1));
        sessions.onMessage(host, answer(id, 0));
        Assert.assertEquals(waiting.get(10, TimeUnit.SECONDS), List.of(0), "the guest's answer was taken");
    }

    private static JsonObject answer(final int id, final int choice) {
        final JsonObject m = message("hostChoice", "id", id);
        final JsonArray value = new JsonArray();
        value.add(choice);
        m.add("value", value);
        return m;
    }

    // Fails if a guest can give a seat at the host's table to another browser, or take a player's seat away
    @Test(timeOut = 60_000)
    public void aGuestCannotHandOverOrRemoveASeat() throws Exception {
        final TestBrowser host = hostAt("invite");
        final TestBrowser ann = connect("ann");
        sessions.onMessage(ann, message("setName", "name", "Ann"));
        final int seat = ann.awaitLobbyWithSeat().get("mySeat").getAsInt();
        sessions.disconnected(ann);
        host.awaitNewest("presence", p -> p.getAsJsonArray("people").asList().stream().map(e -> e.getAsJsonObject()).anyMatch(person ->
                "Ann".equals(str(person, "name")) && person.has("disconnected") && person.get("disconnected").getAsBoolean()),
                "the host was not shown the guest as disconnected");
        final TestBrowser bob = connect("bob");
        sessions.onMessage(bob, message("setName", "name", "Bob"));
        bob.awaitMatching("hello", h -> "Bob".equals(str(h, "playerName")), "the second guest was not named");

        // Each command is handled before onMessage returns, so a seat given away would already be Bob's
        sessions.onMessage(bob, message("giveSeat", "from", "Ann", "to", "Bob"));
        sessions.onMessage(bob, message("dropPlayer", "name", "Ann"));
        Assert.assertNotEquals(bob.hello.get("screen").getAsString(), "setup", "a guest gave itself another player's seat");

        // The host can still give the seat, so the guest's commands neither moved nor removed it
        send(host, message("giveSeat", "from", "Ann", "to", "Bob"));
        bob.awaitLobby(l -> l.get("mySeat").getAsInt() == seat, "the seat was gone before the host gave it");
        send(host, JsonCodec.message("leaveLobby"));
        host.awaitMatching("hello", h -> !"setup".equals(h.get("screen").getAsString()), "the host did not leave its table");
    }
}
