package forge.gamemodes.chronicle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.gamemodes.chronicle.io.ChronicleSaveData;
import forge.gamemodes.chronicle.io.ChronicleSaveIO;

/**
 * Pure unit tests for Chronicle's headless core: no card DB, no display, no
 * user profile — everything runs against inline data and temp directories.
 */
public class ChronicleCoreTest {

    private static final List<String> CALENDAR_LINES = Arrays.asList(
            "# test calendar",
            "1|LEA|Alpha|primary|0|7|245|895|7995|36|true",
            "2|LEB|Beta|primary|7|14|245|895|7995|36|true",
            "3|ARN|Arabian Nights|primary|28|28|145||7995|60|true");

    private static Clock clockAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    // --- seeds -------------------------------------------------------------

    @Test
    public void seedsAreDeterministicAndDomainSeparated() {
        long a1 = ChronicleSeeds.deriveDaily(42L, 3, ChronicleSeeds.DOMAIN_LGS_STOCK);
        long a2 = ChronicleSeeds.deriveDaily(42L, 3, ChronicleSeeds.DOMAIN_LGS_STOCK);
        assertEquals(a1, a2, "same inputs must derive the same seed");

        assertNotEquals(a1, ChronicleSeeds.deriveDaily(42L, 4, ChronicleSeeds.DOMAIN_LGS_STOCK), "day index must matter");
        assertNotEquals(a1, ChronicleSeeds.deriveDaily(43L, 3, ChronicleSeeds.DOMAIN_LGS_STOCK), "run seed must matter");
        assertNotEquals(a1, ChronicleSeeds.deriveDaily(42L, 3, "other-domain"), "domain must matter");

        long i1 = ChronicleSeeds.deriveItem(42L, ChronicleSeeds.DOMAIN_SEALED_CONTENTS, 7);
        assertEquals(i1, ChronicleSeeds.deriveItem(42L, ChronicleSeeds.DOMAIN_SEALED_CONTENTS, 7));
        assertNotEquals(i1, ChronicleSeeds.deriveItem(42L, ChronicleSeeds.DOMAIN_SEALED_CONTENTS, 8));
    }

    // --- timeline ----------------------------------------------------------

    @Test
    public void timelineFirstCollectionBeginsDayZero() {
        ChronicleTimeline timeline = new ChronicleTimeline();
        assertFalse(timeline.hasEverTicked());
        assertEquals(timeline.getDayIndex(), 0);
        assertTrue(timeline.canTick(clockAt("2026-08-01T12:00:00Z")));

        assertEquals(timeline.tick(clockAt("2026-08-01T12:00:00Z")), 0, "first collection IS day 0");
        assertTrue(timeline.hasEverTicked());
        assertEquals(timeline.tick(clockAt("2026-08-02T12:00:00Z")), 1);
        assertEquals(timeline.getDayIndex(), 1);
    }

