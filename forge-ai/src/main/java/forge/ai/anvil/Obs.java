package forge.ai.anvil;

import com.github.luben.zstd.ZstdOutputStream;
import forge.LobbyPlayer;
import forge.ai.LobbyPlayerAi;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.AlternativeCost;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetChoices;
import forge.item.PaperCard;

import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Observation/trajectory writer (Anvil M1 D1, docs/design/observation-schema-v1.md).
 * One zstd frame per game appended to a single file, JSONL records inside the
 * frame, sidecar .idx.jsonl with one line per game (offset/lengths/seed/recs).
 *
 * Record kinds: "game" header (schema version, seat order — all player fields
 * elsewhere are indices into it), "dec" (one per PlayerController callback,
 * observation captured at entry, BEFORE delegation can mutate state), "ret"
 * (the answer, written at exit, joined on "s" — separate record because
 * mid-resolution callbacks nest), "end".
 *
 * Static synchronized like Census: one worker plays one game at a time. Writes
 * are additionally gated on Game identity — after a hard-cap timeout the
 * abandoned game thread can keep firing callbacks while the runner has moved
 * on (the no-interrupts rule means it is never killed); those records must not
 * leak into the next game's frame.
 */
public final class Obs {
    public static final int SCHEMA_VERSION = 1;
    private static final int ZSTD_LEVEL = 3;

    private static FileOutputStream file;
    private static PrintWriter idx;
    private static long fileOffset;

    private static CountingStream counting;
    private static OutputStream frame;
    private static Game currentGame;
    private static int gameIdx = -1;
    private static long gameSeed;
    private static long seq;      // per-game; "s" joins dec/ret
    private static long recs;
    private static long rawBytes;
    private static long obsErrors;

    private Obs() {
    }

    public static synchronized boolean isOpen() {
        return file != null;
    }

    /** True when records for this Game would actually be written (frame open,
     *  not a stale post-hard-cap thread). Lets callers skip expensive
     *  materialization work that only feeds the log. */
    public static synchronized boolean isLogging(Game g) {
        return frame != null && g == currentGame;
    }

    public static synchronized void open(String path) throws IOException {
        file = new FileOutputStream(path, true);
        fileOffset = file.getChannel().position();
        String idxPath = path.endsWith(".zst")
                ? path.substring(0, path.length() - 4) + ".idx.jsonl" : path + ".idx.jsonl";
        idx = new PrintWriter(new FileWriter(idxPath, true));
    }

    public static synchronized void close() {
        endGameFrame();
        if (idx != null) {
            idx.flush();
            idx.close();
            idx = null;
        }
        if (file != null) {
            try {
                file.close();
            } catch (IOException ignored) {
            }
            file = null;
        }
        if (obsErrors > 0) {
            System.err.println("Obs: " + obsErrors + " observation serialization errors (obs:null records)");
        }
    }

    public static synchronized void startGame(int idx0, long seed, Game g, String fmt) {
        if (file == null) {
            return;
        }
        endGameFrame(); // defensive: a crashed game may not have reached endGame
        gameIdx = idx0;
        gameSeed = seed;
        currentGame = g;
        seq = 0;
        recs = 0;
        rawBytes = 0;
        counting = new CountingStream(file);
        try {
            frame = new ZstdOutputStream(counting, ZSTD_LEVEL);
        } catch (IOException e) {
            System.err.println("Obs: cannot open zstd frame for game " + idx0 + ": " + e);
            frame = null;
            currentGame = null;
            return;
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"k\":\"game\",\"sv\":").append(SCHEMA_VERSION)
                .append(",\"g\":").append(idx0)
                .append(",\"seed\":").append(seed)
                .append(",\"fmt\":").append(q(fmt))
                .append(",\"players\":[");
        int i = 0;
        for (Player p : g.getRegisteredPlayers()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append("{\"name\":").append(q(p.getName()));
            RegisteredPlayer rp = p.getRegisteredPlayer();
            if (rp != null && rp.getDeck() != null) {
                sb.append(",\"deck\":").append(q(rp.getDeck().getName()));
                if (!rp.getCommanders().isEmpty()) {
                    sb.append(",\"cmd\":[");
                    int j = 0;
                    for (PaperCard pc : rp.getCommanders()) {
                        if (j++ > 0) {
                            sb.append(',');
                        }
                        sb.append(q(pc.getName()));
                    }
                    sb.append(']');
                }
            }
            LobbyPlayer lp = p.getLobbyPlayer();
            if (lp instanceof LobbyPlayerAi) {
                String profile = ((LobbyPlayerAi) lp).getAiProfile();
                if (profile != null && !profile.isEmpty()) {
                    sb.append(",\"profile\":").append(q(profile));
                }
            }
            sb.append('}');
        }
        sb.append("]}");
        write(sb);
    }

