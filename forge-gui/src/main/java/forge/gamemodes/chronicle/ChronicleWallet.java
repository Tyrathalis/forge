package forge.gamemodes.chronicle;

import forge.gamemodes.chronicle.io.ChronicleSaveData;

/**
 * Period-dollar wallet (integer cents) plus the allowance stipend — the MVP's
 * income source (battler and tournaments are out; pack-cracking is EV-negative
 * by invariant, so without the allowance currency strictly drains and the LGS
 * is unreachable). Kitchen-table-kid flavor; retires or reflavors when
 * tournament income arrives.
 *
 * The stipend pays on the played-day schedule: every stipendPeriodDays-th day
 * index starting at day 0, credited when that day's tick fires — so the run's
 * first collection funds the day-one starter purchase.
 */
public final class ChronicleWallet {

    private long cents;
    /** Day index of the last stipend credit; Integer.MIN_VALUE before the first. */
    private int lastStipendDay = Integer.MIN_VALUE;

    public long getCents() {
        return cents;
    }

    public void credit(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("credit " + amount);
        }
        cents += amount;
    }

    /** False (and no change) if the balance can't cover it. */
    public boolean debit(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("debit " + amount);
        }
        if (amount > cents) {
            return false;
        }
        cents -= amount;
        return true;
    }

    public boolean canAfford(long amount) {
        return amount <= cents;
    }

    /**
     * Credit the stipend if the just-ticked day is a payday not yet paid.
     * Returns the amount credited (0 if none due).
     */
    public long creditStipendIfDue(int dayIndex, int stipendPeriodDays, long stipendCents) {
        if (dayIndex % stipendPeriodDays != 0 || dayIndex == lastStipendDay) {
            return 0;
        }
        lastStipendDay = dayIndex;
        credit(stipendCents);
        return stipendCents;
    }

    public ChronicleSaveData save() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("cents", cents);
        data.store("lastStipendDay", lastStipendDay);
        return data;
    }

    public void load(ChronicleSaveData data) {
        cents = data.readLong("cents");
        lastStipendDay = data.containsKey("lastStipendDay") ? data.readInt("lastStipendDay") : Integer.MIN_VALUE;
    }
}
