package forge.gamemodes.chronicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import forge.item.PaperCard;
import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The kitchen table: who you can play today, what beating them pays, and what
 * has already been collected.
 *
 * This is the mode's effort→reward channel (plan pin 8), and it has two speeds.
 *
 * <b>The cash purse is bounded:</b> each rival pays once per played day, and a
 * rematch for money is free to play but pays nothing. That keeps income
 * unfarmable and the clock the player's.
 *
 * <b>Ante is unbounded, and priced in risk instead of time.</b> A player
 * confident in their deck can keep playing for keeps as long as both sides have
 * something to put up. It is self-limiting three ways rather than by a timer:
 * you stake a card from your own deck every game, the rival's collection
 * actually depletes as you take cards off them, and they stop playing for keeps
 * once they are down to what they need ({@link ChronicleConfig#anteRivalFloorCards}).
 * By volume it is a poor way to get cards — one per won game against a free
 * ration of two packs a day — which is exactly why it can be unlimited.
 *
 * The split is the answer to a question ADR-0071 left open, and it is still the
 * thing dogfood has to judge, so both halves sit behind config knobs.
 */
public final class ChronicleKitchen {

    /** One finished match at the kitchen table. */
    public static final class Result {
        public final int dayIndex;
        public final String rivalId;
        public final String deckName;
        public final boolean won;
        /** Cents actually credited — zero for a rematch, or for a loss. */
        public final long purseCents;
        /** True when this was the rival's paying game for the day. */
        public final boolean paid;
        /** Cards that changed hands, if this was played for keeps. */
        public final List<PaperCard> anteWon = new ArrayList<>();
        public final List<PaperCard> anteLost = new ArrayList<>();

        public Result(int dayIndex, String rivalId, String deckName, boolean won, long purseCents, boolean paid) {
            this.dayIndex = dayIndex;
            this.rivalId = rivalId;
            this.deckName = deckName;
            this.won = won;
            this.purseCents = purseCents;
            this.paid = paid;
        }
    }

    /** rivalId -> last played day on which this rival's purse was collected. */
    private final Map<String, Integer> lastPaidDay = new HashMap<>();
    /** Lifetime tallies, for the paper and the home screen. */
    private final Map<String, int[]> record = new HashMap<>(); //rivalId -> {wins, losses}

    /**
     * Whether a rival will still put cards up. They stop when their collection
     * is down to roughly what a deck needs — a kid who has been cleaned out says
     * so rather than handing over their last playable.
     */
    public static boolean rivalWillAnte(ChronicleConfig config, int rivalPoolSize) {
        return rivalPoolSize >= config.anteRivalFloorCards;
    }

    /** What beating this rival is worth, before the once-per-day check. */
    public static long purseCents(ChronicleConfig config, ChronicleRival rival) {
        return Math.max(0, config.pursebaseCents * rival.pursePercent / 100);
    }

    /** True if beating this rival today would actually pay. */
    public boolean purseAvailable(ChronicleRival rival, int dayIndex) {
        Integer paid = lastPaidDay.get(rival.id);
        return paid == null || paid < dayIndex;
    }

    /**
     * Record a finished match and return what it paid. A win on the rival's
     * first game of the day takes the purse; anything else is a free rematch.
     */
    public Result record(ChronicleConfig config, ChronicleRival rival, int dayIndex, String deckName, boolean won) {
        boolean payable = purseAvailable(rival, dayIndex);
        long purse = 0;
        boolean paid = false;
        if (won && payable) {
            purse = purseCents(config, rival);
            lastPaidDay.put(rival.id, dayIndex);
            paid = true;
        }
        int[] tally = record.computeIfAbsent(rival.id, k -> new int[2]);
        tally[won ? 0 : 1]++;
        return new Result(dayIndex, rival.id, deckName, won, purse, paid);
    }

    public int wins(String rivalId) {
        int[] tally = record.get(rivalId);
        return tally == null ? 0 : tally[0];
    }

    public int losses(String rivalId) {
        int[] tally = record.get(rivalId);
        return tally == null ? 0 : tally[1];
    }

    /** Rivals whose purse is still uncollected today — the home screen's "things left to do". */
    public List<ChronicleRival> unpaidToday(List<ChronicleRival> active, int dayIndex) {
        List<ChronicleRival> result = new ArrayList<>();
        for (ChronicleRival rival : active) {
            if (purseAvailable(rival, dayIndex)) {
                result.add(rival);
            }
        }
        return result;
    }

    // --- persistence -------------------------------------------------------

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        List<String> paidLines = new ArrayList<>();
        for (Map.Entry<String, Integer> e : lastPaidDay.entrySet()) {
            paidLines.add(e.getKey() + "\t" + e.getValue());
        }
        java.util.Collections.sort(paidLines);
        data.store("lastPaidDay", String.join("\n", paidLines));

        List<String> recordLines = new ArrayList<>();
        for (Map.Entry<String, int[]> e : record.entrySet()) {
            recordLines.add(e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1]);
        }
        java.util.Collections.sort(recordLines);
        data.store("record", String.join("\n", recordLines));
        return data;
    }

    public void load(ChronicleSaveData data) {
        lastPaidDay.clear();
        record.clear();
        String paidBlock = data.readString("lastPaidDay");
        if (paidBlock != null && !paidBlock.isEmpty()) {
            for (String line : paidBlock.split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length >= 2) {
                    lastPaidDay.put(f[0], Integer.parseInt(f[1]));
                }
            }
        }
        String recordBlock = data.readString("record");
        if (recordBlock != null && !recordBlock.isEmpty()) {
            for (String line : recordBlock.split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length >= 3) {
                    record.put(f[0], new int[] { Integer.parseInt(f[1]), Integer.parseInt(f[2]) });
                }
            }
        }
    }
}
