package forge.gamemodes.chronicle;

/**
 * One row of the Chronicle release calendar: a product release with its
 * normalized shelf window and period pricing. Immutable data, parsed from
 * res/chronicle/releases.txt.
 *
 * Pacing is normalized (~one release per played week), not historical; shelf
 * windows keep historical CHARACTER as tuned exceptions (Alpha/Beta short and
 * scarce, Fallen Empires long and glutted). Prices are period dollars in
 * integer cents.
 */
public final class ChronicleRelease {
    /** Product kind on the release calendar. Riders/streams are unused in the MVP window but the field exists. */
    public enum Kind {
        PRIMARY, RIDER, STREAM
    }

    public final int orderIndex;
    public final String editionCode;
    public final String name;
    public final Kind kind;
    public final int releaseDay;
    public final int shelfDays;
    public final int boosterCents;
    /** 0 = no starter product for this set (expansions historically had none). */
    public final int starterCents;
    public final int boxCents;
    public final int packsPerBox;
    public final boolean rationEligible;

    public ChronicleRelease(int orderIndex, String editionCode, String name, Kind kind,
                            int releaseDay, int shelfDays, int boosterCents, int starterCents,
                            int boxCents, int packsPerBox, boolean rationEligible) {
        this.orderIndex = orderIndex;
        this.editionCode = editionCode;
        this.name = name;
        this.kind = kind;
        this.releaseDay = releaseDay;
        this.shelfDays = shelfDays;
        this.boosterCents = boosterCents;
        this.starterCents = starterCents;
        this.boxCents = boxCents;
        this.packsPerBox = packsPerBox;
        this.rationEligible = rationEligible;
    }

    public boolean hasStarter() {
        return starterCents > 0;
    }

    /** In print on the given played day: releaseDay <= day < releaseDay + shelfDays. */
    public boolean inPrintOn(int dayIndex) {
        return dayIndex >= releaseDay && dayIndex < releaseDay + shelfDays;
    }

    /** Last played day this product is on the shelf. */
    public int lastShelfDay() {
        return releaseDay + shelfDays - 1;
    }

    @Override
    public String toString() {
        return editionCode + " (day " + releaseDay + "+" + shelfDays + ")";
    }
}
