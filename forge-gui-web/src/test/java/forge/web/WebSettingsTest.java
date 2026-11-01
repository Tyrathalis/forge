package forge.web;

import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.web.protocol.ToBrowser.ServerSettings;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

public class WebSettingsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    private static Map<FPref, String> hostPrefs() {
        final Map<FPref, String> out = new EnumMap<>(FPref.class);
        Arrays.stream(FPref.values()).forEach(p -> out.put(p, FModel.getPreferences().getPref(p)));
        return out;
    }

    // Fails if a guest's change is not kept for that guest, or reaches the host's preferences
    @Test
    public void aGuestsChangeIsItsOwn() {
        final Map<FPref, String> host = hostPrefs();
        final PlayerSettings guest = PlayerSettings.fresh();
        final boolean attackers = guest.getBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS);
        WebSettings.set(guest, null, "interruptAttackers", String.valueOf(!attackers));
        WebSettings.set(guest, null, "soundVolume", "37");
        WebSettings.set(guest, null, "devMode", "true");
        Assert.assertEquals(guest.getBoolean(FPref.YIELD_INTERRUPT_ON_ATTACKERS), !attackers);
        Assert.assertEquals(guest.get(FPref.UI_VOL_SOUNDS), "37");
        Assert.assertTrue(guest.getBoolean(FPref.DEV_MODE_ENABLED));
        Assert.assertEquals(hostPrefs(), host, "a guest's setting changed the host's preferences");
        Assert.assertFalse(PlayerSettings.fresh().getBoolean(FPref.DEV_MODE_ENABLED), "one guest's setting reached another guest");
    }

    // Fails if a value outside what the preference allows, or a key that names no setting, changes anything
    @Test
    public void badValuesAndUnknownKeysAreRefused() {
        final PlayerSettings guest = PlayerSettings.fresh();
        final ServerSettings before = WebSettings.values(guest);
        WebSettings.set(guest, null, "soundVolume", "101");
        WebSettings.set(guest, null, "musicVolume", "-1");
        WebSettings.set(guest, null, "soundVolume", "loud");
        WebSettings.set(guest, null, FPref.SHORTCUT_SHOWSTACK.name(), "ctrl+s");
        WebSettings.set(guest, null, "PLAYER_NAME", "Mallory");
        WebSettings.set(guest, null, "nonsense", "true");
        Assert.assertEquals(WebSettings.values(guest), before);
        Assert.assertNotEquals(guest.get(FPref.PLAYER_NAME), "Mallory", "a key that names a preference but no setting set it");
    }

    // Fails if a guest can change the rules of the host's table, for itself or for the host
    @Test
    public void aGuestCannotChangeTheTablesRules() {
        final Map<FPref, String> host = hostPrefs();
        final PlayerSettings guest = PlayerSettings.fresh();
        final ServerSettings before = WebSettings.values(guest);
        WebSettings.set(guest, null, "filteredHands", String.valueOf(!guest.getBoolean(FPref.FILTERED_HANDS)));
        WebSettings.set(guest, null, "mulliganRule", "Original");
        WebSettings.set(guest, null, "undoMode", "off".equals(guest.get(FPref.UI_UNDO_MODE)) ? "free" : "off");
        WebSettings.set(guest, null, "ante", String.valueOf(!guest.getBoolean(FPref.UI_ANTE)));
        WebSettings.set(guest, null, "aiProfile", "Reckless");
        Assert.assertEquals(WebSettings.values(guest), before);
        Assert.assertEquals(hostPrefs(), host);
    }

    // Fails if the host's change is not saved to the preferences file, or the host can set a table rule Forge does not know
    @Test
    public void theHostsChangeIsSaved() {
        final ForgePreferences prefs = FModel.getPreferences();
        final String volume = prefs.getPref(FPref.UI_VOL_SOUNDS);
        final String undo = prefs.getPref(FPref.UI_UNDO_MODE);
        try {
            final String changed = "37".equals(volume) ? "38" : "37";
            WebSettings.set(PlayerSettings.saved(), null, "soundVolume", changed);
            Assert.assertEquals(new ForgePreferences().getPref(FPref.UI_VOL_SOUNDS), changed);
            WebSettings.set(PlayerSettings.saved(), null, "undoMode", "sometimes");
            Assert.assertEquals(prefs.getPref(FPref.UI_UNDO_MODE), undo);
        } finally {
            prefs.setPref(FPref.UI_VOL_SOUNDS, volume);
            prefs.setPref(FPref.UI_UNDO_MODE, undo);
            prefs.save();
        }
    }
}
