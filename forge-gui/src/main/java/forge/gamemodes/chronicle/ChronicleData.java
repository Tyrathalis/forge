package forge.gamemodes.chronicle;

import java.io.File;
import java.time.Clock;
import java.util.List;

import forge.localinstance.properties.ForgeConstants;
import forge.util.FileUtil;

/**
 * Loads Chronicle's curated data files from res/chronicle/ and assembles the
 * production controller. Tests bypass this and feed the parsers lines
 * directly (FileUtil.readFile silently returns empty for missing files; the
 * parsers throw loudly on empty required data, which is the failure we want
 * at boot rather than mid-run).
 */
public final class ChronicleData {

    public static final String RELEASES_FILE = "releases.txt";
    public static final String ECONOMY_FILE = "economy.txt";
    public static final String NOTABLES_FILE = "notables.txt";
    public static final String PAPER_FLAVOR_FILE = "paper-flavor.txt";
    public static final String RIVALS_FILE = "rivals.txt";

    private ChronicleData() {
    }

    public static ChronicleCalendar loadCalendar() {
        return ChronicleCalendar.parse(readDataFile(RELEASES_FILE));
    }

    public static ChronicleConfig loadConfig() {
        return ChronicleConfig.parse(readDataFile(ECONOMY_FILE));
    }

    public static ChroniclePricing loadPricing(ChronicleConfig config) {
        return new ChroniclePricing(config.buylistBaseCents,
                ChroniclePricing.parseNotables(readDataFile(NOTABLES_FILE)));
    }

    /**
     * The rival cast. Unlike the calendar, a missing roster does not throw: it
     * would lock an existing player out of the whole mode over one data file
     * that arrives by asset delta. But it must never be SILENT either — an
     * empty roster is indistinguishable from "nobody has moved in yet", which
     * is exactly how a failed asset update hid the kitchen table for a whole
     * release. It is logged here and reported on the screen that needs it.
     */
    public static ChronicleRoster loadRoster() {
        ChronicleRoster roster = ChronicleRoster.parse(readDataFile(RIVALS_FILE));
        if (roster.isEmpty()) {
            System.err.println("Chronicle: no rivals loaded from " + ForgeConstants.CHRONICLE_DATA_DIR
                    + RIVALS_FILE + " — the kitchen table will be empty. If this is an updated install,"
                    + " the asset delta has not delivered the file yet.");
        }
        return roster;
    }

    /** True when the roster data file is missing or empty — the kitchen table says so rather than showing nothing. */
    public static boolean rosterFileMissing() {
        return readDataFile(RIVALS_FILE).isEmpty();
    }

    public static ChroniclePaper loadPaper(ChronicleCalendar calendar, ChronicleConfig config) {
        return new ChroniclePaper(calendar, config, readDataFile(PAPER_FLAVOR_FILE));
    }

    /** Production controller: res-loaded data, card-DB resolver, system clock, autosave into the user save dir. */
    public static ChronicleController createController() {
        FileUtil.ensureDirectoryExists(ForgeConstants.CHRONICLE_SAVE_DIR);
        ChronicleConfig config = loadConfig();
        ChronicleController controller = new ChronicleController(loadCalendar(), config, loadPricing(config),
                loadRoster(), ChronicleController.cardDbResolver(), Clock.systemDefaultZone());
        controller.setAutosaveFile(new File(ForgeConstants.CHRONICLE_SAVE_DIR, "autosave.sav"));
        return controller;
    }

    private static List<String> readDataFile(String name) {
        return FileUtil.readFile(ForgeConstants.CHRONICLE_DATA_DIR + name);
    }

    private static final java.util.Map<String, List<forge.item.PaperCard>> setUniverses = new java.util.HashMap<>();

    /**
     * All obtainable printings of a set in collector-number order, resolved
     * once and cached — the binder page spine and the completion denominator.
     * Art/rarity variants (ATQ's split-rarity Urza lands, ARN's dagger
     * commons) are distinct printings and distinct entries.
     */
    public static synchronized List<forge.item.PaperCard> setUniverse(String editionCode) {
        return setUniverses.computeIfAbsent(editionCode, code -> {
            forge.card.CardEdition edition = forge.StaticData.instance().getEditions().get(code);
            if (edition == null) {
                return new java.util.ArrayList<>();
            }
            List<forge.card.CardEdition.EditionEntry> entries = new java.util.ArrayList<>(edition.getObtainableCards());
            entries.sort((a, b) -> forge.card.CardEdition.getSortableCollectorNumber(a.collectorNumber())
                    .compareTo(forge.card.CardEdition.getSortableCollectorNumber(b.collectorNumber())));
            java.util.Set<forge.item.PaperCard> resolved = new java.util.LinkedHashSet<>();
            for (forge.card.CardEdition.EditionEntry entry : entries) {
                forge.item.PaperCard card = forge.StaticData.instance().getCommonCards()
                        .getCard(entry.name(), code, entry.collectorNumber());
                if (card != null) {
                    resolved.add(card);
                }
            }
            return new java.util.ArrayList<>(resolved);
        });
    }
}