    @Test
    public void timelineRefusesSecondTickSameCalendarDay() {
        ChronicleTimeline timeline = new ChronicleTimeline();
        timeline.tick(clockAt("2026-08-01T09:00:00Z"));
        assertFalse(timeline.canTick(clockAt("2026-08-01T23:59:00Z")));
        try {
            timeline.tick(clockAt("2026-08-01T23:59:00Z"));
            fail("same-day second tick must throw");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    @Test
    public void timelineGraceWindowCountsEarlyMorningAsPreviousDay() {
        ChronicleTimeline timeline = new ChronicleTimeline();
        timeline.tick(clockAt("2026-08-01T12:00:00Z"));
        // 03:00 next calendar day is inside the 4am grace window: still Aug 1.
        assertFalse(timeline.canTick(clockAt("2026-08-02T03:00:00Z")));
        // 05:00 is past the grace window: a fresh day.
        assertTrue(timeline.canTick(clockAt("2026-08-02T05:00:00Z")));
        assertEquals(timeline.tick(clockAt("2026-08-02T05:00:00Z")), 1);
        // The midnight opener isn't cheated: a tick AT 03:00 belongs to the previous day...
        assertFalse(timeline.canTick(clockAt("2026-08-02T03:30:00Z")));
    }

    @Test
    public void timelineSkippedRealDaysDoNotHappen() {
        ChronicleTimeline timeline = new ChronicleTimeline();
        timeline.tick(clockAt("2026-08-01T12:00:00Z"));
        // A week of real absence advances the played day by exactly one.
        assertEquals(timeline.tick(clockAt("2026-08-08T12:00:00Z")), 1);
    }

    @Test
    public void timelineSaveLoadRoundtrip() {
        ChronicleTimeline timeline = new ChronicleTimeline();
        timeline.tick(clockAt("2026-08-01T12:00:00Z"));
        timeline.tick(clockAt("2026-08-02T12:00:00Z"));

        ChronicleTimeline loaded = new ChronicleTimeline();
        loaded.load(timeline.save());
        assertEquals(loaded.getDayIndex(), 1);
        assertTrue(loaded.hasEverTicked());
        assertFalse(loaded.canTick(clockAt("2026-08-02T18:00:00Z")), "reload must not re-arm today's tick");
        assertTrue(loaded.canTick(clockAt("2026-08-03T12:00:00Z")));
    }

    // --- calendar ----------------------------------------------------------

    @Test
    public void calendarParsesAndAnswersWindows() {
        ChronicleCalendar calendar = ChronicleCalendar.parse(CALENDAR_LINES);
        assertEquals(calendar.all().size(), 3);
        assertEquals(calendar.byCode("LEA").name, "Alpha");
        assertFalse(calendar.byCode("ARN").hasStarter());
        assertTrue(calendar.byCode("LEA").hasStarter());

        // Day 0: only Alpha. Day 7: Alpha gone (7-day shelf), Beta arrives.
        assertEquals(calendar.inPrintOn(0).size(), 1);
        assertEquals(calendar.inPrintOn(0).get(0).editionCode, "LEA");
        List<ChronicleRelease> day7 = calendar.inPrintOn(7);
        assertEquals(day7.size(), 1);
        assertEquals(day7.get(0).editionCode, "LEB");

        assertEquals(calendar.releasingOn(7).get(0).editionCode, "LEB");
        assertTrue(calendar.releasingOn(1).isEmpty());

        // Alpha's last shelf day is 6; from day 0 with a 7-day horizon it's a last-chance item.
        List<ChronicleRelease> leaving = calendar.leavingShelfWithin(0, 7);
        assertEquals(leaving.size(), 1);
        assertEquals(leaving.get(0).editionCode, "LEA");
        assertTrue(calendar.leavingShelfWithin(8, 7).isEmpty(), "Beta's window extends past the horizon");
    }

    @Test
    public void calendarRejectsBadRows() {
        try {
            ChronicleCalendar.parse(Arrays.asList("1|LEA|Alpha|primary|0|7|245|895|7995|36"));
            fail("wrong field count must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            ChronicleCalendar.parse(Arrays.asList(
                    "1|LEA|Alpha|primary|0|7|245|895|7995|36|true",
                    "2|LEA|Alpha again|primary|7|7|245|895|7995|36|true"));
            fail("duplicate edition code must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // --- config + notables -------------------------------------------------

    @Test
    public void configParsesWithDefaults() {
        ChronicleConfig config = ChronicleConfig.parse(Arrays.asList(
                "# comment", "rationPacks=3", "unknownFutureKey=ignored"));
        assertEquals(config.rationPacks, 3);
        assertEquals(config.stipendCents, 1000, "missing keys take defaults");
        assertEquals(config.stipendPeriodDays, 7);
        assertEquals((int) config.buylistBaseCents.get(forge.card.CardRarity.Rare), 40);
    }

    @Test
    public void notablesParseRejectsDuplicates() {
        Map<String, Integer> notables = ChroniclePricing.parseNotables(Arrays.asList(
                "power|Black Lotus;Mox Jet|20", "juzam|Juzám Djinn|10"));
        assertEquals((int) notables.get("Black Lotus"), 20);
        assertEquals((int) notables.get("Juzám Djinn"), 10);
        try {
            ChroniclePricing.parseNotables(Arrays.asList("a|Black Lotus|20", "b|Black Lotus|5"));
            fail("duplicate name across stories must throw");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    // --- wallet + stipend --------------------------------------------------

    @Test
    public void walletStipendPaysOnPaydaysOnce() {
        ChronicleWallet wallet = new ChronicleWallet();
        assertEquals(wallet.creditStipendIfDue(0, 7, 1000), 1000, "day 0 is the first payday");
        assertEquals(wallet.creditStipendIfDue(0, 7, 1000), 0, "no double pay");
        assertEquals(wallet.creditStipendIfDue(3, 7, 1000), 0);
        assertEquals(wallet.creditStipendIfDue(7, 7, 1000), 1000);
        assertEquals(wallet.getCents(), 2000);

        assertFalse(wallet.debit(2001));
        assertEquals(wallet.getCents(), 2000, "failed debit must not change the balance");
        assertTrue(wallet.debit(1500));
        assertEquals(wallet.getCents(), 500);

        ChronicleWallet loaded = new ChronicleWallet();
        loaded.load(wallet.save());
        assertEquals(loaded.getCents(), 500);
        assertEquals(loaded.creditStipendIfDue(7, 7, 1000), 0, "paid payday survives reload");
    }

    // --- save data + container --------------------------------------------

    @Test
    public void saveDataRoundtripsAllTypes() {
        ChronicleSaveData data = new ChronicleSaveData();
        data.store("i", 42);
        data.store("l", 42L << 40);
        data.store("b", true);
        data.store("s", "hello");
        data.store("f", 1.5f);
        data.store("d", 2.5);
        ChronicleSaveData sub = new ChronicleSaveData();
        sub.store("inner", "nested");
        data.store("sub", sub);

        assertEquals(data.readInt("i"), 42);
        assertEquals(data.readLong("l"), 42L << 40);
        assertTrue(data.readBool("b"));
        assertEquals(data.readString("s"), "hello");
        assertEquals(data.readFloat("f"), 1.5f);
        assertEquals(data.readDouble("d"), 2.5);
        assertEquals(data.readSubData("sub").readString("inner"), "nested");

        // Defensive misses: type-appropriate zeros, no throw.
        assertEquals(data.readInt("absent"), 0);
        assertNull(data.readString("absent"));
        assertFalse(data.hasStoreError());
    }

    @Test
    public void saveIoRoundtripAndHeaderOnlyRead() throws Exception {
        File dir = Files.createTempDirectory("chronicle-test").toFile();
        File file = ChronicleSaveIO.slotFile(dir, 1);

        ChronicleSaveData header = new ChronicleSaveData();
        header.store("dayIndex", 5);
        ChronicleSaveData main = new ChronicleSaveData();
        main.store("payload", "the run");

        assertTrue(ChronicleSaveIO.save(file, header, main));
        assertTrue(file.exists());
        assertFalse(new File(file.getAbsolutePath() + ".old").exists(), "backup cleaned after success");

        ChronicleSaveIO.Loaded loaded = ChronicleSaveIO.load(file);
        assertEquals(loaded.version, ChronicleSaveIO.CURRENT_VERSION);
        assertEquals(loaded.header.readInt("dayIndex"), 5);
        assertEquals(loaded.main.readString("payload"), "the run");

        ChronicleSaveIO.Loaded headerOnly = ChronicleSaveIO.readHeader(file);
        assertEquals(headerOnly.header.readInt("dayIndex"), 5);
        assertNull(headerOnly.main);

        assertEquals(ChronicleSaveIO.listSaves(dir).size(), 1);
    }

    @Test
    public void saveIoRefusesSentinelAndKeepsPreviousSave() throws Exception {
        File dir = Files.createTempDirectory("chronicle-test").toFile();
        File file = ChronicleSaveIO.slotFile(dir, 0);

        ChronicleSaveData good = new ChronicleSaveData();
        good.store("v", "first");
        assertTrue(ChronicleSaveIO.save(file, new ChronicleSaveData(), good));

        ChronicleSaveData poisoned = new ChronicleSaveData();
        poisoned.store("v", "second");
        poisoned.put(ChronicleSaveData.ERROR_SENTINEL_KEY, "boom".getBytes());
        assertFalse(ChronicleSaveIO.save(file, new ChronicleSaveData(), poisoned), "sentinel must abort the save");

        assertEquals(ChronicleSaveIO.load(file).main.readString("v"), "first", "previous save intact");
    }

    @Test
    public void saveIoLoadReturnsNullOnGarbage() throws Exception {
        File dir = Files.createTempDirectory("chronicle-test").toFile();
        File file = new File(dir, "garbage.sav");
        Files.write(file.toPath(), new byte[] { 1, 2, 3, 4 });
        assertNull(ChronicleSaveIO.load(file));
        assertNull(ChronicleSaveIO.readHeader(file));
    }

    // --- LGS ----------------------------------------------------------------

    @Test
    public void lgsStockIsDeterministicPerDay() {
        ChronicleCalendar calendar = ChronicleCalendar.parse(CALENDAR_LINES);
        ChronicleConfig config = ChronicleConfig.parse(Arrays.asList("lgsStockSlots=4"));
        ChronicleLgs lgs = new ChronicleLgs();

        List<ChronicleLgs.StockOffer> first = lgs.stockFor(calendar, config, 99L, 3);
        List<ChronicleLgs.StockOffer> second = lgs.stockFor(calendar, config, 99L, 3);
        assertEquals(first.size(), 4);
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).editionCode, second.get(i).editionCode, "same day, same stock");
            assertEquals(first.get(i).kind, second.get(i).kind);
            assertEquals(first.get(i).priceCents, second.get(i).priceCents);
            assertEquals(first.get(i).quantity, second.get(i).quantity);
            assertTrue(first.get(i).priceCents <= msrpOf(calendar, first.get(i)), "deals never exceed MSRP");
        }

        // A different day rolls different stock (with 4 slots the chance of an identical roll is negligible).
        List<ChronicleLgs.StockOffer> nextDay = lgs.stockFor(calendar, config, 99L, 4);
        boolean anyDifferent = false;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).priceCents != nextDay.get(i).priceCents
                    || first.get(i).quantity != nextDay.get(i).quantity
                    || first.get(i).kind != nextDay.get(i).kind) {
                anyDifferent = true;
            }
        }
        assertTrue(anyDifferent, "different day should roll different stock");
    }

