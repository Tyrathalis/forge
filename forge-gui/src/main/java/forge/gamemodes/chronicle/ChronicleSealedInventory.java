package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The player's unopened sealed products. Items get a monotonic id; the
 * contents seed derives from (run seed, item id) and is committed here, at
 * acquisition, never at opening.
 */
public final class ChronicleSealedInventory {

    private final Map<Long, SealedItem> items = new LinkedHashMap<>();
    private long nextItemId = 1;

    /** Acquire one sealed item, committing its contents seed. */
    public SealedItem acquire(long runSeed, SealedItem.Kind kind, String editionCode, int dayIndex) {
        long id = nextItemId++;
        long seed = ChronicleSeeds.deriveItem(runSeed, ChronicleSeeds.DOMAIN_SEALED_CONTENTS, id);
        SealedItem item = new SealedItem(id, kind, editionCode, seed, dayIndex);
        items.put(id, item);
        return item;
    }

    /** Acquire several at once (a box materializes as its component boosters). */
    public List<SealedItem> acquire(long runSeed, SealedItem.Kind kind, String editionCode, int dayIndex, int count) {
        List<SealedItem> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(acquire(runSeed, kind, editionCode, dayIndex));
        }
        return result;
    }

    /** Remove the item for opening. Null if the id is unknown (already opened). */
    public SealedItem take(long itemId) {
        return items.remove(itemId);
    }

    public SealedItem get(long itemId) {
        return items.get(itemId);
    }

    public List<SealedItem> all() {
        return Collections.unmodifiableList(new ArrayList<>(items.values()));
    }

    public int size() {
        return items.size();
    }

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("nextItemId", nextItemId);
        List<String> lines = new ArrayList<>();
        for (SealedItem item : items.values()) {
            lines.add(item.itemId + "|" + item.kind + "|" + item.editionCode + "|" + item.contentsSeed + "|" + item.acquiredDay);
        }
        data.store("items", String.join("\n", lines));
        return data;
    }

    public void load(ChronicleSaveData data) {
        items.clear();
        nextItemId = data.readLong("nextItemId");
        if (nextItemId < 1) {
            nextItemId = 1;
        }
        String block = data.readString("items");
        if (block == null || block.isEmpty()) {
            return;
        }
        for (String line : block.split("\n")) {
            String[] f = line.split("\\|", -1);
            SealedItem item = new SealedItem(Long.parseLong(f[0]), SealedItem.Kind.valueOf(f[1]), f[2],
                    Long.parseLong(f[3]), Integer.parseInt(f[4]));
            items.put(item.itemId, item);
        }
    }
}
