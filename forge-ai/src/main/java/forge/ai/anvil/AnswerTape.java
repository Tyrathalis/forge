package forge.ai.anvil;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The answer tape (Anvil M13 Build 1, forced-decision replay): every answer a
 * mainline game took from outside the engine, in the order it took them. A
 * game replays from its seed and its tape with no decision server.
 *
 * Two things enter a tape: each bridge answer given while the mainline game
 * holds the bridge session (TapeBridge), and each search verdict that changed
 * the mainline (a forced option, a forced pass, a surface answer armed for
 * the seat). A search's copies are not taped. A replay runs under the
 * recorded run's search flags: the search monitor takes every step it takes
 * on the mainline game (the state-based check at the window, the allocation
 * ask, the option scan), plays no copy, and the seat applies the taped
 * verdict.
 *
 * One entry is one line of tab-separated fields, the first naming its kind:
 * <pre>
 *   one  tag i                      SELECT_ONE
 *   set  tag csv|-                  selectK / selectSet / order ("-" = null)
 *   bool tag 0|1
 *   int  tag v
 *   val  tag v                      the value ask on the mainline session (the allocation head)
 *   plan tag -                      priorityCastPlan answered null
 *   plan tag opt host hasX x refs   refs: p3 (player) e120 (entity) s45 (stack), comma-joined, "" = none
 *   atk  tag -|pairs                pairs: left>right joined by ";" ("" = the empty map)
 *   blk  tag -|pairs
 *   srch seat mode label arm armLabel   mode nat|pass|act; arm "-" or kind,ordinal,answer...
 * </pre>
 * Free text (labels) is escaped: backslash, tab, newline and carriage return.
 * Recording writes each line into the game's observation frame as
 * {"k":"ans","a":"line"} (Obs.tape); a replay file is a "G idx seed tags"
 * line per game followed by that game's lines, verbatim.
 */
public final class AnswerTape {
    private AnswerTape() {
    }

    /** Set once per worker JVM (AnvilRun -tape): search verdicts are taped. */
    public static volatile boolean recording = false;

    public static String esc(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': b.append("\\\\"); break;
                case '\t': b.append("\\t"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    public static String unesc(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 == s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(++i);
            b.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
        }
        return b.toString();
    }

    static String csv(int[] a) {
        if (a == null) {
            return "-";
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(a[i]);
        }
        return b.toString();
    }

    static int[] ints(String s) {
        if (s.equals("-")) {
            return null;
        }
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] p = s.split(",");
        int[] a = new int[p.length];
        for (int i = 0; i < p.length; i++) {
            a[i] = Integer.parseInt(p[i]);
        }
        return a;
    }

    static String ref(CastPlanAnswer.Ref r) {
        return r.isPlayer ? "p" + r.player : (r.onStack ? "s" : "e") + r.entity;
    }

    static CastPlanAnswer.Ref ref(String s) {
        int n = Integer.parseInt(s.substring(1));
        return s.charAt(0) == 'p' ? new CastPlanAnswer.Ref(true, n, -1, false)
                : new CastPlanAnswer.Ref(false, -1, n, s.charAt(0) == 's');
    }

    static String plan(String tag, CastPlanAnswer a) {
        if (a == null) {
            return "plan\t" + tag + "\t-";
        }
        StringBuilder b = new StringBuilder("plan\t").append(tag).append('\t').append(a.optionIndex)
                .append('\t').append(a.hostLevel ? 1 : 0).append('\t').append(a.hasX ? 1 : 0)
                .append('\t').append(a.x).append('\t');
        for (int i = 0; i < a.targets.size(); i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(ref(a.targets.get(i)));
        }
        return b.toString();
    }

    static CastPlanAnswer plan(String[] f) {
        if (f[2].equals("-")) {
            return null;
        }
        List<CastPlanAnswer.Ref> refs = new ArrayList<>();
        if (f.length > 6 && !f[6].isEmpty()) {
            for (String r : f[6].split(",")) {
                refs.add(ref(r));
            }
        }
        return new CastPlanAnswer(Integer.parseInt(f[2]), f[3].equals("1"), refs, f[4].equals("1"),
                Integer.parseInt(f[5]));
    }

