package forge.gamemodes.net.client;

import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;

/**
 * Client-side persistence for the reconnect capability issued by a host. The
 * token has to survive a restart, or requiring it would break reconnect for a
 * client that crashed — which previously worked on username alone.
 *
 * <p>Only the most recent host's token is kept: you reconnect to the server you
 * just dropped from, which bounds both the preference file and how long a
 * capability lingers on disk. Stored in the clear, at the same trust level as
 * the rest of the preferences.
 */
final class ReconnectTokenStore implements IHasForgeLog {

    private static final String SEP = " ";

    private ReconnectTokenStore() {
    }

    private static String key(final String hostname, final Integer port, final String username) {
        return hostname + ":" + port + SEP + username;
    }

    /** The stored token for this host/user, or null if we have none. */
    static String load(final String hostname, final Integer port, final String username) {
        try {
            final String stored = FModel.getNetPreferences().getPref(FNetPref.NET_RECONNECT_TOKEN);
            if (stored == null || stored.isEmpty()) {
                return null;
            }
            final int split = stored.lastIndexOf(SEP);
            if (split < 0) {
                return null;
            }
            final String storedKey = stored.substring(0, split);
            final String token = stored.substring(split + SEP.length());
            return storedKey.equals(key(hostname, port, username)) ? token : null;
        } catch (final Exception e) {
            // Preferences unavailable (e.g. headless harness with no FModel).
            // A missing token only costs us the seat reclaim; never fail connect.
            netLog.info("Reconnect token unavailable: {}", e.toString());
            return null;
        }
    }

    static void save(final String hostname, final Integer port, final String username, final String token) {
        try {
            final var prefs = FModel.getNetPreferences();
            prefs.setPref(FNetPref.NET_RECONNECT_TOKEN,
                    token == null || token.isEmpty() ? "" : key(hostname, port, username) + SEP + token);
            prefs.save();
        } catch (final Exception e) {
            netLog.info("Could not persist reconnect token: {}", e.toString());
        }
    }
}
