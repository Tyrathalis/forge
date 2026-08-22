package forge.gamemodes.net.event;

/**
 * One custom sleeve's bytes, travelling in-band between peers.
 *
 * <p>Peers exchange <i>content</i>, never an address. A URL field would make every client fetch
 * from a host another player chose - handing that player each viewer's address - and would not even
 * deliver "everyone sees the same sleeve", since a server may vary its answer per requester. The
 * key is the SHA-256 of these bytes, so the recipient can verify that what arrived is what was
 * named, and one blob is one verifiable thing.
 *
 * <p>Rides the existing transport, so whatever secures that connection secures this too - which is
 * the reason not to give sleeves a side channel of their own.
 */
public final class SleeveBlobEvent implements NetEvent {
    private static final long serialVersionUID = 4726511899224677195L;

    private final String key;
    private final byte[] bytes;

    public SleeveBlobEvent(final String key0, final byte[] bytes0) {
        key = key0;
        bytes = bytes0;
    }

    public String getKey() {
        return key;
    }

    public byte[] getBytes() {
        return bytes;
    }

    /** Names the sleeve and its size, never its content: this is written to the network log. */
    @Override
    public String toString() {
        return "SleeveBlobEvent[" + key + ", " + (bytes == null ? 0 : bytes.length) + " bytes]";
    }
}
