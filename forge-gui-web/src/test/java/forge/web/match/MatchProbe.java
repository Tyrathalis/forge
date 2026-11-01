package forge.web.match;

import com.google.gson.JsonObject;

import java.util.Map;

/** Gives tests outside this package access to match internals that the classes keep out of their public API. */
public final class MatchProbe {
    private MatchProbe() {
    }

    /** The number of properties the match dropped because they have no JSON form. Tests check that it stays at zero. */
    public static int skippedProperties(final WebGuiGame gui) {
        return gui.skippedProperties();
    }

    /** The model a browser would have if it were sent the whole game now. Call it on the match's dispatch thread. */
    public static BrowserModel freshSnapshot(final WebGuiGame gui) {
        return gui.freshSnapshot();
    }

    public static Map<Integer, JsonObject> objects(final BrowserModel model) {
        return model.objectsCopy();
    }
}
