package forge.web.protocol;

import com.google.gson.JsonObject;

/** Where a message to the browser goes. Implementations must be safe to call from any thread. */
public interface BrowserChannel {
    void send(JsonObject message);

    /** Ends the connection, as when the server has no room for another browser. */
    default void close() {
    }

    /** Sends one of the messages in {@link ToBrowser}. */
    default void send(final Record message) {
        send(Wire.encode(message));
    }
}
