package forge.ai.anvil;

import java.util.List;

/**
 * Decision-answering seam for PlayerControllerAnvil (Anvil bridge-protocol-v0
 * answer shapes, Java side). Implementations: LocalRandomBridge (in-process
 * random-legal, the transport-free control arm) and GrpcBridge (forge-gui-desktop,
 * talks to the Python decision server). Answers must be deterministic given the
 * game seed for replay to hold.
 *
 * Option labels are built by the controller for BOTH arms (equal cost outside
 * the measured transport delta); the local arm ignores them, the gRPC arm
 * serializes them so M0 payloads carry realistic option lists.
 */
public interface AnvilBridge {
    /** SELECT_ONE: pick over options (index into the list). */
    int selectOne(String tag, List<String> optionLabels);

    /** SELECT_K: pick k distinct indices from [0, n); returned ascending. */
    int[] selectK(String tag, int n, int k);

    /** BOOL. */
    boolean bool(String tag);

    /** INT_IN_RANGE: inclusive bounds. */
    int intInRange(String tag, int min, int max);

    /** Lifecycle notifications (no-ops for the local arm). */
    default void gameStart(String gameId, long seed) {
    }

    default void gameEnd(String gameId, String winner, int turns, long wallMs) {
    }

    default void close() {
    }
}
