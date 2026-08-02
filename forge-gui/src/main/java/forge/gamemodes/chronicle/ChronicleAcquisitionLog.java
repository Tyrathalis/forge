package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The provenance journal: every sealed opening is recorded (day, product,
 * contents), so a card can answer "when did I pull this?" and the binder can
 * sort by true acquisition order. Append-only; at MVP scale (a few packs a
 * day) size is a non-issue.
 */
public final class ChronicleAcquisitionLog {

    //persistence separators: identity lines contain tabs, so records use ASCII RS/US
    private static final String RECORD_SEP = "\u001E";
    private static final String FIELD_SEP = "\u001F";

    /** One opening event. */
    public static final class Entry {
        public final long seq;
        public final int dayIndex;
        public final SealedItem.Kind kind;
        public final String editionCode;
        public final List<String> cardIdentities;

        Entry(long seq, int dayIndex, SealedItem.Kind kind, String editionCode, List<String> cardIdentities) {
            this.seq = seq;
            this.dayIndex = dayIndex;
            this.kind = kind;
            this.editionCode = editionCode;
            this.cardIdentities = Collections.unmodifiableList(cardIdentities);
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    /** identity line -> ordinal of the card's FIRST acquisition (per-card sequence, not per-event). */
    private final Map<String, Long> firstAcquired = new HashMap<>();
    private long nextSeq = 1;
    private long nextCardOrdinal = 1;

    /** Record one opening. Cards keep pack order. */
    public Entry record(int dayIndex, SealedItem.Kind kind, String editionCode, Iterable<PaperCard> cards) {
        List<String> identities = new ArrayList<>();
        for (PaperCard card : cards) {
            String identity = identityOf(card);
            identities.add(identity);
            if (!firstAcquired.containsKey(identity)) {
                firstAcquired.put(identity, nextCardOrdinal++);
            }
        }
        Entry entry = new Entry(nextSeq++, dayIndex, kind, editionCode, identities);
        entries.add(entry);
        return entry;
    }

    public List<Entry> all() {
        return Collections.unmodifiableList(entries);
    }

    /** Opening events that contained this printing, oldest first. */
    public List<Entry> eventsFor(PaperCard card) {
        String identity = identityOf(card);
        List<Entry> result = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.cardIdentities.contains(identity)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Ordinal of the card's first acquisition (1 = the run's first-ever new
     * printing), or 0 if never pulled — the binder's true opening-order sort key.
     */
    public long firstAcquiredOrdinal(PaperCard card) {
        return firstAcquired.getOrDefault(identityOf(card), 0L);
    }

    /** How many copies of this printing one opening event contained (a starter can carry duplicates). */
    public static int copiesIn(Entry entry, PaperCard card) {
        return Collections.frequency(entry.cardIdentities, identityOf(card));
    }

    /**
     * Which product kinds of the card's own set can yield this printing: the
     * booster (Forge's era sheets carry no basic lands there) and the starter
     * if one exists (starters include basics).
     */
    public static List<SealedItem.Kind> sourcesFor(PaperCard card, ChronicleCalendar calendar) {
        List<SealedItem.Kind> result = new ArrayList<>();
        ChronicleRelease release = calendar.byCode(card.getEdition());
        if (release == null) {
            return result;
        }
        if (card.getRarity() != forge.card.CardRarity.BasicLand) {
            result.add(SealedItem.Kind.BOOSTER);
        }
        if (release.hasStarter()) {
            result.add(SealedItem.Kind.STARTER);
        }
        return result;
    }

    static String identityOf(PaperCard card) {
        return card.getName() + "\t" + card.getEdition() + "\t" + card.getArtIndex() + "\t" + card.isFoil();
    }

    // --- persistence -------------------------------------------------------

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("nextSeq", nextSeq);
        data.store("nextCardOrdinal", nextCardOrdinal);
        StringBuilder events = new StringBuilder();
        for (Entry entry : entries) {
            if (events.length() > 0) {
                events.append(RECORD_SEP);
            }
            events.append(entry.seq).append(FIELD_SEP).append(entry.dayIndex).append(FIELD_SEP)
                  .append(entry.kind).append(FIELD_SEP).append(entry.editionCode);
            for (String identity : entry.cardIdentities) {
                events.append(FIELD_SEP).append(identity);
            }
        }
        data.store("events", events.toString());
        StringBuilder firsts = new StringBuilder();
        for (Map.Entry<String, Long> e : firstAcquired.entrySet()) {
            if (firsts.length() > 0) {
                firsts.append(RECORD_SEP);
            }
            firsts.append(e.getValue()).append(FIELD_SEP).append(e.getKey());
        }
        data.store("firstAcquired", firsts.toString());
        return data;
    }

    public void load(ChronicleSaveData data) {
        entries.clear();
        firstAcquired.clear();
        nextSeq = Math.max(1, data.readLong("nextSeq"));
        nextCardOrdinal = Math.max(1, data.readLong("nextCardOrdinal"));
        String events = data.readString("events");
        if (events != null && !events.isEmpty()) {
            for (String record : events.split(RECORD_SEP)) {
                String[] f = record.split(FIELD_SEP, -1);
                if (f.length < 4) {
                    continue;
                }
                List<String> identities = new ArrayList<>();
                for (int i = 4; i < f.length; i++) {
                    identities.add(f[i]);
                }
                entries.add(new Entry(Long.parseLong(f[0]), Integer.parseInt(f[1]),
                        SealedItem.Kind.valueOf(f[2]), f[3], identities));
            }
        }
        String firsts = data.readString("firstAcquired");
        if (firsts != null && !firsts.isEmpty()) {
            for (String record : firsts.split(RECORD_SEP)) {
                int sep = record.indexOf(FIELD_SEP);
                if (sep > 0) {
                    firstAcquired.put(record.substring(sep + 1), Long.parseLong(record.substring(0, sep)));
                }
            }
        }
    }
}
