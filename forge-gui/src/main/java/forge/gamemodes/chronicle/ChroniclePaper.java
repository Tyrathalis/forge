package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The daily Chronicle issue: templated headlines composed from the calendar's
 * event feed (release announcements, last-chance shelf warnings), the LGS
 * stock roll, the stipend schedule, and a seeded era-flavor snippet. Headless;
 * the paper overlay screen renders what this composes.
 */
public final class ChroniclePaper {

    /** Seed domain for the daily flavor-line pick. */
    public static final String DOMAIN_PAPER_FLAVOR = "paper-flavor";

    /** One composed issue. */
    public static final class Issue {
        public final int dayIndex;
        public final List<String> headlines;
        public final List<String> lgsNotes;
        public final String flavor;

        Issue(int dayIndex, List<String> headlines, List<String> lgsNotes, String flavor) {
            this.dayIndex = dayIndex;
            this.headlines = headlines;
            this.lgsNotes = lgsNotes;
            this.flavor = flavor;
        }
    }

    private final ChronicleCalendar calendar;
    private final ChronicleConfig config;
    private final List<String> flavorLines;

    public ChroniclePaper(ChronicleCalendar calendar, ChronicleConfig config, List<String> flavorLines) {
        this.calendar = calendar;
        this.config = config;
        this.flavorLines = new ArrayList<>();
        for (String line : flavorLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                this.flavorLines.add(trimmed);
            }
        }
    }

    /** Compose the issue for a played day. Deterministic in (runSeed, dayIndex). */
    public Issue composeFor(long runSeed, int dayIndex, List<ChronicleLgs.StockOffer> stock) {
        List<String> headlines = new ArrayList<>();
        for (ChronicleRelease release : calendar.releasingOn(dayIndex)) {
            headlines.add(release.name + " hits shelves today!");
        }
        for (ChronicleRelease release : calendar.leavingShelfWithin(dayIndex, ChronicleController.LAST_CHANCE_HORIZON_DAYS)) {
            int daysLeft = release.lastShelfDay() - dayIndex;
            if (daysLeft == 0) {
                headlines.add("Last day for " + release.name + " — gone tomorrow.");
            } else {
                headlines.add(release.name + " leaving shelves in " + daysLeft + (daysLeft == 1 ? " day." : " days."));
            }
        }
        if (dayIndex % config.stipendPeriodDays == 0) {
            headlines.add("Allowance day.");
        }

        List<String> lgsNotes = new ArrayList<>();
        for (ChronicleLgs.StockOffer offer : stock) {
            if (offer.discountPercent > 0) {
                ChronicleRelease product = calendar.byCode(offer.editionCode);
                String name = product == null ? offer.editionCode : product.name;
                lgsNotes.add(name + " " + offer.kind.toString().toLowerCase() + "s "
                        + offer.discountPercent + "% off at the store.");
            }
        }

        String flavor = "";
        if (!flavorLines.isEmpty()) {
            Random rng = ChronicleSeeds.random(ChronicleSeeds.deriveDaily(runSeed, dayIndex, DOMAIN_PAPER_FLAVOR));
            flavor = flavorLines.get(rng.nextInt(flavorLines.size()));
        }
        return new Issue(dayIndex, headlines, lgsNotes, flavor);
    }
}
