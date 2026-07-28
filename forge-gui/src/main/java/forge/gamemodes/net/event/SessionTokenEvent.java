package forge.gamemodes.net.event;

/**
 * Server → one client. Carries the reconnect capability the client presents in
 * a later {@link LoginEvent} to reclaim its seat.
 *
 * <p><b>Never broadcast</b>: that would hand every player every other player's
 * capability, which is the takeover this exists to prevent.
 * {@link #toString()} redacts for the same reason — the protocol handler logs
 * every message it sees.
 */
public class SessionTokenEvent implements NetEvent {
    private static final long serialVersionUID = 4114322205176351320L;

    private final String token;

    public SessionTokenEvent(final String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    @Override
    public String toString() {
        return "SessionTokenEvent[token redacted]";
    }
}
