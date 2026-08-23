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
     * The rival cast, from res/ if it is there and from the bundled copy if it
     * is not.
     *
     * res/ is delivered by assets.zip and the update delta, which means a data
     * file introduced alongside NEW CODE can arrive later than the code that
     * needs it — or, on an install whose delta has never run, not at all. That
     * is exactly what happened to the kitchen table: every other Chronicle data
     * file was already present from the original assets.zip, so the mode booted
     * fine and just quietly had no rivals in it.
     *
     * Small curated data that ships with a feature should not depend on asset
     * delivery to exist at all. The res copy still wins when present, so the
     * file stays moddable and the curated-data-file convention holds; the
     * classpath copy rides in the jar/APK with the code, so the feature can
     * never again be missing on an install that has the code for it. A test
     * pins the two copies byte-identical so they cannot drift.
     */
    public static ChronicleRoster loadRoster() {
        ChronicleRoster roster = ChronicleRoster.parse(readDataFile(RIVALS_FILE));
        if (!roster.isEmpty()) {
            return roster;
        }
        List<String> bundled = readBundledRoster();
        if (!bundled.isEmpty()) {
            System.err.println("Chronicle: " + ForgeConstants.CHRONICLE_DATA_DIR + RIVALS_FILE
                    + " is missing or empty — falling back to the copy bundled with the code."
                    + " The asset update has not delivered it to this install.");
            return ChronicleRoster.parse(bundled);
        }
        System.err.println("Chronicle: no rivals could be loaded from res/ OR the bundled copy —"
                + " the kitchen table will be empty.");
        return roster;
    }

    /** The roster shipped inside the jar/APK, next to the code that needs it. */
    static List<String> readBundledRoster() {
        try (java.io.InputStream in = ChronicleData.class.getResourceAsStream("/chronicle/" + RIVALS_FILE)) {
            if (in == null) {
                return java.util.Collections.emptyList();
            }
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                    .lines().collect(java.util.stream.Collectors.toList());
        } catch (java.io.IOException e) {
            System.err.println("Chronicle: could not read the bundled rivals.txt — " + e);
            return java.util.Collections.emptyList();
        }
    }

    /** True when res/ has no usable roster and the bundled copy is carrying the mode. */
    public static boolean rosterCameFromBundle() {
        return ChronicleRoster.parse(readDataFile(RIVALS_FILE)).isEmpty();
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
