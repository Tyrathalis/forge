package forge.chronicle;

import java.io.File;
import java.util.List;
import java.util.Random;

import forge.gui.FThreads;
import forge.gamemodes.chronicle.ChronicleController;
import forge.gamemodes.chronicle.ChronicleData;
import forge.gamemodes.chronicle.ChroniclePaper;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.screens.LoadingOverlay;

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
}
