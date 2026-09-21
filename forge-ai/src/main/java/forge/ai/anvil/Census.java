package forge.ai.anvil;

import forge.game.Game;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.collect.FCollectionView;

import java.io.FileWriter;
import java.io.IOException;
import java.util.WeakHashMap;
import java.util.Collections;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.Map;

/**
 * Callback-census logger for CensusPlayerController: one JSONL line per
 * PlayerController callback (method, turn, phase, stack depth, cheap arg
 * summaries). Static because controller instances are created per game by the
 * factory while the log spans a whole run; one census process runs one game at
 * a time, so a single writer is safe (rec() is synchronized regardless).
 */
public final class Census {
    /**
     * Per-game byte cap on census rows — the runaway guard mirroring
     * Obs.RAW_CAP. A game wedged in a decision loop until the hard-cap
     * timeout writes one row per callback for the whole 360s (d6-run13
     * i006: one such game grew a worker's census.jsonl to GB scale; every
     * stale-data pass to date has trimmed this exact pattern). A healthy
     * game emits ~15 KB, so 16 MiB is ~1000x headroom; past it, rows for
     * the current game are dropped after one loud "census_cap" marker.
     * Start/end records still land, so per-game accounting stays intact.
     */
    private static final long RAW_CAP = Long.getLong("anvil.census.rawcap", 16L << 20);

    private static PrintWriter out;
    private static long seq;
    private static int gameIdx = -1;
    private static long gameStartMs;
    private static long gameBytes;
    private static boolean capped;

    private Census() {
    }

    public static synchronized void open(String path) throws IOException {
        out = new PrintWriter(new FileWriter(path, true));
    }

    public static synchronized void close() {
        if (out != null) {
            out.flush();
            out.close();
            out = null;
        }
    }

    public static synchronized void startGame(int idx, long seed) {
        gameIdx = idx;
        gameStartMs = System.currentTimeMillis();
        gameBytes = 0;
        capped = false;
        if (out != null) {
            out.println("{\"ev\":\"start\",\"g\":" + idx + ",\"seed\":" + seed + "}");
        }
    }

    public static synchronized void endGame(String winner, int turns) {
        if (out != null) {
            out.println("{\"ev\":\"end\",\"g\":" + gameIdx + ",\"winner\":" + quote(winner)
                    + ",\"turns\":" + turns + ",\"ms\":" + (System.currentTimeMillis() - gameStartMs)
                    + (capped ? ",\"cap\":true" : "") + "}");
            out.flush();
        }
    }

    // ---- M12 Build 2 loop tripwire (ADR-0104 addendum, 09-07): an engine re-ask
    // loop (ChooseSourceEffect's do/while on a null controller answer — game 989
    // of the dzla10 arm asked "choose a source" 150K times in one window) is the
    // same controller callback with the same arguments, consecutively, without
    // end. Count consecutive identical callbacks per Game; past LOOP_TRIP the
    // game is capped as a Draw with reason "loop:<method>" and the Surfaces
    // force hooks answer the first option so the engine's loop can exit. Runs
    // whether or not a census file is open (the guard is not telemetry).
    private static final int LOOP_TRIP = Integer.getInteger("anvil.loop.trip", 256);

    private static final class LoopState {
        String lastKey;
        int n;
        boolean tripped;
    }

    private static final Map<Game, LoopState> loops = Collections.synchronizedMap(new WeakHashMap<>());
    private static final java.util.Set<String> loopClassesPrinted =
            Collections.synchronizedSet(new java.util.HashSet<>());

    public static boolean loopTripped(Game g) {
        LoopState st = g == null ? null : loops.get(g);
        return st != null && st.tripped;
    }

    private static void loopCheck(Game g, Player p, String method, Object... kv) {
        if (g == null || g.isGameOver()) {
            return;
        }
        StringBuilder k = new StringBuilder(96).append(method).append('|').append(p == null ? "" : p.getName());
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number || v instanceof Boolean || v instanceof String) {
                k.append('|').append(v);
            }
        }
        String key = k.toString();
        LoopState st = loops.computeIfAbsent(g, x -> new LoopState());
        if (key.equals(st.lastKey)) {
            st.n++;
        } else {
            st.lastKey = key;
            st.n = 1;
        }
        if (st.n >= LOOP_TRIP && !st.tripped) {
            st.tripped = true;
            g.setAnvilCapReason("loop:" + method);
            g.setGameOver(forge.game.GameEndReason.Draw);
            if (loopClassesPrinted.add(method)) {
                System.err.println("[anvil] LOOP TRIP after " + st.n + " identical " + method + " callbacks: " + key);
            }
        }
    }

    public static synchronized void rec(Game g, Player p, String method, Object... kv) {
        loopCheck(g, p, method, kv);
        if (out == null || capped) {
            return;
        }
        int turn = -1;
        String phase = null;
        try {
            PhaseHandler ph = g == null ? null : g.getPhaseHandler();
            if (ph != null) {
                turn = ph.getTurn();
                PhaseType pt = ph.getPhase();
                phase = pt == null ? null : pt.toString();
            }
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"s\":").append(seq++)
                .append(",\"g\":").append(gameIdx)
                .append(",\"t\":").append(turn)
                .append(",\"ph\":").append(quote(phase))
                .append(",\"p\":").append(quote(p == null ? null : p.getName()))
                .append(",\"m\":\"").append(method).append('"')
                .append(",\"d\":").append(Thread.currentThread().getStackTrace().length);
        // 09-21 (ADR-0114 routed): a search copy's rows are marked so a
        // searched arm's mainline rates read from the mainline decs alone
        // (the copies' forced asks dominated the raw priority census).
        if (SearchDirective.isCopy(g)) {
            boolean explicit = false;
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if ("copy".equals(kv[i])) {
                    explicit = true;
                    break;
                }
            }
            if (!explicit) {
                sb.append(",\"copy\":true");
            }
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(",\"").append(kv[i]).append("\":").append(val(kv[i + 1]));
        }
        sb.append('}');
        // sb.length()+1 approximates UTF-8 bytes (rows are ASCII-dominated);
        // a cap this coarse doesn't need exact byte accounting
        if (gameBytes + sb.length() + 1 > RAW_CAP) {
            capped = true;
            System.err.println("Census: game " + gameIdx + " hit raw cap ("
                    + gameBytes + " bytes), dropping rows until game end");
            out.println("{\"ev\":\"census_cap\",\"g\":" + gameIdx + ",\"s\":" + seq
                    + ",\"bytes\":" + gameBytes + "}");
            out.flush();
            return;
        }
        gameBytes += sb.length() + 1;
        out.println(sb);
    }

    /** Size of a collection-ish argument; -1 for null/unknown. */
    public static int sz(Object o) {
        if (o == null) {
            return -1;
        }
        if (o instanceof Collection) {
            return ((Collection<?>) o).size();
        }
        if (o instanceof Map) {
            return ((Map<?, ?>) o).size();
        }
        if (o instanceof FCollectionView) {
            return ((FCollectionView<?>) o).size();
        }
        if (o instanceof com.google.common.collect.Multimap) {
            return ((com.google.common.collect.Multimap<?, ?>) o).size();
        }
        if (o instanceof Iterable) {
            int n = 0;
            for (Object ignored : (Iterable<?>) o) {
                n++;
            }
            return n;
        }
        return -1;
    }

    /** Truncated toString for entity-ish arguments. */
    public static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static String val(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Boolean) {
            return o.toString();
        }
        return quote(String.valueOf(o));
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
