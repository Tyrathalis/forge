package forge.gamemodes.chronicle;

import java.time.Clock;
import java.time.LocalDateTime;

import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * The played-day clock. One tick per played day, fired on ration collection —
 * the consuming act — never on app launch; at most one tick per real calendar
 * day (local time, with an early-morning grace window so the midnight opener
 * isn't cheated). A skipped real day simply doesn't happen in-game. Before
 * collecting, the player is still living the previous in-game day.
 *
 * Callers pass a Clock so tests can script a fortnight; production uses
 * Clock.systemDefaultZone().
 */
public final class ChronicleTimeline {

    /** Real-time days before this hour of the morning count as the previous calendar day. */
    public static final int GRACE_HOUR = 4;

    private static final long NEVER = Long.MIN_VALUE;

    /** Current in-game day. Day 0 begins with the run's first collection. */
    private int dayIndex;
    /** Effective local calendar day (epoch day) of the last collection; NEVER before the first. */
    private long lastCollectedEpochDay = NEVER;

    /** The real calendar day "now" counts as, after the grace shift. */
    public static long effectiveEpochDay(Clock clock) {
        return LocalDateTime.now(clock).minusHours(GRACE_HOUR).toLocalDate().toEpochDay();
    }

    public int getDayIndex() {
        return dayIndex;
    }

    /** False until the run's first collection. */
    public boolean hasEverTicked() {
        return lastCollectedEpochDay != NEVER;
    }

    /**
     * Whether the daily ration is collectable now (i.e., a tick is available).
     * Strictly monotonic on the effective calendar day: winding the device
     * clock backwards can never re-arm a tick (exploit-resistance above
     * realism; the grace window already covers the honest midnight opener).
     */
    public boolean canTick(Clock clock) {
        return effectiveEpochDay(clock) > lastCollectedEpochDay;
    }

    /**
     * Fire the played-day tick. The first collection begins day 0; every later
     * one advances the day. Returns the day index the collection belongs to.
     *
     * @throws IllegalStateException if today's tick already fired
     */
    public int tick(Clock clock) {
        long today = effectiveEpochDay(clock);
        if (today <= lastCollectedEpochDay) {
            throw new IllegalStateException("Chronicle: day tick already fired this calendar day");
        }
        if (lastCollectedEpochDay != NEVER) {
            dayIndex++;
        }
        lastCollectedEpochDay = today;
        return dayIndex;
    }

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("dayIndex", dayIndex);
        data.store("lastCollectedEpochDay", lastCollectedEpochDay);
        return data;
    }

    public void load(ChronicleSaveData data) {
        dayIndex = data.readInt("dayIndex");
        lastCollectedEpochDay = data.containsKey("lastCollectedEpochDay")
                ? data.readLong("lastCollectedEpochDay") : NEVER;
    }
}
