package forge.gamemodes.chronicle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.item.PaperCard;

/**
 * The D1 done-when gate: a simulated fortnight — 14 scripted played days
 * exercising ticks, a release event, shelf turnover (Alpha leaving), rations,
 * LGS and shelf purchases, buylist sales, stipend credit, and a mid-fortnight
 * save/reload with IDENTICAL continuation. Runs on the shipped res/chronicle
 * data files and the real card DB.
 */
public class ChronicleFortnightTest extends AITest {

    private static final long RUN_SEED = 20260731L;
    private static final Instant DAY0_NOON = Instant.parse("2026-08-01T12:00:00Z");

    /** Mutable fixed-zone clock the test scripts forward one day at a time. */
    private static final class TestClock extends Clock {
        private Instant instant = DAY0_NOON;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void setDay(int dayNumber) {
            instant = DAY0_NOON.plus(Duration.ofDays(dayNumber));
        }
    }

    private static ChronicleController newController(TestClock clock) {
        ChronicleConfig config = ChronicleData.loadConfig();
        return new ChronicleController(ChronicleData.loadCalendar(), config, ChronicleData.loadPricing(config),
                ChronicleController.cardDbResolver(), clock);
    }

    /**
     * One scripted played day, fully deterministic: collect the ration
     * (first eligible product), buy the first affordable LGS booster deal,
     * open every sealed item in id order, then sell every copy beyond a
     * playset of four to the buylist. Returns a digest of everything that
     * happened — the continuation-identity currency.
     */
    private static String playDay(ChronicleController controller, int expectedDay) {
        StringBuilder digest = new StringBuilder();
        assertTrue(controller.canCollectRation(), "day " + expectedDay + " should be collectable");
        assertEquals(controller.nextDayIndex(), expectedDay);

        List<ChronicleRelease> choices = controller.rationChoices();
        assertFalse(choices.isEmpty(), "there must always be a ration choice in the MVP window");
        ChronicleController.DaySummary summary = controller.collectRation(choices.get(0).editionCode);
        assertEquals(summary.dayIndex, expectedDay);
        digest.append("day=").append(summary.dayIndex)
              .append(" ration=").append(choices.get(0).editionCode)
              .append(" stipend=").append(summary.stipendCredited);
        for (SealedItem pack : summary.rationPacks) {
            digest.append(" pack[").append(pack.itemId).append(':').append(pack.contentsSeed).append(']');
        }

        // The daily LGS look: buy one unit of the first affordable booster deal.
        for (ChronicleLgs.StockOffer offer : controller.lgsStock()) {
            if (offer.kind == ChronicleLgs.StockOffer.OfferKind.BOOSTER
                    && controller.getRun().wallet.canAfford(offer.priceCents)) {
                List<SealedItem> bought = controller.buyLgsOffer(offer);
                if (bought != null) {
                    digest.append(" lgs=").append(offer.editionCode).append('@').append(offer.priceCents);
                }
                break;
            }
        }

        // Open everything sealed, oldest first.
        for (SealedItem item : new ArrayList<>(controller.getRun().sealed.all())) {
            List<PaperCard> cards = controller.openSealed(item.itemId);
            digest.append(" open[").append(item.itemId).append("]=");
            for (PaperCard card : cards) {
                digest.append(card.getName()).append('#').append(card.getArtIndex()).append(',');
            }
        }

        // Sell down to a playset of each printing.
        List<Map.Entry<PaperCard, Integer>> snapshot = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> entry : controller.getRun().collection.entries()) {
            if (entry.getValue() > 4) {
                snapshot.add(entry);
            }
        }
        snapshot.sort((a, b) -> a.getKey().getName().compareTo(b.getKey().getName()));
        for (Map.Entry<PaperCard, Integer> entry : snapshot) {
            int excess = entry.getValue() - 4;
            long credit = controller.sellToBuylist(entry.getKey(), excess);
            assertTrue(credit > 0, "owned excess must sell");
            digest.append(" sell=").append(entry.getKey().getName()).append('x').append(excess).append('@').append(credit);
        }