    private static int msrpOf(ChronicleCalendar calendar, ChronicleLgs.StockOffer offer) {
        ChronicleRelease product = calendar.byCode(offer.editionCode);
        switch (offer.kind) {
            case BOOSTER: return product.boosterCents;
            case BOX: return product.boxCents;
            case STARTER: return product.starterCents;
            default: throw new IllegalStateException();
        }
    }

    @Test
    public void lgsPurchaseTrackingSellsOutAndResetsNextDay() {
        ChronicleCalendar calendar = ChronicleCalendar.parse(CALENDAR_LINES);
        ChronicleConfig config = ChronicleConfig.parse(Arrays.asList("lgsStockSlots=2"));
        ChronicleLgs lgs = new ChronicleLgs();

        ChronicleLgs.StockOffer offer = lgs.stockFor(calendar, config, 7L, 0).get(0);
        for (int i = 0; i < offer.quantity; i++) {
            assertTrue(lgs.recordPurchase(config, offer, 0));
        }
        assertFalse(lgs.recordPurchase(config, offer, 0), "slot must sell out");
        assertEquals(lgs.purchasedFrom(0, offer.slot), offer.quantity);

        // Reload keeps today's purchases.
        ChronicleLgs loaded = new ChronicleLgs();
        loaded.load(lgs.save());
        assertFalse(loaded.recordPurchase(config, offer, 0), "sold-out state survives reload");

        // The next day resets the counters.
        ChronicleLgs.StockOffer tomorrow = loaded.stockFor(calendar, config, 7L, 1).get(0);
        assertTrue(loaded.recordPurchase(config, tomorrow, 1));
    }

    // --- effective-day arithmetic sanity ------------------------------------

    @Test
    public void effectiveEpochDayShiftsExactlyAtGraceHour() {
        ZoneId zone = ZoneOffset.UTC;
        Instant beforeGrace = Instant.parse("2026-08-02T03:59:59Z");
        Instant afterGrace = Instant.parse("2026-08-02T04:00:00Z");
        long dayBefore = ChronicleTimeline.effectiveEpochDay(Clock.fixed(beforeGrace, zone));
        long dayAfter = ChronicleTimeline.effectiveEpochDay(Clock.fixed(afterGrace, zone));
        assertEquals(dayAfter - dayBefore, 1, "grace boundary must fall exactly at 04:00 local");
        assertEquals(dayBefore,
                ChronicleTimeline.effectiveEpochDay(Clock.fixed(beforeGrace.minus(23, ChronoUnit.HOURS), zone)),
                "03:59 groups with the previous calendar day");
    }
}
