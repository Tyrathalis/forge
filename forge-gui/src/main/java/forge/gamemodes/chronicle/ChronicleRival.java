package forge.gamemodes.chronicle;

/**
 * One rival collector: a kid down the street with their own collection, growing
 * along the same timeline the player walks.
 *
 * A rival is pure data — their collection is never stored, it is DERIVED from
 * (run seed, rival id, pack index) by {@link ChronicleRivalPool}, so a rival's
 * cards are as unrerollable as the player's own and cost the save nothing.
 *
 * Difficulty is fiction, not a multiplier: {@code packsPerDay} is the size of
 * this kid's allowance. A rival who buys three packs a day has a deeper
 * collection than one who buys one, and that is the whole of the difficulty
 * model — the power ceiling comes from the era, since a rival can only ever own
 * what has been released.
 */
public final class ChronicleRival {

    public final String id;
    public final String name;
    /** Played day this rival first shows up at the kitchen table. */
    public final int joinDay;
    /** Packs this rival's allowance buys per played day — the difficulty knob, as fiction. */
    public final float packsPerDay;
    /** Purse multiplier, in percent of the config base — a tougher kid plays for more. */
    public final int pursePercent;
    /** One line for the challenge screen and the paper. */
    public final String flavor;

    public ChronicleRival(String id, String name, int joinDay, float packsPerDay, int pursePercent, String flavor) {
        if (packsPerDay <= 0) {
            throw new IllegalArgumentException("Chronicle rival " + id + ": packsPerDay must be positive");
        }
        if (joinDay < 0) {
            throw new IllegalArgumentException("Chronicle rival " + id + ": joinDay must not be negative");
        }
        this.id = id;
        this.name = name;
        this.joinDay = joinDay;
        this.packsPerDay = packsPerDay;
        this.pursePercent = pursePercent;
        this.flavor = flavor;
    }

    public boolean isAroundOn(int dayIndex) {
        return dayIndex >= joinDay;
    }

    /**
     * Packs this rival has bought by the end of the given day. Cumulative and
     * monotonic: pack i is always the same pack, so the collection only ever
     * grows — the pool derivation depends on this.
     */
    public int packsOwnedBy(int dayIndex) {
        if (!isAroundOn(dayIndex)) {
            return 0;
        }
        return (int) Math.floor((dayIndex - joinDay + 1) * (double) packsPerDay);
    }

    /** The played day pack {@code index} was bought — the era bound on what can be in it. */
    public int acquisitionDay(int index) {
        return joinDay + (int) Math.floor(index / (double) packsPerDay);
    }

    @Override
    public String toString() {
        return "ChronicleRival[" + id + "]";
    }
}
