package forge.ai.anvil;

import java.util.Collections;
import java.util.List;

import forge.util.MyRandom;

/**
 * In-process one-shot variant of the random-legal bridge (M10 smoke rig):
 * answers the composite CastPlan shape with a uniformly random label index
 * and an EMPTY plan — the realizer AI-fits targets/X — so directive genres
 * that require the one-shot path (-forceschedule's masked forced asks) can
 * be exercised end-to-end without a decision server. Deterministic per game
 * seed (MyRandom, like LocalRandomBridge). Smoke/mechanics only; never a
 * corpus or measurement arm.
 */
public final class LocalOneShotBridge implements AnvilBridge {
    private final LocalRandomBridge base = new LocalRandomBridge();

    @Override
    public int selectOne(String tag, List<String> optionLabels) {
        return base.selectOne(tag, optionLabels);
    }

    @Override
    public int[] selectK(String tag, int n, int k) {
        return base.selectK(tag, n, k);
    }

    @Override
    public boolean bool(String tag) {
        return base.bool(tag);
    }

    @Override
    public int intInRange(String tag, int min, int max) {
        return base.intInRange(tag, min, max);
    }

    @Override
    public CastPlanAnswer priorityCastPlan(String tag, List<String> optionLabels,
            String observation, int attempt, boolean forbidDecline) {
        final int n = optionLabels.size(); // index 0 = pass
        final int idx;
        if (n <= 1) {
            idx = 0;
        } else if (forbidDecline) {
            idx = 1 + MyRandom.getRandom().nextInt(n - 1);
        } else {
            idx = MyRandom.getRandom().nextInt(n);
        }
        return new CastPlanAnswer(idx, false, Collections.emptyList(), false, 0);
    }
}