    static String combat(String kind, String tag, CombatMapAnswer a) {
        if (a == null) {
            return kind + "\t" + tag + "\t-";
        }
        StringBuilder b = new StringBuilder(kind).append('\t').append(tag).append('\t');
        for (int i = 0; i < a.assignments.size(); i++) {
            if (i > 0) {
                b.append(';');
            }
            b.append(ref(a.assignments.get(i).left)).append('>').append(ref(a.assignments.get(i).right));
        }
        return b.toString();
    }

    static CombatMapAnswer combat(String[] f) {
        String s = f.length > 2 ? f[2] : "";
        if (s.equals("-")) {
            return null;
        }
        List<CombatMapAnswer.Assignment> out = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String pair : s.split(";")) {
                int gt = pair.indexOf('>');
                out.add(new CombatMapAnswer.Assignment(ref(pair.substring(0, gt)), ref(pair.substring(gt + 1))));
            }
        }
        return new CombatMapAnswer(out);
    }

    /** A search verdict that changed the mainline at one priority ask. */
    public static final class Search {
        public static final String NAT = "nat", PASS = "pass", ACT = "act";
        public final int seat;
        public final String mode;
        /** The forced option's label (ACT); "" otherwise. */
        public final String label;
        /** The armed surface answer, or armKind < 0 = none. */
        public final int armKind;
        public final int armOrdinal;
        public final int[] armAnswer;
        public final String armLabel;

        public Search(int seat, String mode, String label, int armKind, int armOrdinal, int[] armAnswer,
                String armLabel) {
            this.seat = seat;
            this.mode = mode;
            this.label = label == null ? "" : label;
            this.armKind = armKind;
            this.armOrdinal = armOrdinal;
            this.armAnswer = armAnswer;
            this.armLabel = armLabel;
        }

        String line() {
            StringBuilder b = new StringBuilder("srch\t").append(seat).append('\t').append(mode).append('\t')
                    .append(esc(label)).append('\t');
            if (armKind < 0) {
                return b.append("-\t").toString();
            }
            b.append(armKind).append(',').append(armOrdinal);
            for (int v : armAnswer) {
                b.append(',').append(v);
            }
            // a null guard label and an empty one differ (SurfaceDirective.label)
            return b.append('\t').append(armLabel == null ? "-" : "=" + esc(armLabel)).toString();
        }

        static Search parse(String[] f) {
            int kind = -1, ord = 0;
            int[] ans = null;
            String al = null;
            if (!f[4].equals("-")) {
                int[] a = ints(f[4]);
                kind = a[0];
                ord = a[1];
                ans = java.util.Arrays.copyOfRange(a, 2, a.length);
                al = f.length > 5 && f[5].startsWith("=") ? unesc(f[5].substring(1)) : null;
            }
            return new Search(Integer.parseInt(f[1]), f[2], unesc(f[3]), kind, ord, ans, al);
        }
    }

    /** Tape a search verdict (the mainline game's thread; no-op when not recording). */
    public static void record(Search s) {
        if (recording) {
            Obs.tape(s.line());
        }
    }

    /** One game of a replay file. */
    public static final class Game {
        public final int idx;
        public final long seed;
        public final Set<String> tags;
        public final List<String> lines = new ArrayList<>();

        Game(int idx, long seed, Set<String> tags) {
            this.idx = idx;
            this.seed = seed;
            this.tags = tags;
        }
    }

    /** Read a replay file: games in file order, keyed by game index. */
    public static Map<Integer, Game> read(String path) throws IOException {
        Map<Integer, Game> games = new LinkedHashMap<>();
        Game cur = null;
        try (BufferedReader r = Files.newBufferedReader(Paths.get(path), StandardCharsets.UTF_8)) {
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("G\t")) {
                    String[] f = line.split("\t", -1);
                    Set<String> tags = new LinkedHashSet<>();
                    if (f.length > 3 && !f[3].isEmpty()) {
                        Collections.addAll(tags, f[3].split(","));
                    }
                    cur = new Game(Integer.parseInt(f[1]), Long.parseLong(f[2]), tags);
                    if (games.put(cur.idx, cur) != null) {
                        throw new IOException("tape file names game " + cur.idx + " twice");
                    }
                } else if (cur == null) {
                    throw new IOException("tape line before any game line: " + line);
                } else {
                    cur.lines.add(line);
                }
            }
        }
        return games;
    }
}
