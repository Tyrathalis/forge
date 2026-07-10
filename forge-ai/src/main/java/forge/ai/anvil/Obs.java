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
 *
 * Runaway guard: a wedged post-hard-cap thread can write observations
 * indefinitely (pilot: one game wrote 24.6 GB raw in ~6 min against a legit
 * max of 571 MB before the worker was killed mid-write, leaving a truncated
 * frame). RAW_CAP truncates the frame with a loud "obs_cap" end record —
 * policy labels up to the cut stay usable; the status keeps the game out of
 * the value loss. The cap firing also releases the Obs lock promptly so the
 * runner's endGame/close are never starved by a wedge.
 */
public final class Obs {
    public static final int SCHEMA_VERSION = 1;
    private static final int ZSTD_LEVEL = 3;
    /** Per-game raw-byte ceiling; 2x the 50K-pilot's largest legit frame. */
    private static final long RAW_CAP = Long.getLong("anvil.obs.rawcap", 1L << 30);

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

    // ---- M1 D8 bridge support: wire-record capture, oi labels, history ring ----
    /** = anvil.encoder.transform.HISTORY_K; the wire observation carries the
     *  last K completed decisions so the server featurizes identically to
     *  training (info-set rule applied Python-side, where it is leak-tested). */
    private static final int HIST_CAP = 8;
    private static final java.util.ArrayDeque<String> histRing = new java.util.ArrayDeque<>();
    private static final java.util.LinkedHashMap<Long, String[]> pendingDec = new java.util.LinkedHashMap<>();
    private static long prioSeq = -1;
    private static java.util.List<SpellAbility> prioOptions;
    private static String lastDecRecord;
    private static String lastHeaderRecord;

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
        histRing.clear();
        pendingDec.clear();
        prioSeq = -1;
        prioOptions = null;
        lastDecRecord = null;
        lastHeaderRecord = null;
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
        lastHeaderRecord = sb.toString(); // GameStart.header for the M1 bridge
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
        return decPriority(g, p, null);
    }

    /** Bridged variant carries provenance; both stash the scanned options so
     *  ret() can label the chosen option index ("oi" — exact SA-level labels
     *  for future corpora; the sa-string join is ambiguous for ~31% of
     *  multi-SA hosts, measured 2026-07-10). */
    public static long decPriority(Game g, Player p, String by) {
        return decPriority(g, p, by, null);
    }

    /** preScanned lets the bridged path reuse its own option scan (the scan
     *  is the D3-measured haircut; scanning twice per window would double it). */
    public static long decPriority(Game g, Player p, String by,
            java.util.List<SpellAbility> preScanned) {
        if (!isLogging(g)) {
            return -1;
        }
        java.util.List<String> opts = null;
        java.util.List<Card> extraEnts = null;
        java.util.List<SpellAbility> options = null;
        try {
            options = preScanned != null ? preScanned : AnvilOptions.priorityOptions(g, p);
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
            options = null;
            obsErrors++;
        }
        long s = decInternal(g, p, "chooseSpellAbilityToPlay", by, opts, true, extraEnts);
        if (s >= 0) {
            prioSeq = s;
            prioOptions = options;
        }
        return s;
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
        int pIdx = p == null ? -1 : g.getRegisteredPlayers().indexOf(p);
        sb.append(",\"t\":").append(turn)
                .append(",\"ph\":").append(q(phase))
                .append(",\"p\":").append(pIdx)
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
        lastDecRecord = sb.toString();
        pendingDec.put(s, new String[]{m, String.valueOf(pIdx)});
        if (pendingDec.size() > 64) { // crash-path leak bound; normal nesting is shallow
            pendingDec.remove(pendingDec.keySet().iterator().next());
        }
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
        if (s == prioSeq && prioOptions != null && v != null) {
            sb.append(",\"oi\":").append(optionIndexOf(v));
        }
        sb.append('}');
        write(sb);
        String[] mp = pendingDec.remove(s);
        if (mp != null) {
            histRing.addLast("{\"m\":" + q(mp[0]) + ",\"p\":" + mp[1]
                    + ",\"e\":" + retHostId(v) + '}');
            if (histRing.size() > HIST_CAP) {
                histRing.removeFirst();
            }
        }
        if (s == prioSeq) {
            prioSeq = -1;
            prioOptions = null;
        }
    }

    /**
     * Index of the chosen SA in the stashed option scan, or -1. Identity
     * first; alt-cost variants are fresh copies each scan, so fall back to a
     * composite key (host + rendered string + alternative cost) and stay
     * honest on ambiguity (-1) rather than guess.
     */
    private static int optionIndexOf(Object v) {
        SpellAbility chosen = null;
        if (v instanceof SpellAbility) {
            chosen = (SpellAbility) v;
        } else if (v instanceof java.util.List && !((java.util.List<?>) v).isEmpty()
                && ((java.util.List<?>) v).get(0) instanceof SpellAbility) {
            chosen = (SpellAbility) ((java.util.List<?>) v).get(0);
        }
        if (chosen == null) {
            return -1;
        }
        for (int i = 0; i < prioOptions.size(); i++) {
            if (prioOptions.get(i) == chosen) {
                return i;
            }
        }
        int hit = -1;
        int n = 0;
        for (int i = 0; i < prioOptions.size(); i++) {
            SpellAbility o = prioOptions.get(i);
            if (o.getHostCard() == chosen.getHostCard()
                    && o.getAlternativeCost() == chosen.getAlternativeCost()
                    && String.valueOf(o).equals(String.valueOf(chosen))) {
                hit = i;
                n++;
            }
        }
        return n == 1 ? hit : -1;
    }

    /** Chosen-host id for a history entry, mirroring what the Python side
     *  reads off ret[0] (SpellAbility/Card answers; -1 otherwise). */
    private static long retHostId(Object v) {
        Object first = v;
        if (v instanceof java.util.List) {
            java.util.List<?> l = (java.util.List<?>) v;
            first = l.isEmpty() ? null : l.get(0);
        }
        if (first instanceof SpellAbility) {
            Card h = ((SpellAbility) first).getHostCard();
            return h == null ? -1 : h.getId();
        }
        if (first instanceof Card) {
            return ((Card) first).getId();
        }
        return -1;
    }

    /**
     * The dec record just written, with the last-K completed-decision history
     * spliced in — the M1 bridge observation payload. The server featurizes
     * it with the same transform as training; the information-set rule is
     * applied Python-side, where it is leak-tested. Null when not logging
     * (D8 eval games run with --obs on; the wire payload rides the same
     * serialization the corpus uses).
     */
    public static synchronized String lastDecForBridge() {
        if (lastDecRecord == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(lastDecRecord.length() + 64 * histRing.size() + 16);
        sb.append(lastDecRecord, 0, lastDecRecord.length() - 1).append(",\"hist\":[");
        int i = 0;
        for (String h : histRing) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append(h);
        }
        sb.append("]}");
        return sb.toString();
    }

    /** The game header record (GameStart.header wire field). Null when not logging. */
    public static synchronized String lastHeaderForBridge() {
        return lastHeaderRecord;
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
            StringBuilder tsb = new StringBuilder(64);
            targets(tsb, sub);
            if (tsb.length() == 0) {
                continue; // no live targets (all stale-filtered or none set)
            }
            sb.append(emitted++ == 0 ? ",\"sub\":[" : ",").append("{\"i\":").append(link)
                    .append(tsb).append('}');
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
        java.util.List<String> refs = new java.util.ArrayList<>(4);
        for (Object t : tc) {
            if (t instanceof Card) {
                Card c = (Card) t;
                // Stale-evaluation guard (D3 validation batch): a modal
                // spell's sub-chain can retain TargetChoices from an earlier
                // AI pass, pointing at an entity that no longer exists (dead
                // token). Resolution re-binds targets, so a ref to a card in
                // no zone is noise that would dangle in the label.
                if (c.getZone() == null || !c.getZone().contains(c)) {
                    continue;
                }
                refs.add("{\"e\":" + c.getId() + '}');
            } else if (t instanceof Player) {
                Player tp = (Player) t;
                refs.add("{\"pi\":" + tp.getGame().getRegisteredPlayers().indexOf(tp) + '}');
            } else if (t instanceof SpellAbility) {
                // A stack-SA target joins on its host card id; stale if that
                // spell is no longer on the stack.
                Card h = ((SpellAbility) t).getHostCard();
                if (h == null || h.getZone() == null
                        || h.getZone().getZoneType() != forge.game.zone.ZoneType.Stack
                        || !h.getZone().contains(h)) {
                    continue;
                }
                refs.add("{\"e\":" + h.getId() + ",\"stk\":1}");
            } else {
                refs.add("{\"str\":" + q(trunc(String.valueOf(t))) + '}');
            }
        }
        if (refs.isEmpty()) {
            return;
        }
        sb.append(",\"tgt\":[");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(refs.get(i));
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
            // close + account the partial frame; dropping it with no idx row
            // would leave fileOffset stale and misalign every later frame
            System.err.println("Obs: write failed for game " + gameIdx + ", closing frame: " + e);
            endGameFrame();
            return;
        }
        if (rawBytes > RAW_CAP) {
            System.err.println("Obs: game " + gameIdx + " hit raw cap ("
                    + rawBytes + " > " + RAW_CAP + " bytes), truncating frame");
            try {
                frame.write("{\"k\":\"end\",\"status\":\"obs_cap\",\"winner\":-1,\"turns\":-1,\"ms\":0}\n"
                        .getBytes(StandardCharsets.UTF_8));
                recs++;
            } catch (IOException ignored) {
            }
            endGameFrame();
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
        if (file != null) {
            try {
                // channel position is ground truth (append mode, single writer);
                // self-heals drift when a failed write landed partial bytes
                fileOffset = file.getChannel().position();
            } catch (IOException ignored) {
            }
        }
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
