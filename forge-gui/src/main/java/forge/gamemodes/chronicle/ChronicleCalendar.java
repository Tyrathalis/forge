package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Chronicle release calendar: the ordered list of product releases with
 * shelf windows. Parsed from res/chronicle/releases.txt (pipe-delimited, one
 * row per product — the artifact the "curate the master release list" task
 * grows, era by era).
 */
public final class ChronicleCalendar {

    private final List<ChronicleRelease> releases;
    private final Map<String, ChronicleRelease> byCode;

    public ChronicleCalendar(List<ChronicleRelease> releases) {
        List<ChronicleRelease> sorted = new ArrayList<>(releases);
        sorted.sort(Comparator.comparingInt(r -> r.orderIndex));
        this.releases = Collections.unmodifiableList(sorted);
        this.byCode = new HashMap<>();
        for (ChronicleRelease r : sorted) {
            if (byCode.put(r.editionCode, r) != null) {
                throw new IllegalArgumentException("Chronicle calendar: duplicate edition code " + r.editionCode);
            }
        }
    }

    /**
     * Parse calendar rows. Format, one product per line ('#' comments, blank
     * lines skipped):
     *
     * order|code|name|kind|releaseDay|shelfDays|boosterCents|starterCents|boxCents|packsPerBox|rationEligible
     */
    public static ChronicleCalendar parse(Iterable<String> lines) {
        List<ChronicleRelease> rows = new ArrayList<>();
        int lineNo = 0;
        for (String line : lines) {
            lineNo++;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] f = trimmed.split("\\|", -1);
            if (f.length != 11) {
                throw new IllegalArgumentException("Chronicle calendar line " + lineNo + ": expected 11 fields, got " + f.length);
            }
            try {
                rows.add(new ChronicleRelease(
                        Integer.parseInt(f[0].trim()),
                        f[1].trim(),
                        f[2].trim(),
                        ChronicleRelease.Kind.valueOf(f[3].trim().toUpperCase()),
                        Integer.parseInt(f[4].trim()),
                        Integer.parseInt(f[5].trim()),
                        Integer.parseInt(f[6].trim()),
                        f[7].trim().isEmpty() ? 0 : Integer.parseInt(f[7].trim()),
                        Integer.parseInt(f[8].trim()),
                        Integer.parseInt(f[9].trim()),
                        Boolean.parseBoolean(f[10].trim())));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Chronicle calendar line " + lineNo + ": " + e.getMessage(), e);
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Chronicle calendar: no rows");
        }
        return new ChronicleCalendar(rows);
    }

    public List<ChronicleRelease> all() {
        return releases;
    }

    public ChronicleRelease byCode(String editionCode) {
        return byCode.get(editionCode);
    }

    /** Products on the shelf on the given played day, calendar order. */
    public List<ChronicleRelease> inPrintOn(int dayIndex) {
        List<ChronicleRelease> result = new ArrayList<>();
        for (ChronicleRelease r : releases) {
            if (r.inPrintOn(dayIndex)) {
                result.add(r);
            }
        }
        return result;
    }

    /** Products releasing exactly on the given played day. */
    public List<ChronicleRelease> releasingOn(int dayIndex) {
        List<ChronicleRelease> result = new ArrayList<>();
        for (ChronicleRelease r : releases) {
            if (r.releaseDay == dayIndex) {
                result.add(r);
            }
        }
        return result;
    }

    /** Products leaving the shelf within the horizon (dayIndex <= lastShelfDay < dayIndex + horizonDays) — last-chance warnings for the paper. */
    public List<ChronicleRelease> leavingShelfWithin(int dayIndex, int horizonDays) {
        List<ChronicleRelease> result = new ArrayList<>();
        for (ChronicleRelease r : releases) {
            if (r.inPrintOn(dayIndex) && r.lastShelfDay() < dayIndex + horizonDays) {
                result.add(r);
            }
        }
        return result;
    }
}
