package forge.web.match;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.gamemodes.net.DeltaPacket;
import forge.web.protocol.ToBrowser.StateMessage;
import forge.web.protocol.Wire;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The game's objects as the browser holds them. It applies the same rules as src/main/ts/model.ts, so the two copies stay equal. The server keeps this copy to send the current game to a browser that reloads. */
public final class BrowserModel {
    private final Map<Integer, JsonObject> objects = new LinkedHashMap<>();
    private int root = -1;
    private JsonArray visible = new JsonArray();
    private JsonArray localPlayers = new JsonArray();

    public synchronized void reset(final int rootKey) {
        objects.clear();
        root = rootKey;
        visible = new JsonArray();
    }

    public synchronized void apply(final Map<Integer, JsonObject> newObjects, final Map<Integer, JsonObject> deltas) {
        for (final Map.Entry<Integer, JsonObject> e : newObjects.entrySet()) {
            // A property at its default is left out of an object. A null in a new object means the same, so it is removed
            final JsonObject copy = e.getValue().deepCopy();
            copy.entrySet().removeIf(field -> field.getValue().isJsonNull());
            objects.put(e.getKey(), copy);
        }
        for (final Map.Entry<Integer, JsonObject> e : deltas.entrySet()) {
            final JsonObject target = objects.get(e.getKey());
            if (target == null) {
                continue;
            }
            for (final Map.Entry<String, JsonElement> field : e.getValue().entrySet()) {
                if (field.getValue().isJsonNull()) {
                    target.remove(field.getKey());
                } else {
                    target.add(field.getKey(), field.getValue().deepCopy());
                }
            }
        }
        // Only a new object, a cleared property, or a property set to an object or list can make an object unreachable
        final boolean mayOrphan = !newObjects.isEmpty() || deltas.values().stream().anyMatch(props -> props.entrySet().stream()
                .anyMatch(field -> field.getValue().isJsonNull() || field.getValue().isJsonObject() || field.getValue().isJsonArray()));
        if (mayOrphan) {
            prune();
        }
    }

    /** Applies a state message as the page would. Only tests use this, to rebuild the page's model from what was sent. */
    public synchronized void applyStateMessage(final JsonObject msg) {
        if (msg.get("full").getAsBoolean()) {
            reset(msg.get("root").getAsInt());
        }
        apply(keyed(msg.getAsJsonObject("newObjects")), keyed(msg.getAsJsonObject("deltas")));
        visible = msg.getAsJsonArray("visible").deepCopy();
        localPlayers = msg.getAsJsonArray("localPlayers").deepCopy();
    }

    private static Map<Integer, JsonObject> keyed(final JsonObject byKey) {
        final Map<Integer, JsonObject> out = new LinkedHashMap<>();
        for (final Map.Entry<String, JsonElement> e : byKey.entrySet()) {
            out.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonObject());
        }
        return out;
    }

    public synchronized void setVisible(final Collection<Integer> keys) {
        visible = toArray(keys);
    }

    public synchronized void setLocalPlayers(final Collection<Integer> keys) {
        localPlayers = toArray(keys);
    }

    private static JsonArray toArray(final Collection<Integer> keys) {
        final JsonArray a = new JsonArray();
        keys.forEach(a::add);
        return a;
    }

    public synchronized List<Integer> keysOfType(final int type) {
        final List<Integer> keys = new ArrayList<>();
        for (final Integer key : objects.keySet()) {
            if (DeltaPacket.getTypeFromDeltaKey(key) == type) {
                keys.add(key);
            }
        }
        return keys;
    }

    public synchronized JsonObject stateMessage(final boolean full, final long seq, final Map<Integer, JsonObject> newObjects,
            final Map<Integer, JsonObject> deltas, final List<Record> events) {
        return Wire.encode(new StateMessage(full, seq, root, byKey(newObjects), byKey(deltas), ints(visible),
                ints(localPlayers), events));
    }

    public synchronized JsonObject fullState() {
        return stateMessage(true, -1, objects, Map.of(), List.of());
    }

    /** Only tests use this, to compare a model with another without sharing its objects. */
    synchronized Map<Integer, JsonObject> objectsCopy() {
        final Map<Integer, JsonObject> copy = new LinkedHashMap<>();
        objects.forEach((k, v) -> copy.put(k, v.deepCopy()));
        return copy;
    }

    private static Map<String, JsonObject> byKey(final Map<Integer, JsonObject> objects) {
        final Map<String, JsonObject> o = new LinkedHashMap<>();
        objects.forEach((k, v) -> o.put(String.valueOf(k), v.deepCopy()));
        return o;
    }

    private static List<Integer> ints(final JsonArray keys) {
        final List<Integer> out = new ArrayList<>();
        keys.forEach(k -> out.add(k.getAsInt()));
        return out;
    }

    // Packets do not say when an object is removed. So any object the root no longer references is dropped here
    private void prune() {
        if (!objects.containsKey(root)) {
            return;
        }
        final Set<Integer> reachable = new HashSet<>();
        final Deque<Integer> todo = new ArrayDeque<>();
        todo.push(root);
        while (!todo.isEmpty()) {
            final int key = todo.pop();
            if (!reachable.add(key)) {
                continue;
            }
            final JsonObject o = objects.get(key);
            if (o != null) {
                collectRefs(o, todo);
            }
        }
        objects.keySet().retainAll(reachable);
    }

    private static void collectRefs(final JsonElement e, final Deque<Integer> out) {
        if (e.isJsonArray()) {
            for (final JsonElement item : e.getAsJsonArray()) {
                collectRefs(item, out);
            }
        } else if (e.isJsonObject()) {
            final JsonObject o = e.getAsJsonObject();
            if (o.size() == 1 && o.has("ref")) {
                out.push(o.get("ref").getAsInt());
                return;
            }
            for (final Map.Entry<String, JsonElement> field : o.entrySet()) {
                collectRefs(field.getValue(), out);
            }
        }
    }
}
