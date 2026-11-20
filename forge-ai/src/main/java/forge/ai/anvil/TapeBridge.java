package forge.ai.anvil;

import java.util.List;

/**
 * Records the answer tape (AnswerTape) around another bridge. An answer is
 * taped only while the mainline game holds the session: a search copy
 * announces its own derived id ("g12.s3r0o1") and the mainline is announced
 * again when the copy ends, so the session id says whose ask this is. The
 * value and certify asks are never taped (a replay runs no search).
 * Recording only: every call returns the delegate's answer unchanged.
 */
public final class TapeBridge implements AnvilBridge {
    private final AnvilBridge in;
    private boolean mainline;

    public TapeBridge(AnvilBridge in) {
        this.in = in;
    }

    private void tape(String line) {
        if (mainline) {
            Obs.tape(line);
        }
    }

    private static boolean isMainline(String gameId) {
        return gameId.indexOf('.') < 0;
    }

    @Override
    public boolean poisoned() {
        return in.poisoned();
    }

    @Override
    public int selectOne(String tag, List<String> optionLabels) {
        int i = in.selectOne(tag, optionLabels);
        tape("one\t" + tag + "\t" + i);
        return i;
    }

    @Override
    public int[] selectK(String tag, int n, int k) {
        int[] a = in.selectK(tag, n, k);
        tape("set\t" + tag + "\t" + AnswerTape.csv(a));
        return a;
    }

    @Override
    public int[] selectSet(String tag, List<String> optionLabels, int min, int max) {
        int[] a = in.selectSet(tag, optionLabels, min, max);
        tape("set\t" + tag + "\t" + AnswerTape.csv(a));
        return a;
    }

    @Override
    public int[] selectSet(String tag, List<String> optionLabels, int min, int max, boolean repeat) {
        int[] a = in.selectSet(tag, optionLabels, min, max, repeat);
        tape("set\t" + tag + "\t" + AnswerTape.csv(a));
        return a;
    }

    @Override
    public int[] order(String tag, List<String> optionLabels) {
        int[] a = in.order(tag, optionLabels);
        tape("set\t" + tag + "\t" + AnswerTape.csv(a));
        return a;
    }

    @Override
    public boolean bool(String tag) {
        boolean b = in.bool(tag);
        tape("bool\t" + tag + "\t" + (b ? 1 : 0));
        return b;
    }

    @Override
    public int intInRange(String tag, int min, int max) {
        int v = in.intInRange(tag, min, max);
        tape("int\t" + tag + "\t" + v);
        return v;
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation) {
        CastPlanAnswer a = in.priorityCastPlan(tag, optionLabels, observation);
        tape(AnswerTape.plan(tag, a));
        return a;
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation,
            int attempt) {
        CastPlanAnswer a = in.priorityCastPlan(tag, optionLabels, observation, attempt);
        tape(AnswerTape.plan(tag, a));
        return a;
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels, String observation,
            int attempt, boolean forbidDecline) {
        CastPlanAnswer a = in.priorityCastPlan(tag, optionLabels, observation, attempt, forbidDecline);
        tape(AnswerTape.plan(tag, a));
        return a;
    }

    @Override
    public CombatMapAnswer attackMap(String tag, String observation) {
        CombatMapAnswer a = in.attackMap(tag, observation);
        tape(AnswerTape.combat("atk", tag, a));
        return a;
    }

    @Override
    public CombatMapAnswer blockMap(String tag, String observation) {
        CombatMapAnswer a = in.blockMap(tag, observation);
        tape(AnswerTape.combat("blk", tag, a));
        return a;
    }

    @Override
    public List<int[]> certifyArms(String tag, List<String> optionLabels, String observation) {
        return in.certifyArms(tag, optionLabels, observation);
    }

    @Override
    public double value(String tag, String observation) {
        return in.value(tag, observation);
    }

    @Override
    public long asksSoFar() {
        return in.asksSoFar();
    }

    @Override
    public void gameStart(String gameId, long seed) {
        mainline = isMainline(gameId);
        in.gameStart(gameId, seed);
    }

    @Override
    public void gameStart(String gameId, long seed, String header) {
        mainline = isMainline(gameId);
        in.gameStart(gameId, seed, header);
    }

    @Override
    public void gameEnd(String gameId, String winner, int turns, long wallMs) {
        in.gameEnd(gameId, winner, turns, wallMs);
    }

    @Override
    public void close() {
        in.close();
    }
}
