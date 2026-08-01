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

    /** Production controller: res-loaded data, card-DB resolver, system clock, autosave into the user save dir. */
    public static ChronicleController createController() {
        FileUtil.ensureDirectoryExists(ForgeConstants.CHRONICLE_SAVE_DIR);
        ChronicleConfig config = loadConfig();
        ChronicleController controller = new ChronicleController(loadCalendar(), config, loadPricing(config),
                ChronicleController.cardDbResolver(), Clock.systemDefaultZone());
        controller.setAutosaveFile(new File(ForgeConstants.CHRONICLE_SAVE_DIR, "autosave.sav"));
        return controller;
    }

    private static List<String> readDataFile(String name) {
        return FileUtil.readFile(ForgeConstants.CHRONICLE_DATA_DIR + name);
    }
}
