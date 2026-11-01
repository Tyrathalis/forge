package forge.web.match;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.util.IHasForgeLog;
import forge.util.LogSafe;
import forge.web.protocol.ToBrowser;
import forge.web.protocol.Wire;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Blocking prompts that wait for the browser's answer. A request stays open when the browser reloads, because only the asking thread waits on it. */
public final class PendingRequests implements IHasForgeLog {
    private record Pending(JsonObject request, JsonElement defaultAnswer, Predicate<JsonElement> valid, CompletableFuture<JsonElement> answer) {}

    private final Consumer<JsonObject> send;
    private final Supplier<String> who;
    private final Map<Integer, Pending> open = new LinkedHashMap<>();
    private int nextId = 1;

    /** {@code who} gives the name of the player asked, for the log. */
    public PendingRequests(final Consumer<JsonObject> send, final Supplier<String> who) {
        this.send = send;
        this.who = who;
    }

    private static String described(final JsonObject request) {
        final StringBuilder out = new StringBuilder(request.get("kind").getAsString());
        if (request.has("message") && request.get("message").isJsonPrimitive()) {
            out.append(" \"").append(LogSafe.forLog(request.get("message").getAsString(), 120)).append('"');
        }
        if (request.has("options") && request.get("options").isJsonArray()) {
            out.append(", ").append(request.getAsJsonArray("options").size()).append(" options");
        }
        if (request.has("stackKeys") && request.get("stackKeys").isJsonArray()) {
            out.append(", picked on the stack");
        }
        return out.toString();
    }

    /** Sends one of the requests in {@link ToBrowser} and waits for its answer. If the game stops waiting, the default answer is returned. */
    public JsonElement await(final Record payload, final Predicate<JsonElement> valid) {
        final int id;
        final Pending pending;
        synchronized (this) {
            id = nextId++;
            final JsonObject request = Wire.encodeRequest(payload);
            request.addProperty("id", id);
            final JsonElement defaultAnswer = ToBrowser.defaultOf(request);
            pending = new Pending(request, defaultAnswer, valid, new CompletableFuture<>());
            open.put(id, pending);
        }
        netLog.info("Asked {} #{}: {}", who.get(), id, described(pending.request()));
        final long asked = System.nanoTime();
        send.accept(pending.request());
        try {
            final JsonElement answer = pending.answer().get();
            // When the game stops waiting, the answer is the default object itself
            netLog.info("#{} {} after {} ms: {}", id, answer == pending.defaultAnswer() ? "took its default" : "answered",
                    (System.nanoTime() - asked) / 1_000_000, LogSafe.forLog(String.valueOf(answer), 200));
            return answer;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return pending.defaultAnswer();
        } catch (final ExecutionException e) {
            return pending.defaultAnswer();
        } finally {
            synchronized (this) {
                open.remove(id);
            }
        }
    }

    public void complete(final int id, final JsonElement value) {
        final Pending pending;
        synchronized (this) {
            pending = open.get(id);
        }
        if (pending == null) {
            netLog.warn("Reply from {} for unknown request {}", who.get(), id);
            return;
        }
        if (value == null || !pending.valid().test(value)) {
            netLog.warn("Invalid reply from {} for request {}: {}", who.get(), id, LogSafe.forLog(String.valueOf(value), 200));
            return;
        }
        pending.answer().complete(value);
    }

    public void cancelAll() {
        final List<Pending> all;
        synchronized (this) {
            all = new ArrayList<>(open.values());
        }
        for (final Pending p : all) {
            p.answer().complete(p.defaultAnswer());
        }
    }

    public void replay(final Consumer<JsonObject> to) {
        final List<JsonObject> requests = new ArrayList<>();
        synchronized (this) {
            for (final Pending p : open.values()) {
                requests.add(p.request().deepCopy());
            }
        }
        requests.forEach(to);
    }
}
