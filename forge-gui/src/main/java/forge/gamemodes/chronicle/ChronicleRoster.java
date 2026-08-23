package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rival cast, parsed from res/chronicle/rivals.txt — the same curated-data-file
 * convention as the release calendar and the notables table, and the artifact the
 * cast grows through as the content window extends era by era.
 */
public final class ChronicleRoster {

    private final List<ChronicleRival> rivals;
    private final Map<String, ChronicleRival> byId;

    public ChronicleRoster(List<ChronicleRival> rivals) {
        List<ChronicleRival> sorted = new ArrayList<>(rivals);
        sorted.sort(Comparator.comparingInt((ChronicleRival r) -> r.joinDay).thenComparing(r -> r.id));
        this.rivals = Collections.unmodifiableList(sorted);
        this.byId = new HashMap<>();
        for (ChronicleRival r : sorted) {
            if (byId.put(r.id, r) != null) {
                throw new IllegalArgumentException("Chronicle roster: duplicate rival id " + r.id);
            }
        }
    }

    /**
     * Parse roster rows. Format, one rival per line ('#' comments, blank lines
     * skipped):
     *
     * id|name|joinDay|packsPerDay|pursePercent|flavor
     */
    public static ChronicleRoster parse(Iterable<String> lines) {
        List<ChronicleRival> rows = new ArrayList<>();
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] f = trimmed.split("\\|", -1);
            if (f.length != 6) {
                throw new IllegalArgumentException("Chronicle roster line " + lineNo
                        + ": expected 6 fields, got " + f.length);
            }
            try {
                rows.add(new ChronicleRival(
                        f[0].trim(),
                        f[1].trim(),
                        Integer.parseInt(f[2].trim()),
                        Float.parseFloat(f[3].trim()),
                        Integer.parseInt(f[4].trim()),
                        f[5].trim()));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Chronicle roster line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        return new ChronicleRoster(rows);
    }

    public List<ChronicleRival> all() {
        return rivals;
    }

    public ChronicleRival byId(String id) {
        return byId.get(id);
    }

    /** Rivals who are around on the given played day, in join order. */
    public List<ChronicleRival> activeOn(int dayIndex) {
        List<ChronicleRival> result = new ArrayList<>();
        for (ChronicleRival r : rivals) {
            if (r.isAroundOn(dayIndex)) {
                result.add(r);
            }
        }
        return result;
    }

    /** Rivals joining exactly on this day — the paper's "new kid at the table" hook. */
    public List<ChronicleRival> joiningOn(int dayIndex) {
        List<ChronicleRival> result = new ArrayList<>();
        for (ChronicleRival r : rivals) {
            if (r.joinDay == dayIndex) {
                result.add(r);
            }
        }
        return result;
    }

    public int size() {
        return rivals.size();
    }
}
