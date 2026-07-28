package forge.player;

import java.util.Collection;

/**
 * Bounds checking for amount allocations that come back from a remote answer.
 * In a network game the GUI being asked to split a budget is a proxy for a
 * peer, so its reply is a proposal rather than an instruction.
 *
 * <p>Deliberately narrow: the arithmetic invariant the host can state without
 * re-deriving the rules. Lethal-damage ordering and the like depend on
 * deathtouch and protection, and are adjudicated by the engine.
 * Under-allocating is left alone, since it only costs the allocating player.
 */
final class RemoteAllocations {

    private RemoteAllocations() {
    }

    /** Whether a proposal hands out no more than {@code budget}. */
    static boolean allocatesAtMost(final Collection<Integer> shares, final int budget) {
        return total(shares, budget) >= 0;
    }

    /** Whether a proposal allocates exactly {@code budget}, no more and no less. */
    static boolean allocatesExactly(final Collection<Integer> shares, final int budget) {
        return total(shares, budget) == budget;
    }

    /**
     * Sum of the shares, or -1 if this is not a legal allocation: a null
     * collection, a missing or negative share, or a total over budget. The
     * {@code long} guards a refactor that hoists the budget test out of the
     * loop, where summing peer-supplied ints could wrap past a {@code >} check.
     */
    private static long total(final Collection<Integer> shares, final int budget) {
        if (shares == null) {
            return -1;
        }
        long total = 0;
        for (final Integer share : shares) {
            if (share == null || share < 0) {
                return -1;
            }
            total += share;
            if (total > budget) {
                return -1;
            }
        }
        return total;
    }
}