    public static synchronized void endGame(String status, int winnerIdx, int turns, long ms, boolean drawClock) {
        if (frame == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"k\":\"end\",\"status\":").append(q(status))
                .append(",\"winner\":").append(winnerIdx)
                .append(",\"turns\":").append(turns)
                .append(",\"ms\":").append(ms);
        if (drawClock) {
            sb.append(",\"draw_clock\":true");
        }
        sb.append('}');
        write(sb);
        endGameFrame();
    }

    /**
     * Decision record at callback entry; returns the seq to pass to ret(), or
     * -1 when not logging. The observation snapshots state before the
     * heuristic/bridge answers (some callbacks resolve their own effects).
     */
    public static long dec(Game g, Player p, String m, Object... kv) {
        return decInternal(g, p, m, null, null, false, kv);
    }

    /** Bridged variant: provenance tag + materialized option labels. */
    public static long decBridged(Game g, Player p, String m, java.util.List<String> opts, Object... kv) {
        return decInternal(g, p, m, "bridge", opts, false, kv);
    }

    /**
     * Priority-window dec (M1 D2): materializes the engine-legal option set
     * (same scan as the bridged path — legal-actions-only invariant) into
     * structured opts entries {"e":hostId,"sa":str,"kind":...} so the label
     * has a logged legality basis (replay drift forbids recomputing it later;
     * the gate metric's single-legal-option exclusion needs it). Pass is not
     * an entry: a null ret IS the pass. Options are scanned only when this
     * game is actually being logged.
     */
    public static long decPriority(Game g, Player p) {
        if (!isLogging(g)) {
            return -1;
        }
        java.util.List<String> opts = null;
        java.util.List<Card> extraEnts = null;
        try {
            java.util.List<SpellAbility> options = AnvilOptions.priorityOptions(g, p);
            opts = new java.util.ArrayList<>(options.size());
            for (SpellAbility sa : options) {
                StringBuilder ob = new StringBuilder(96);
                Card h = sa.getHostCard();
                ob.append("{\"e\":").append(h == null ? -1 : h.getId())
                        .append(",\"sa\":").append(q(trunc(String.valueOf(sa))))
                        .append(",\"kind\":\"").append(kind(sa)).append("\"}");
                opts.add(ob.toString());
                // Hosts castable from an unwalked zone (library top): the
                // snapshot must contain them or the label references nothing.
                if (h != null && h.getZone() != null
                        && h.getZone().getZoneType() == forge.game.zone.ZoneType.Library
                        && (extraEnts == null || !extraEnts.contains(h))) {
                    if (extraEnts == null) {
                        extraEnts = new java.util.ArrayList<>(2);
                    }
                    extraEnts.add(h);
                }
            }
        } catch (Exception e) {
            opts = null; // options are diagnostic-critical but not corpus-fatal
            obsErrors++;
        }
        return decInternal(g, p, "chooseSpellAbilityToPlay", null, opts, true, extraEnts);
    }

    private static synchronized long decInternal(Game g, Player p, String m, String by,
            java.util.List<String> opts, boolean optsRaw, Object... kv) {
        return decInternal(g, p, m, by, opts, optsRaw, null, kv);
    }

