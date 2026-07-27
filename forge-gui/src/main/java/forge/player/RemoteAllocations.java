package forge.player;

import java.util.Collection;

/**
 * Bounds checking for amount allocations that come back from a remote answer.
 *
 * <p>{@code PlayerControllerHuman} asks the GUI to split a fixed budget —
 * combat damage among blockers, counters among targets, shields, mana — and in
 * a network game that GUI is a {@code RemoteClientGuiGame} whose reply arrives
 * over the wire. The reply is a <i>proposal from an untrusted peer</i>, not an
 * instruction: a modified client can put any integers it likes in the map, and
 * the host is the authority that has to say no.
 *
 * <p>The check is deliberately narrow. It enforces the arithmetic invariant
 * the host can state without re-deriving the rules — every share present and
 * non-negative, and no more handed out than exists to hand out. It does not
 * try to enforce lethal-damage ordering or similar, which depend on
 * deathtouch, protection and the rest, and which the engine adjudicates
 * elsewhere. Under-allocating is left alone: it only ever costs the allocating
 * player, so it is a bad play rather than an exploit.
 */
final class RemoteAllocations {

    /** Returned instead of a total when the proposal is not a legal allocation. */
    static final int ILLEGAL = -1;

    private RemoteAllocations() {
    }

    /**
     * Total of a proposed allocation, or {@link #ILLEGAL} if it is not one.
     *
     * <p>Rejects a null share (a client that simply omits a key), a negative
     * share, and any total exceeding {@code budget}. {@code addDividedAllocation}
     * takes an {@code Integer}, so a missing share is stored rather than
     * throwing at the call site — it corrupts the allocation and surfaces
     * later, which is worse than a clean rejection here.
     *
     * <p>The budget is tested inside the loop, not once at the end, so with
     * non-negative shares the running total is monotonic and trips the budget
     * long before it could overflow. The {@code long} accumulator is
     * belt-and-braces for the day someone hoists that test out of the loop —
     * at which point summing attacker-supplied {@code int}s could wrap to a
     * negative total and sail straight past a {@code total > budget} check.
     */
    static int totalIfLegal(final Collection<Integer> shares, final int budget) {
        if (shares == null) {
            return ILLEGAL;
        }
        long total = 0;
        for (final Integer share : shares) {
            if (share == null || share < 0) {
                return ILLEGAL;
            }
            total += share;
            if (total > budget) {
                return ILLEGAL;
            }
        }
        return (int) total;
    }

    /** Whether a proposal allocates exactly {@code budget}, no more and no less. */
    static boolean allocatesExactly(final Collection<Integer> shares, final int budget) {
        return totalIfLegal(shares, budget) == budget;
    }
}
