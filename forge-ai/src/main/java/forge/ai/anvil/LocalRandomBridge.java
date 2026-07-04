package forge.ai.anvil;

import forge.util.MyRandom;

import java.util.Arrays;

/**
 * In-process random-legal bridge: answers every decision uniformly at random
 * from the game's seeded RNG (MyRandom), so runs stay deterministic per seed.
 * This is the transport-free control arm for the bridge-tax measurement — the
 * gRPC arm must answer identically (same RNG consumption) for the comparison
 * to isolate transport cost.
 */
public final class LocalRandomBridge implements AnvilBridge {
    @Override
    public int selectOne(String tag, java.util.List<String> optionLabels) {
        int n = optionLabels.size();
        return n <= 1 ? 0 : MyRandom.getRandom().nextInt(n);
    }

    @Override
    public int[] selectK(String tag, int n, int k) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        // Partial Fisher-Yates: first k slots become the sample.
        for (int i = 0; i < k && i < n; i++) {
            int j = i + MyRandom.getRandom().nextInt(n - i);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        int[] pick = Arrays.copyOf(all, Math.min(k, n));
        Arrays.sort(pick);
        return pick;
    }

    @Override
    public boolean bool(String tag) {
        return MyRandom.getRandom().nextBoolean();
    }

    @Override
    public int intInRange(String tag, int min, int max) {
        return max <= min ? min : min + MyRandom.getRandom().nextInt(max - min + 1);
    }
}
