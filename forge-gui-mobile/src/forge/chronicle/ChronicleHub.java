package forge.chronicle;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import forge.ImageKeys;
import forge.gui.FThreads;
import forge.gui.GuiBase;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleData;
import forge.gamemodes.chronicle.ChroniclePaper;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.LoadingOverlay;
import forge.util.FileUtil;

/**
 * Chronicle's mobile-side singleton: owns the loaded controller and paper,
 * mirroring Adventure's own-singleton shape (deliberately NOT an FModel
 * field). Data/save loading runs off the GL thread; screens call
 * ensureLoaded and refresh in the callback.
 */
public final class ChronicleHub {

    private static ChronicleController controller;
    private static ChroniclePaper paper;

    private ChronicleHub() {
    }

    public static boolean isLoaded() {
        return controller != null;
    }

    public static ChronicleController controller() {
        return controller;
    }

    public static ChroniclePaper paper() {
        return paper;
    }

    public static boolean hasRun() {
        return controller != null && controller.getRun() != null;
    }

    /** Load data files + autosave off the GL thread, then run onReady on the EDT. No-op if already loaded. */
    public static void ensureLoaded(Runnable onReady) {
        if (controller != null) {
            onReady.run();
            return;
        }
        LoadingOverlay.runBackgroundTask("Opening the Chronicle...", () -> {
            ChronicleController loaded = ChronicleData.createController();
            ChroniclePaper loadedPaper = ChronicleData.loadPaper(loaded.getCalendar(), loaded.getConfig());
            File autosave = new File(ForgeConstants.CHRONICLE_SAVE_DIR, "autosave.sav");
            if (autosave.exists()) {
                loaded.loadFrom(autosave);
            }
            FThreads.invokeInEdtLater(() -> {
                controller = loaded;
                paper = loadedPaper;
                onReady.run();
            });
        });
    }

    /** Begin a fresh run. Caller is off the GL thread (LoadingOverlay). */
    public static void startNewRun() {
        controller.newRun(new Random().nextLong());
        controller.saveTo(new File(ForgeConstants.CHRONICLE_SAVE_DIR, "autosave.sav"));
    }

    /** Binder page spine: delegates to the headless (desktop-tree-tested) resolver. */
    public static List<PaperCard> setUniverse(String editionCode) {
        return ChronicleData.setUniverse(editionCode);
    }

    // --- product art ---------------------------------------------------------

    private static final Map<String, String> productArtKeys = new HashMap<>();

    /**
     * Resolve a set's booster art to an image key the fetcher can actually
     * download: the booster-images list carries full filenames (LEA.jpg,
     * 10E_1.jpg, ...) and the fetcher saves under that exact name, so a bare
     * "b:LEA" key never matches a cached file. First list match wins — a
     * stable period identity per set. Falls back to the bare key (placeholder
     * renders) when a set has no listed art. Also kicks the download; the
     * fetcher no-ops when the file is already local. CachedCardImage can't do
     * this: imageKeyFileExists() answers true for every product prefix, so
     * its fetch() never fires for booster art (upstream fix candidate).
     */
    public static String boosterArtKey(String editionCode) {
        return productArtKey(ImageKeys.BOOSTER_PREFIX, ForgeConstants.IMAGE_LIST_QUEST_BOOSTERS_FILE, editionCode, null);
    }

    /** Starter decks show the tournament-pack product shot when one is listed (core sets), else booster art. */
    public static String starterArtKey(String editionCode) {
        return productArtKey(ImageKeys.TOURNAMENTPACK_PREFIX, ForgeConstants.IMAGE_LIST_QUEST_TOURNAMENTPACKS_FILE,
                editionCode, () -> boosterArtKey(editionCode));
    }

    private static String productArtKey(String prefix, String listFile, String editionCode,
                                        java.util.function.Supplier<String> fallback) {
        String key = productArtKeys.computeIfAbsent(prefix + editionCode, k -> {
            for (String line : FileUtil.readFile(listFile)) {
                String filename = line.substring(line.lastIndexOf('/') + 1).trim();
                if (filename.startsWith(editionCode + ".") || filename.startsWith(editionCode + "_")) {
                    return prefix + filename;
                }
            }
            return ""; //no listed art for this set
        });
        if (key.isEmpty()) {
            return fallback != null ? fallback.get() : prefix + editionCode;
        }
        //fetchImage asserts the EDT; callers include background staging threads
        FThreads.invokeInEdtNowOrLater(() -> GuiBase.getInterface().getImageFetcher().fetchImage(key, () -> {
        }));
        return key;
    }
}
