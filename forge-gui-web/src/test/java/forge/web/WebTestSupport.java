package forge.web;

import forge.StaticData;
import forge.game.player.Player;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.PlayerControllerHuman;
import org.testng.SkipException;

public final class WebTestSupport {
    private WebTestSupport() {}

    /** GuiBase must be set before ForgeConstants loads, because its static init reads the assets dir. */
    public static synchronized void initModel() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new WebGuiBase());
        }
        if (StaticData.instance() == null) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            }, false);
        }
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
    }

    /** The host's view of the web seat. Call {@code updateGameView()} on it after a test seeds cards, because seeding fires no event. */
    static RemoteClientGuiGame remoteGui(final HostedMatch match) {
        for (final Player p : match.getGame().getPlayers()) {
            if (p.getController() instanceof PlayerControllerHuman pch && pch.getGui() instanceof RemoteClientGuiGame r) {
                return r;
            }
        }
        throw new AssertionError("no remote human seat");
    }

    /** Skips a slow test unless -Drun.stress.tests=true is set. Forge's other network tests use the same switch. */
    static void skipUnlessStress() {
        if (!"true".equalsIgnoreCase(System.getProperty("run.stress.tests"))) {
            throw new SkipException("Slow test skipped. Use -Drun.stress.tests=true to run.");
        }
    }
}
