package forge.ai.anvil;

import forge.game.Game;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.util.collect.FCollectionView;

import java.io.FileWriter;
import java.io.IOException;
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
    private static PrintWriter out;
    private static long seq;
    private static int gameIdx = -1;
    private static long gameStartMs;

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
        if (out != null) {
            out.println("{\"ev\":\"start\",\"g\":" + idx + ",\"seed\":" + seed + "}");
        }
    }

    public static synchronized void endGame(String winner, int turns) {
        if (out != null) {
            out.println("{\"ev\":\"end\",\"g\":" + gameIdx + ",\"winner\":" + quote(winner)
                    + ",\"turns\":" + turns + ",\"ms\":" + (System.currentTimeMillis() - gameStartMs) + "}");
            out.flush();
        }
    }

    public static synchronized void rec(Game g, Player p, String method, Object... kv) {
        if (out == null) {
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
        for (int i = 0; i + 1 < kv.length; i += 2) {
            sb.append(",\"").append(kv[i]).append("\":").append(val(kv[i + 1]));
        }
        sb.append('}');
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
