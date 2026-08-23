package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The provenance journal: every acquisition is recorded (day, where from,
 * contents), so a card can answer "when did I pull this?" and the binder can
 * sort by true acquisition order. Append-only; at MVP scale (a few packs a
 * day) size is a non-issue.
 *
 * It records departures too. Ante can take a card out of the collection, and a
 * journal that only says where cards came from would quietly lose the more
 * interesting half of that story — "lost to Marcy, day 34" is exactly what this
 * mode is for.
 */
public final class ChronicleAcquisitionLog {

    //persistence separators: identity lines contain tabs, so records use ASCII RS/US
    private static final String RECORD_SEP = "\u001E";
    private static final String FIELD_SEP = "\u001F";

    /**
     * Where an entry's cards came from — or went. BOOSTER and STARTER keep the
     * names {@link SealedItem.Kind} persisted, so saves written before ante
     * existed load unchanged.
     */
    public enum Source {
        BOOSTER, STARTER, ANTE_WON, ANTE_LOST;

        public boolean isAnte() {
            return this == ANTE_WON || this == ANTE_LOST;
        }

        /** True when this entry ADDED cards to the collection. */
        public boolean isAcquisition() {
            return this != ANTE_LOST;
        }
    }

    /** One acquisition (or, for ANTE_LOST, one departure). */
    public static final class Entry {
        public final long seq;
        public final int dayIndex;
        public final Source kind;
        /** Edition code for openings; rival id for ante events. */
        public final String origin;
        public final List<String> cardIdentities;

        Entry(long seq, int dayIndex, Source kind, String origin, List<String> cardIdentities) {
            this.seq = seq;
            this.dayIndex = dayIndex;
            this.kind = kind;
            this.origin = origin;
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
        return record(dayIndex, Source.valueOf(kind.name()), editionCode, cards);
    }

    /**
     * Record cards won at ante. These are acquisitions like any other — a card
     * won off a rival can absolutely be the first copy you have ever owned, so
     * it takes a first-pull ordinal and earns its NEW badge.
     */
    public Entry recordAnteWon(int dayIndex, String rivalId, Iterable<PaperCard> cards) {
        return record(dayIndex, Source.ANTE_WON, rivalId, cards);
    }

    /** Record cards lost at ante. Never touches first-pull ordinals: the card was still once yours. */
    public Entry recordAnteLost(int dayIndex, String rivalId, Iterable<PaperCard> cards) {
        return record(dayIndex, Source.ANTE_LOST, rivalId, cards);
    }

    private Entry record(int dayIndex, Source source, String origin, Iterable<PaperCard> cards) {
        List<String> identities = new ArrayList<>();
        for (PaperCard card : cards) {
            String identity = identityOf(card);
            identities.add(identity);
            if (source.isAcquisition() && !firstAcquired.containsKey(identity)) {
                firstAcquired.put(identity, nextCardOrdinal++);
            }
        }
        Entry entry = new Entry(nextSeq++, dayIndex, source, origin, identities);
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
                  .append(entry.kind).append(FIELD_SEP).append(entry.origin);
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
                        Source.valueOf(f[2]), f[3], identities));
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
