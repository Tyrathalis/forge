package forge.ai.anvil;

import java.util.List;
import java.util.Map;

/**
 * Forced-decision replay (Anvil M13 Build 1): answers every ask from a
 * recorded answer tape (AnswerTape) in place of a decision server. The tape
 * is a gRPC-served game's, so each answer first makes the echo draw the gRPC
 * bridge made (EchoDraw), which keeps the game's RNG stream where the served
 * game had it.
 *
 * A replay that asks something the tape does not hold next has diverged from
 * the recorded game: the ask throws TapeDivergedException, which ends that
 * game as a crash and leaves the bridge usable for the next one.
 */
public final class ReplayBridge implements AnvilBridge {
    public static final class TapeDivergedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TapeDivergedException(String msg) {
            super(msg);
        }
    }

    private final Map<Integer, AnswerTape.Game> games;
    private List<String> lines;
    private int pos;
    private String gameId = "";
    private String diverged;

    public ReplayBridge(Map<Integer, AnswerTape.Game> games) {
        this.games = games;
    }

    @Override
    public void gameStart(String id, long seed) {
        gameId = id;
        AnswerTape.Game g = games.get(Integer.parseInt(id.substring(1)));
        if (g == null || g.seed != seed) {
            throw new IllegalStateException("no tape for game " + id + " seed " + seed);
        }
        lines = g.lines;
        pos = 0;
        diverged = null;
    }

    /** Entries the current game has consumed, and its tape's length. */
    public int used() {
        return pos;
    }

    public int length() {
        return lines == null ? 0 : lines.size();
    }

    /** The first divergence of the current game, or null. */
    public String diverged() {
        return diverged;
    }

    private TapeDivergedException diverge(String want) {
        String have = lines == null || pos >= lines.size() ? "the tape's end" : lines.get(pos);
        if (diverged == null) {
            diverged = "at entry " + pos + ": asked " + want + ", tape holds " + have;
        }
        return new TapeDivergedException(gameId + " " + diverged);
    }

    private String[] next(String kind, String tag) {
        if (lines == null || pos >= lines.size()) {
            throw diverge(kind + " " + tag);
        }
        String[] f = lines.get(pos).split("\t", -1);
        if (!f[0].equals(kind) || !f[1].equals(tag)) {
            throw diverge(kind + " " + tag);
        }
        pos++;
        return f;
    }

    /** The search verdict taped at this point for this seat, consumed; null
     *  when the tape's next entry is not one. */
    public AnswerTape.Search takeSearch(int seat) {
        if (lines == null || pos >= lines.size() || !lines.get(pos).startsWith("srch\t")) {
            return null;
        }
        AnswerTape.Search s = AnswerTape.Search.parse(lines.get(pos).split("\t", -1));
        if (s.seat != seat) {
            throw diverge("a search verdict for seat " + seat);
        }
        pos++;
        return s;
    }

    @Override
    public int selectOne(String tag, List<String> optionLabels) {
        EchoDraw.selectOne(tag, optionLabels.size());
        return Integer.parseInt(next("one", tag)[2]);
    }

    @Override
    public int[] selectK(String tag, int n, int k) {
        EchoDraw.selectK(n, k);
        return AnswerTape.ints(next("set", tag)[2]);
    }

    @Override
    public int[] selectSet(String tag, List<String> optionLabels, int min, int max) {
        return AnswerTape.ints(next("set", tag)[2]);
    }

    @Override
    public int[] selectSet(String tag, List<String> optionLabels, int min, int max, boolean repeat) {
        return AnswerTape.ints(next("set", tag)[2]);
    }

    @Override
    public int[] order(String tag, List<String> optionLabels) {
        return AnswerTape.ints(next("set", tag)[2]);
    }

    @Override
    public boolean bool(String tag) {
        EchoDraw.bool();
        return next("bool", tag)[2].equals("1");
    }

    @Override
    public int intInRange(String tag, int min, int max) {
        EchoDraw.intInRange(min, max);
        return Integer.parseInt(next("int", tag)[2]);
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation) {
        return AnswerTape.plan(next("plan", tag));
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation,
            int attempt) {
        return AnswerTape.plan(next("plan", tag));
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation,
            int attempt, boolean forbidDecline) {
        return AnswerTape.plan(next("plan", tag));
    }

    @Override
    public CombatMapAnswer attackMap(String tag, String observation) {
        return AnswerTape.combat(next("atk", tag));
    }

    @Override
    public CombatMapAnswer blockMap(String tag, String observation) {
        return AnswerTape.combat(next("blk", tag));
    }
}