        ChronicleRun run = controller.getRun();
        digest.append(" wallet=").append(run.wallet.getCents())
              .append(" copies=").append(run.collection.totalCopies())
              .append(" distinct=").append(run.collection.distinctOwned())
              .append(" new=").append(run.collection.newCount())
              .append(" sealed=").append(run.sealed.size());
        return digest.toString();
    }

    @Test
    public void simulatedFortnight() throws Exception {
        TestClock clock = new TestClock();
        ChronicleController controller = newController(clock);
        controller.newRun(RUN_SEED);

        // --- Day 0: the day-one moment — allowance, then a starter deck. ---
        clock.setDay(0);
        assertEquals(controller.rationChoices().size(), 1, "day 0 shelf is Alpha only");
        ChronicleController.DaySummary day0 = controller.collectRation("LEA");
        assertEquals(day0.dayIndex, 0);
        assertEquals(day0.stipendCredited, controller.getConfig().stipendCents, "day 0 is the first payday");
        assertEquals(day0.releasesToday.get(0).editionCode, "LEA");
        assertEquals(day0.rationPacks.size(), controller.getConfig().rationPacks);
        assertFalse(controller.canCollectRation(), "one tick per calendar day");
        try {
            controller.collectRation("LEA");
            fail("second collection the same day must throw");
        } catch (IllegalStateException expected) {
            // expected
        }

        List<SealedItem> starter = controller.buyFromShelf("LEA", ChronicleLgs.StockOffer.OfferKind.STARTER);
        assertTrue(starter != null, "the stipend must cover the day-one starter");
        assertEquals(controller.openSealed(starter.get(0).itemId).size(), 60);
        for (SealedItem pack : new ArrayList<>(controller.getRun().sealed.all())) {
            assertEquals(controller.openSealed(pack.itemId).size(), 15, "Alpha ration packs are 15 cards");
        }
        assertTrue(controller.getRun().collection.totalCopies() >= 90);

        // --- Days 1-6: the Alpha week. Day 6 warns Alpha is leaving. ---
        for (int day = 1; day <= 6; day++) {
            clock.setDay(day);
            String digest = playDay(controller, day);
            if (day == 6) {
                assertTrue(digest.contains("ration=LEA"), "Alpha still rationable on its last shelf day");
            }
        }
        clock.setDay(6);
        // (post-collection view of day 6) Alpha's last-chance warning fired in the day-6 summary via playDay;
        // verify the calendar-level fact directly too:
        assertTrue(controller.getCalendar().leavingShelfWithin(6, ChronicleController.LAST_CHANCE_HORIZON_DAYS)
                .stream().anyMatch(r -> r.editionCode.equals("LEA")));

        // --- Day 7: shelf turnover + payday. Alpha is gone, Beta releases. ---
        clock.setDay(7);
        for (ChronicleRelease choice : controller.rationChoices()) {
            assertFalse(choice.editionCode.equals("LEA"), "Alpha must be off the shelf on day 7");
        }
        ChronicleController.DaySummary day7 = controller.collectRation(controller.rationChoices().get(0).editionCode);
        assertEquals(day7.stipendCredited, controller.getConfig().stipendCents, "day 7 is a payday");
        assertTrue(day7.releasesToday.stream().anyMatch(r -> r.editionCode.equals("LEB")), "Beta releases on day 7");
        for (SealedItem pack : new ArrayList<>(controller.getRun().sealed.all())) {
            controller.openSealed(pack.itemId);
        }

        // --- Mid-fortnight save. ---
        File saveDir = Files.createTempDirectory("chronicle-fortnight").toFile();
        File saveFile = new File(saveDir, "midfortnight.sav");
        assertTrue(controller.saveTo(saveFile));

        // --- Days 8-13 on the original run, digests recorded. ---
        List<String> originalDigests = new ArrayList<>();
        for (int day = 8; day <= 13; day++) {
            clock.setDay(day);
            originalDigests.add(playDay(controller, day));
        }
        assertEquals(controller.getRun().timeline.getDayIndex(), 13, "a fortnight is days 0-13");

        // --- Reload the mid-fortnight save and replay: continuation must be IDENTICAL. ---
        TestClock replayClock = new TestClock();
        ChronicleController replay = newController(replayClock);
        assertTrue(replay.loadFrom(saveFile));
        assertEquals(replay.getRun().runId, controller.getRun().runId, "run identity survives");
        assertEquals(replay.getRun().runSeed, RUN_SEED);
        assertEquals(replay.getRun().timeline.getDayIndex(), 7);
        replayClock.setDay(7);
        assertFalse(replay.canCollectRation(), "day 7's tick must not re-arm after reload");

        List<String> replayDigests = new ArrayList<>();
        for (int day = 8; day <= 13; day++) {
            replayClock.setDay(day);
            replayDigests.add(playDay(replay, day));
        }
        assertEquals(replayDigests, originalDigests,
                "save/reload must continue IDENTICALLY: same packs, same stock, same economy");

        // Meta counters moved with the run.
        assertTrue(controller.getRun().meta.readLong("totalDaysPlayed") >= 14);
        assertTrue(controller.getRun().meta.readLong("totalPacksOpened") > 0);
    }

    @Test
    public void reloadCanNeverRerollACommittedPack() throws Exception {
        TestClock clock = new TestClock();
        ChronicleController controller = newController(clock);
        controller.newRun(777L);
        clock.setDay(0);
        controller.collectRation("LEA");

        File saveFile = new File(Files.createTempDirectory("chronicle-savescum").toFile(), "before-open.sav");
        assertTrue(controller.saveTo(saveFile));

        SealedItem pack = controller.getRun().sealed.all().get(0);
        List<PaperCard> opened = controller.openSealed(pack.itemId);

        ChronicleController scummer = newController(clock);
        assertTrue(scummer.loadFrom(saveFile));
        List<PaperCard> reopened = scummer.openSealed(pack.itemId);
        assertEquals(reopened, opened, "quit-without-saving must not reroll the pull");
    }

    @Test
    public void autosaveWritesLoadableSaves() throws Exception {
        TestClock clock = new TestClock();
        ChronicleController controller = newController(clock);
        controller.newRun(999L);
        File autosave = new File(Files.createTempDirectory("chronicle-autosave").toFile(), "autosave.sav");
        controller.setAutosaveFile(autosave);

        clock.setDay(0);
        controller.collectRation("LEA");
        assertTrue(autosave.exists(), "the daily tick must autosave");

        ChronicleController resumed = newController(clock);
        assertTrue(resumed.loadFrom(autosave));
        assertEquals(resumed.getRun().timeline.getDayIndex(), 0);
        assertEquals(resumed.getRun().sealed.size(), controller.getConfig().rationPacks);
    }
}
