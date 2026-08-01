package forge.gamemodes.chronicle;

/**
 * One sealed product in the player's possession. The contents seed is
 * committed at acquisition (seed-integrity invariant): opening reveals what
 * was already determined — quit-without-saving can never reroll a pull.
 *
 * Boxes are materialized at purchase as their component boosters, each with
 * its own committed seed, so BOOSTER and STARTER are the only sealed kinds
 * that exist in inventory. (Stage 2's out-of-print sealed-box market will
 * want box-as-item; the acquisition-time commitment property it needs is
 * already what this class implements.)
 */
public final class SealedItem {

    public enum Kind {
        BOOSTER, STARTER
    }

    public final long itemId;
    public final Kind kind;
    public final String editionCode;
    public final long contentsSeed;
    public final int acquiredDay;

    public SealedItem(long itemId, Kind kind, String editionCode, long contentsSeed, int acquiredDay) {
        this.itemId = itemId;
        this.kind = kind;
        this.editionCode = editionCode;
        this.contentsSeed = contentsSeed;
        this.acquiredDay = acquiredDay;
    }

    @Override
    public String toString() {
        return kind + " " + editionCode + " #" + itemId;
    }
}
