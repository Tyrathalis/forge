package forge.gamemodes.net.client;

import forge.localinstance.properties.ForgeNetPreferences.FNetPref;
import forge.model.FModel;
import forge.util.IHasForgeLog;

/**
 * Client-side persistence for the reconnect capability issued by a host.
 *
 * <p>Why this exists: before capabilities, a client that crashed and restarted
 * could reclaim its seat by supplying the same username. Requiring a token
 * would remove that unless the token survives a restart, and a security patch
 * that visibly breaks reconnect is a security patch that gets reverted. So the
 * token is written through to preferences, keyed by the host it came from.
 *
 * <p>Only the most recent host's token is retained — you reconnect to the
 * server you just dropped from, not to an arbitrary one — which keeps this to
 * a single preference entry and bounds how long any capability lingers on
 * disk. The token is stored in the clear, at the same trust level as the rest
 * of the preferences file; it authorises reclaiming one seat on one host
 * inside that host's reconnect window and nothing else.
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
