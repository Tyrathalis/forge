package forge.gamemodes.net.event;

public class LoginEvent implements NetEvent {
    private static final long serialVersionUID = -8865183377417377938L;

    private final String username;
    private final int avatarIndex, sleeveIndex;
    private final String version;
    private final boolean libgdx;
    /**
     * Capability previously issued via {@link SessionTokenEvent}, or null on a
     * first join. Must be stripped with {@link #withoutToken()} before the
     * server re-broadcasts a login to the lobby.
     */
    private final String reconnectToken;

    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String version, final boolean libgdx) {
        this(username, avatarIndex, sleeveIndex, version, libgdx, null);
    }

    public LoginEvent(final String username, final int avatarIndex, final int sleeveIndex, final String version, final boolean libgdx, final String reconnectToken) {
        this.username = username;
        this.avatarIndex = avatarIndex;
        this.sleeveIndex = sleeveIndex;
        this.version = version;
        this.libgdx = libgdx;
        this.reconnectToken = reconnectToken;
    }

    public String getReconnectToken() {
        return reconnectToken;
    }

    /** A copy without the capability, for the broadcast path. */
    public LoginEvent withoutToken() {
        return reconnectToken == null ? this
                : new LoginEvent(username, avatarIndex, sleeveIndex, version, libgdx, null);
    }

    public String getUsername() {
        return username;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public int getSleeveIndex() {
        return sleeveIndex;
    }

    public String getVersion() {
        return version;
    }

    public boolean isLibgdx() {
        return libgdx;
    }
}
