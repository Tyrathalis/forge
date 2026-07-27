package forge.gamemodes.net.event;

/**
 * Server → one client. Carries the reconnect capability that the client must
 * present in a later {@link LoginEvent} to reclaim its seat after a
 * disconnect.
 *
 * <p>This event is <b>never broadcast</b>. It is minted per client and
 * delivered only to the client it belongs to; a broadcast would hand every
 * player every other player's capability, which is exactly the takeover this
 * is meant to prevent.
 *
 * <p>{@link #toString()} deliberately redacts the token: the protocol handler
 * logs every inbound message with {@code netLog.info("Received: {}", msg)},
 * so a naive toString would write capabilities to the network log in
 * plaintext.
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
