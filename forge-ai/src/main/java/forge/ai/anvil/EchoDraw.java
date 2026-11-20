package forge.ai.anvil;

import forge.util.MyRandom;

import java.util.Arrays;

/**
 * The local echo answers the gRPC bridge draws from the game's seeded RNG
 * before each round trip (protocol v0's echo instrument). The draws are part
 * of the game: a served game consumes them whether or not the server's answer
 * is used. GrpcBridge and ReplayBridge both draw here, so a replay consumes
 * the stream exactly as the served game did.
 */
public final class EchoDraw {
    private EchoDraw() {
    }

    /** SELECT_ONE. The payment-class window's echo is auto (option 0), never
     *  random (M9 D3 §3c). */
    public static int selectOne(String tag, int n) {
        return n <= 1 || PlayerControllerAnvil.TAG_PAY_CLASS.equals(tag) ? 0 : MyRandom.getRandom().nextInt(n);
    }

    /** SELECT_K over [0, n): a partial Fisher-Yates sample, ascending. */
    public static int[] selectK(int n, int k) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
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

    public static boolean bool() {
        return MyRandom.getRandom().nextBoolean();
    }

    public static int intInRange(int min, int max) {
        return max <= min ? min : min + MyRandom.getRandom().nextInt(max - min + 1);
    }
}
