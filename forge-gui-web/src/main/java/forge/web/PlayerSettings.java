package forge.web;

import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;

import java.util.EnumMap;
import java.util.Map;

/** Every setting a player can change is read through this class, never straight from the preferences. This stops one player's choice from changing another player's settings. */
public final class PlayerSettings {
    /** Null for the host, whose settings are the preferences themselves. */
    private final Map<FPref, String> own;

    private PlayerSettings(final Map<FPref, String> own) {
        this.own = own;
    }

    /** The host's settings. These are Forge's preferences, which the desktop client also uses. */
    public static PlayerSettings saved() {
        return new PlayerSettings(null);
    }

    /** A guest's settings, starting from Forge's defaults. */
    static PlayerSettings fresh() {
        return new PlayerSettings(new EnumMap<>(FPref.class));
    }

    public synchronized String get(final FPref pref) {
        if (own == null) {
            return FModel.getPreferences().getPref(pref);
        }
        final String value = own.get(pref);
        return value != null ? value : pref.getDefault();
    }

    /** Whether these are the host's settings, which are Forge's own preferences. */
    boolean shared() {
        return own == null;
    }

    public boolean getBoolean(final FPref pref) {
        return Boolean.parseBoolean(get(pref));
    }

    public int getInt(final FPref pref) {
        try {
            return Integer.parseInt(get(pref));
        } catch (final NumberFormatException e) {
            return Integer.parseInt(pref.getDefault());
        }
    }

    public synchronized void set(final FPref pref, final String value) {
        if (own == null) {
            FModel.getPreferences().setPref(pref, value);
        } else {
            own.put(pref, value);
        }
    }

    public void set(final FPref pref, final boolean value) {
        set(pref, String.valueOf(value));
    }

    /** Saves what was set. Only the host's settings are kept after the process ends. A guest's browser stores its own. */
    public void save() {
        if (own == null) {
            FModel.getPreferences().save();
        }
    }
}
