package forge.gamemodes.chronicle;

import java.util.UUID;

import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * One Chronicle run: the aggregate save root. The collection layer carries a
 * run-id; meta progress lives in a SEPARATE blob the run never mixes into
 * collection state — prestige ("collection resets, meta persists") is a
 * schema property, bought now while it's free.
 */
public final class ChronicleRun {

    public final String runId;
    public final long runSeed;
    public final ChronicleTimeline timeline = new ChronicleTimeline();
    public final ChronicleWallet wallet = new ChronicleWallet();
    public final ChronicleCollection collection = new ChronicleCollection();
    public final ChronicleSealedInventory sealed = new ChronicleSealedInventory();
    public final ChronicleLgs lgs = new ChronicleLgs();
    public final ChronicleAcquisitionLog acquisitions = new ChronicleAcquisitionLog();
    public final ChronicleDecks decks = new ChronicleDecks();
    public final ChronicleKitchen kitchen = new ChronicleKitchen();
    /** Meta-progress blob: survives prestige. Opaque key-value; owners read their own keys defensively. */
    public final ChronicleSaveData meta = new ChronicleSaveData();

    public ChronicleRun(String runId, long runSeed) {
        this.runId = runId;
        this.runSeed = runSeed;
    }

    public static ChronicleRun newRun(long runSeed) {
        return new ChronicleRun(UUID.randomUUID().toString(), runSeed);
    }

    /** Header block for the slot browser: identity + at-a-glance progress, no collection payload. */
    public ChronicleSaveData buildHeader() {
        ChronicleSaveData header = new ChronicleSaveData();
        header.store("runId", runId);
        header.store("dayIndex", timeline.getDayIndex());
        header.store("cents", wallet.getCents());
        header.store("saveEpochMillis", System.currentTimeMillis());
        return header;
    }

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("runId", runId);
        data.store("runSeed", runSeed);
        data.store("timeline", timeline.save());
        data.store("wallet", wallet.save());
        data.store("collection", collection.save());
        data.store("sealed", sealed.save());
        data.store("lgs", lgs.save());
        data.store("acquisitions", acquisitions.save());
        data.store("decks", decks.save());
        data.store("kitchen", kitchen.save());
        data.store("meta", meta);
        return data;
    }

    public static ChronicleRun load(ChronicleSaveData data, ChronicleCollection.CardResolver resolver) {
        ChronicleRun run = new ChronicleRun(data.readString("runId"), data.readLong("runSeed"));
        ChronicleSaveData block = data.readSubData("timeline");
        if (block != null) {
            run.timeline.load(block);
        }
        block = data.readSubData("wallet");
        if (block != null) {
            run.wallet.load(block);
        }
        block = data.readSubData("collection");
        if (block != null) {
            run.collection.load(block, resolver);
        }
        block = data.readSubData("sealed");
        if (block != null) {
            run.sealed.load(block);
        }
        block = data.readSubData("lgs");
        if (block != null) {
            run.lgs.load(block);
        }
        block = data.readSubData("acquisitions");
        if (block != null) {
            run.acquisitions.load(block);
        }
        block = data.readSubData("decks");
        if (block != null) {
            run.decks.load(block, resolver);
        }
        block = data.readSubData("kitchen");
        if (block != null) {
            run.kitchen.load(block);
        }
        block = data.readSubData("meta");
        if (block != null) {
            run.meta.putAll(block);
        }
        return run;
    }
}
