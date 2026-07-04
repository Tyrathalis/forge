package forge.ai.anvil;

/**
 * Decision-answering seam for PlayerControllerAnvil (Anvil bridge-protocol-v0
 * answer shapes, Java side). Implementations: LocalRandomBridge (in-process
 * random-legal, the transport-free control arm) and, next, the gRPC client.
 * Answers must be deterministic given the game seed for replay to hold.
 */
public interface AnvilBridge {
    /** SELECT_ONE: uniform pick over n options; returns index in [0, n). */
    int selectOne(String tag, int n);

    /** SELECT_K: pick k distinct indices from [0, n); returned in ascending order. */
    int[] selectK(String tag, int n, int k);

    /** BOOL. */
    boolean bool(String tag);

    /** INT_IN_RANGE: inclusive bounds. */
    int intInRange(String tag, int min, int max);
}