    private static synchronized long decInternal(Game g, Player p, String m, String by,
            java.util.List<String> opts, boolean optsRaw, java.util.List<Card> extraEnts,
            Object... kv) {
        if (frame == null || g != currentGame) {
            return -1;
        }
        long s = seq++;
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\"k\":\"dec\",\"s\":").append(s);
        int turn = -1;
        String phase = null;
        try {
            PhaseHandler ph = g.getPhaseHandler();
            if (ph != null) {
                turn = ph.getTurn();
                PhaseType pt = ph.getPhase();
                phase = pt == null ? null : pt.toString();
            }
        } catch (Exception ignored) {
        }
        sb.append(",\"t\":").append(turn)
                .append(",\"ph\":").append(q(phase))
                .append(",\"p\":").append(p == null ? -1 : g.getRegisteredPlayers().indexOf(p))
                .append(",\"m\":\"").append(m).append('"')
                .append(",\"d\":").append(Thread.currentThread().getStackTrace().length);
        if (by != null) {
            sb.append(",\"by\":").append(q(by));
        }
        if (kv.length > 1) {
            sb.append(",\"args\":{");
            for (int i = 0; i + 1 < kv.length; i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(kv[i]).append("\":").append(scalar(kv[i + 1]));
            }
            sb.append('}');
        }
        if (opts != null) {
            sb.append(",\"opts\":[");
            for (int i = 0; i < opts.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                // raw entries are pre-rendered JSON objects (decPriority);
                // otherwise plain labels, quoted
                sb.append(optsRaw ? opts.get(i) : q(opts.get(i)));
            }
            sb.append(']');
        }
        sb.append(",\"obs\":");
        int obsStart = sb.length();
        try {
            ObsSnapshot.write(sb, g, extraEnts);
        } catch (Exception e) {
            sb.setLength(obsStart);
            sb.append("null,\"err\":").append(q(e.toString()));
            obsErrors++;
        }
        sb.append('}');
        write(sb);
        return s;
    }

    /** Answer record at callback exit; joined to its dec on "s". */
    public static synchronized void ret(Game g, long s, Object v) {
        if (frame == null || s < 0 || g != currentGame) {
            return;
        }
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"k\":\"ret\",\"s\":").append(s).append(",\"v\":");
        value(sb, v, 0);
        sb.append('}');
        write(sb);
    }

    // ---------- answer-value serialization (best-effort v1; see schema doc) ----------

    private static void value(StringBuilder sb, Object v, int depth) {
        if (v == null) {
            sb.append("null");
            return;
        }
        if (depth > 3) {
            sb.append(q(trunc(String.valueOf(v))));
            return;
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) {
            sb.append(v);
        } else if (v instanceof String) {
            sb.append(q((String) v));
        } else if (v instanceof Card) {
            sb.append("{\"e\":").append(((Card) v).getId()).append('}');
        } else if (v instanceof Player) {
            Player p = (Player) v;
            sb.append("{\"pi\":").append(p.getGame().getRegisteredPlayers().indexOf(p)).append('}');
        } else if (v instanceof SpellAbility) {
            castPlan(sb, (SpellAbility) v, depth);
        } else if (v.getClass().isEnum()) {
            sb.append(q(((Enum<?>) v).name()));
        } else if (v instanceof Map) {
            sb.append("{\"map\":[");
            int i = 0;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (i++ > 0) {
                    sb.append(',');
                }
                if (i > 64) {
                    sb.append("\"...\"");
                    break;
                }
                sb.append('[');
                value(sb, e.getKey(), depth + 1);
                sb.append(',');
                value(sb, e.getValue(), depth + 1);
                sb.append(']');
            }
            sb.append("]}");
        } else if (v instanceof Iterable) {
            sb.append('[');
            int i = 0;
            for (Object o : (Iterable<?>) v) {
                if (i++ > 0) {
                    sb.append(',');
                }
                if (i > 128) {
                    sb.append("\"...\"");
                    break;
                }
                value(sb, o, depth + 1);
            }
            sb.append(']');
        } else if (v instanceof org.apache.commons.lang3.tuple.Pair) {
            org.apache.commons.lang3.tuple.Pair<?, ?> pr = (org.apache.commons.lang3.tuple.Pair<?, ?>) v;
            sb.append('[');
            value(sb, pr.getLeft(), depth + 1);
            sb.append(',');
            value(sb, pr.getRight(), depth + 1);
            sb.append(']');
        } else {
            sb.append("{\"str\":").append(q(trunc(String.valueOf(v)))).append('}');
        }
    }

    /**
     * CastPlan-shaped serialization of a chosen SpellAbility (M1 D2, schema
     * doc "CastPlan ret"): everything the heuristic decided at cast-decision
     * time that never surfaces as a callback (census): targets and X read off
     * the SA; optional costs already baked in (the SA the AI returns is the
     * copy GameActionUtil.addOptionalCosts built); modes only when already
     * bound — for cast spells they usually bind later at chooseModeForAbility,
     * which is logged as its own dec/ret. Sub-abilities contribute their own
     * pre-set targets.
     */
    private static void castPlan(StringBuilder sb, SpellAbility sa, int depth) {
        sb.append('{');
        if (sa.getHostCard() != null) {
            sb.append("\"e\":").append(sa.getHostCard().getId()).append(',');
        }
        sb.append("\"sa\":").append(q(trunc(String.valueOf(sa))))
                .append(",\"kind\":\"").append(kind(sa)).append('"');
        targets(sb, sa);
        Integer x = sa.getXManaCostPaid();
        if (x != null) {
            sb.append(",\"x\":").append(x);
        }
        AlternativeCost alt = sa.getAlternativeCost();
        if (alt != null) {
            sb.append(",\"alt\":").append(q(alt.name()));
        }
        int nOpt = 0;
        for (OptionalCost oc : sa.getOptionalCosts()) {
            sb.append(nOpt++ == 0 ? ",\"opt\":[" : ",").append(q(oc.name()));
        }
        if (nOpt > 0) {
            sb.append(']');
        }
        int mk = sa.getOptionalKeywordAmount(Keyword.MULTIKICKER);
        if (mk > 0) {
            sb.append(",\"mk\":").append(mk);
        }
        java.util.List<AbilitySub> modes = sa.getChosenList();
        if (modes != null && !modes.isEmpty() && depth < 3) {
            sb.append(",\"modes\":[");
            int j = 0;
            for (AbilitySub mode : modes) {
                if (j++ > 0) {
                    sb.append(',');
                }
                castPlan(sb, mode, depth + 1);
            }
            sb.append(']');
        }
        // sub-ability chain: only links that carry their own targets, indexed
        // by chain position (bounded — chains are hand-authored card script)
        int link = 0;
        int emitted = 0;
        for (SpellAbility sub = sa.getSubAbility(); sub != null && link < 16; sub = sub.getSubAbility(), link++) {
            TargetChoices tc = sub.getTargets();
            if (tc == null || tc.isEmpty()) {
                continue;
            }
            sb.append(emitted++ == 0 ? ",\"sub\":[" : ",").append("{\"i\":").append(link);
            targets(sb, sub);
            sb.append('}');
        }
        if (emitted > 0) {
            sb.append(']');
        }
        sb.append('}');
    }

    private static String kind(SpellAbility sa) {
        return sa.isLandAbility() ? "land" : sa.isSpell() ? "spell"
                : sa.isActivatedAbility() ? "ability" : "other";
    }

    /** Root-level targets in the observation idiom: {"e":id} / {"pi":seat} /
     *  {"e":hostId,"stk":1} for a targeted stack SA (stack entries are keyed
     *  by host card id in ObsSnapshot, so this joins). */
    private static void targets(StringBuilder sb, SpellAbility sa) {
        TargetChoices tc = sa.getTargets();
        if (tc == null || tc.isEmpty()) {
            return;
        }
        sb.append(",\"tgt\":[");
        int i = 0;
        for (Object t : tc) {
            if (i++ > 0) {
                sb.append(',');
            }
            if (t instanceof Card) {
                sb.append("{\"e\":").append(((Card) t).getId()).append('}');
            } else if (t instanceof Player) {
                Player tp = (Player) t;
                sb.append("{\"pi\":").append(tp.getGame().getRegisteredPlayers().indexOf(tp)).append('}');
            } else if (t instanceof SpellAbility) {
                SpellAbility ts = (SpellAbility) t;
                sb.append("{\"e\":").append(ts.getHostCard() == null ? -1 : ts.getHostCard().getId())
                        .append(",\"stk\":1}");
            } else {
                sb.append("{\"str\":").append(q(trunc(String.valueOf(t)))).append('}');
            }
        }
        sb.append(']');
    }

    private static String scalar(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Integer || o instanceof Long || o instanceof Boolean) {
            return o.toString();
        }
        return q(String.valueOf(o));
    }

    private static String trunc(String s) {
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    // ---------- frame plumbing ----------

    private static void write(StringBuilder sb) {
        sb.append('\n');
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        try {
            frame.write(bytes);
            rawBytes += bytes.length;
            recs++;
        } catch (IOException e) {
            System.err.println("Obs: write failed, disabling for this run: " + e);
            frame = null;
            currentGame = null;
        }
    }

    private static void endGameFrame() {
        if (frame == null) {
            return;
        }
        try {
            frame.close(); // finishes the zstd frame; CountingStream shields the file
        } catch (IOException e) {
            System.err.println("Obs: frame close failed for game " + gameIdx + ": " + e);
        }
        if (idx != null) {
            idx.println("{\"g\":" + gameIdx + ",\"off\":" + fileOffset + ",\"clen\":" + counting.count
                    + ",\"rlen\":" + rawBytes + ",\"seed\":" + gameSeed + ",\"recs\":" + recs + "}");
            idx.flush();
        }
        fileOffset += counting.count;
        frame = null;
        counting = null;
        currentGame = null;
    }

    static String q(String s) {
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

    /** Counts bytes through to the file; close() flushes but never closes it. */
    private static final class CountingStream extends OutputStream {
        private final OutputStream out;
        long count;

        CountingStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }
}
