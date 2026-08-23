package forge.gamemodes.chronicle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gamemodes.chronicle.io.ChronicleSaveData;
import forge.item.PaperCard;

/**
 * D6 (the kitchen table) unit coverage. The properties under test are the ones
 * that would be expensive to discover later: that a rival's derived collection
 * obeys seed integrity and the era bound, that a generated deck can never name
 * cards its owner doesn't have, and that the purse pays once per played day.
 */
public class ChronicleKitchenTest extends AITest {

    private static final long RUN_SEED = 20260822L;

    private static ChronicleRoster roster() {
        return ChronicleRoster.parse(Arrays.asList(
                "# comment",
                "",
                "kid|Danny|0|0.5|60|little brother",
                "marcy|Marcy|3|1.5|100|trades hard",
                "vince|Vince|21|3.0|180|paper route"));
    }

    private static ChronicleConfig config() {
        return ChronicleData.loadConfig();
    }

    // --- roster ------------------------------------------------------------

    @Test
    public void rosterParsesAndOrdersByJoinDay() {
        ChronicleRoster r = roster();
        assertEquals(r.size(), 3);
        assertEquals(r.all().get(0).id, "kid");
        assertEquals(r.all().get(2).id, "vince");
        assertEquals(r.byId("marcy").pursePercent, 100);
    }

    @Test
    public void rosterActiveTracksJoinDay() {
        ChronicleRoster r = roster();
        assertEquals(r.activeOn(0).size(), 1);
        assertEquals(r.activeOn(3).size(), 2);
        assertEquals(r.activeOn(21).size(), 3);
        assertEquals(r.joiningOn(3).get(0).id, "marcy");
        assertTrue(r.joiningOn(4).isEmpty());
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rosterRejectsDuplicateIds() {
        ChronicleRoster.parse(Arrays.asList("a|A|0|1|100|x", "a|B|1|1|100|y"));
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rosterRejectsShortRows() {
        ChronicleRoster.parse(Arrays.asList("a|A|0|1|100"));
    }

    @Test
    public void anAbsentRosterFileIsDistinguishableFromAnEmptyDay() {
        //a missing rivals.txt and "nobody has moved in yet" render identically
        //unless the roster can say which it is — that ambiguity hid the kitchen
        //table on an updated install for a whole release
        assertTrue(ChronicleRoster.parse(new ArrayList<String>()).isEmpty());
        assertTrue(ChronicleRoster.parse(Arrays.asList("# only a comment", "")).isEmpty());
        assertFalse(roster().isEmpty());
        //and a populated roster always has somebody at day 0, so an empty table
        //on a played save can only mean the data file did not load
        assertFalse(ChronicleData.loadRoster().isEmpty(), "the shipped rivals.txt must parse");
        assertFalse(ChronicleData.loadRoster().activeOn(0).isEmpty(),
                "somebody must be around on day 0, or every save looks broken");
    }

    // --- the rival's growing collection ------------------------------------

    @Test
    public void packCountGrowsWithTheAllowance() {
        ChronicleRival slow = roster().byId("kid");   //0.5 packs/day
        ChronicleRival fast = roster().byId("vince"); //3.0 packs/day, joins day 21

        assertEquals(slow.packsOwnedBy(0), 0);  //half a pack is no pack yet
        assertEquals(slow.packsOwnedBy(1), 1);
        assertEquals(slow.packsOwnedBy(9), 5);

        assertEquals(fast.packsOwnedBy(20), 0); //not around yet
        assertEquals(fast.packsOwnedBy(21), 3);
        assertEquals(fast.packsOwnedBy(22), 6);
    }

    @Test
    public void aRivalsCollectionOnlyEverGrows() {
        //the seed-integrity property that matters for rivals: pack i is the same
        //pack forever, so day D+1's pool CONTAINS day D's — never reshuffles it
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);

        CardPool early = pool.derivedPoolFor(marcy, 10);
        CardPool later = pool.derivedPoolFor(marcy, 17);
        assertTrue(early.countAll() > 0, "rival should own something by day 10");
        assertTrue(later.countAll() > early.countAll(), "a week of allowance should add cards");
        for (Map.Entry<PaperCard, Integer> e : early) {
            assertTrue(later.count(e.getKey()) >= e.getValue(),
                    "day 17 lost a card the rival owned on day 10: " + e.getKey());
        }
    }

    @Test
    public void aRivalsCollectionIsNotRerollable() {
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleCalendar calendar = ChronicleData.loadCalendar();

        //a fresh derivation of the same run seed must reproduce it exactly
        CardPool first = new ChronicleRivalPool(calendar, RUN_SEED).derivedPoolFor(marcy, 12);
        CardPool second = new ChronicleRivalPool(calendar, RUN_SEED).derivedPoolFor(marcy, 12);
        assertEquals(digest(second), digest(first), "same run seed must give the same rival collection");

        //and a different run must not
        CardPool other = new ChronicleRivalPool(calendar, RUN_SEED + 1).derivedPoolFor(marcy, 12);
        assertFalse(digest(other).equals(digest(first)), "a different run seed should give a different rival");
    }

    @Test
    public void aRivalCanOnlyOwnWhatHasBeenReleased() {
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleCalendar calendar = ChronicleData.loadCalendar();
        ChronicleRivalPool pool = new ChronicleRivalPool(calendar, RUN_SEED);

        Map<String, Integer> releaseDay = new HashMap<>();
        for (ChronicleRelease r : calendar.all()) {
            releaseDay.put(r.editionCode, r.releaseDay);
        }
        int day = 30;
        for (Map.Entry<PaperCard, Integer> e : pool.derivedPoolFor(marcy, day)) {
            Integer released = releaseDay.get(e.getKey().getEdition());
            if (released != null) {
                assertTrue(released <= day,
                        e.getKey() + " from " + e.getKey().getEdition() + " is not out yet on day " + day);
            }
        }
    }

    @Test
    public void aRivalKeepsWhatWentOutOfPrint() {
        //the collector property: Alpha leaves the shelf on day 7, but a rival who
        //bought it in week one still has it in week six
        ChronicleRival kid = roster().byId("kid");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);
        CardPool early = pool.derivedPoolFor(kid, 6);
        CardPool late = pool.derivedPoolFor(kid, 42);
        for (Map.Entry<PaperCard, Integer> e : early) {
            assertTrue(late.count(e.getKey()) >= e.getValue(),
                    "rival lost an out-of-print card: " + e.getKey());
        }
    }

    // --- deck building ------------------------------------------------------

    @Test
    public void aGeneratedDeckNeverNamesCardsItsOwnerLacks() {
        //DeckGenPool is keyed by NAME and count-blind, so this is the clamp under test
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);
        CardPool owned = pool.derivedPoolFor(marcy, 24);

        Deck deck = new ChronicleDeckBuilder().buildFrom(owned, 1234L, "Marcy");
        Map<String, Integer> ownedByName = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : owned) {
            ownedByName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        Map<String, Integer> usedByName = new HashMap<>();
        for (Map.Entry<PaperCard, Integer> e : deck.getOrCreate(DeckSection.Main)) {
            if (e.getKey().getRules().getType().isBasicLand()) {
                continue; //basics come from outside the collection, by design
            }
            usedByName.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        }
        assertFalse(usedByName.isEmpty(), "deck should use something from the collection");
        for (Map.Entry<String, Integer> e : usedByName.entrySet()) {
            assertTrue(e.getValue() <= ownedByName.getOrDefault(e.getKey(), 0),
                    "deck names " + e.getValue() + " " + e.getKey() + " but the collection holds "
                            + ownedByName.getOrDefault(e.getKey(), 0));
        }
    }

    @Test
    public void aGeneratedDeckIsLegalSizeAndReproducible() {
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);
        CardPool owned = pool.derivedPoolFor(marcy, 24);

        Deck first = new ChronicleDeckBuilder().buildFrom(owned, 99L, "Marcy");
        Deck second = new ChronicleDeckBuilder().buildFrom(owned, 99L, "Marcy");
        assertEquals(first.getOrCreate(DeckSection.Main).countAll(), ChronicleDeckBuilder.DECK_SIZE);
        assertEquals(deckDigest(second), deckDigest(first), "same pool and seed must give the same deck");

        Deck different = new ChronicleDeckBuilder().buildFrom(owned, 100L, "Marcy");
        assertFalse(deckDigest(different).equals(deckDigest(first)), "a different seed should build differently");
    }

    @Test
    public void aDeckIsBuildableFromAnEmptyCollection() {
        //a rival on day zero owns nothing; they still have to be playable
        Deck deck = new ChronicleDeckBuilder("LEA").buildFrom(new CardPool(), 7L, "Danny");
        assertEquals(deck.getOrCreate(DeckSection.Main).countAll(), ChronicleDeckBuilder.DECK_SIZE);
    }

    @Test
    public void basicLandsComeFromTheRequestedEra() {
        Deck deck = new ChronicleDeckBuilder("LEA").buildFrom(new CardPool(), 7L, "Danny");
        for (Map.Entry<PaperCard, Integer> e : deck.getOrCreate(DeckSection.Main)) {
            assertEquals(e.getKey().getEdition(), "LEA", "anachronistic basic land: " + e.getKey());
        }
    }

    // --- the purse ----------------------------------------------------------

    @Test
    public void thePursePaysOncePerPlayedDay() {
        ChronicleKitchen kitchen = new ChronicleKitchen();
        ChronicleConfig config = config();
        ChronicleRival marcy = roster().byId("marcy");
        long expected = ChronicleKitchen.purseCents(config, marcy);
        assertTrue(expected > 0);

        assertTrue(kitchen.purseAvailable(marcy, 5));
        ChronicleKitchen.Result win = kitchen.record(config, marcy, 5, "mine", true);
        assertEquals(win.purseCents, expected);
        assertTrue(win.paid);

        //the rematch is free to play and pays nothing
        assertFalse(kitchen.purseAvailable(marcy, 5));
        ChronicleKitchen.Result rematch = kitchen.record(config, marcy, 5, "mine", true);
        assertEquals(rematch.purseCents, 0);
        assertFalse(rematch.paid);

        //tomorrow it is back
        assertTrue(kitchen.purseAvailable(marcy, 6));
        assertEquals(kitchen.record(config, marcy, 6, "mine", true).purseCents, expected);
    }

    @Test
    public void aLossPaysNothingAndLeavesTheDayOpen() {
        ChronicleKitchen kitchen = new ChronicleKitchen();
        ChronicleConfig config = config();
        ChronicleRival marcy = roster().byId("marcy");

        ChronicleKitchen.Result loss = kitchen.record(config, marcy, 5, "mine", false);
        assertEquals(loss.purseCents, 0);
        assertFalse(loss.paid);
        assertTrue(kitchen.purseAvailable(marcy, 5), "losing must not burn the day's purse");
        assertEquals(kitchen.losses("marcy"), 1);

        assertEquals(kitchen.record(config, marcy, 5, "mine", true).purseCents,
                ChronicleKitchen.purseCents(config, marcy));
    }

    @Test
    public void aToughRivalPaysMore() {
        ChronicleConfig config = config();
        assertTrue(ChronicleKitchen.purseCents(config, roster().byId("vince"))
                > ChronicleKitchen.purseCents(config, roster().byId("kid")));
    }

    @Test
    public void unpaidTodayShrinksAsPursesAreCollected() {
        ChronicleKitchen kitchen = new ChronicleKitchen();
        ChronicleConfig config = config();
        List<ChronicleRival> active = roster().activeOn(21);
        assertEquals(kitchen.unpaidToday(active, 21).size(), 3);
        kitchen.record(config, roster().byId("marcy"), 21, "mine", true);
        assertEquals(kitchen.unpaidToday(active, 21).size(), 2);
    }

    @Test
    public void kitchenStateSurvivesSaveAndReload() {
        ChronicleKitchen kitchen = new ChronicleKitchen();
        ChronicleConfig config = config();
        kitchen.record(config, roster().byId("marcy"), 5, "mine", true);
        kitchen.record(config, roster().byId("kid"), 5, "mine", false);

        ChronicleSaveData data = kitchen.save();
        ChronicleKitchen reloaded = new ChronicleKitchen();
        reloaded.load(data);

        assertFalse(reloaded.purseAvailable(roster().byId("marcy"), 5), "collected purse must not come back");
        assertTrue(reloaded.purseAvailable(roster().byId("marcy"), 6));
        assertEquals(reloaded.wins("marcy"), 1);
        assertEquals(reloaded.losses("kid"), 1);
    }

    // --- player decks -------------------------------------------------------

    @Test
    public void decksBorrowRatherThanConsume() {
        ChronicleCollection collection = new ChronicleCollection();
        PaperCard bolt = card("Lightning Bolt");
        collection.add(bolt, 2);

        Deck a = new Deck("aggro");
        a.getOrCreate(DeckSection.Main).add(bolt, 2);
        Deck b = new Deck("burn");
        b.getOrCreate(DeckSection.Main).add(bolt, 2);

        //both decks name the same two copies, and the binder still holds them
        assertTrue(ChronicleDecks.isPlayable(a, collection));
        assertTrue(ChronicleDecks.isPlayable(b, collection));
        assertEquals(collection.count(bolt), 2);
    }

    @Test
    public void sellingBelowADecksNeedsReportsAShortfall() {
        ChronicleCollection collection = new ChronicleCollection();
        PaperCard bolt = card("Lightning Bolt");
        collection.add(bolt, 3);

        Deck deck = new Deck("burn");
        deck.getOrCreate(DeckSection.Main).add(bolt, 3);
        assertTrue(ChronicleDecks.isPlayable(deck, collection));

        collection.remove(bolt, 2);
        Map<PaperCard, Integer> missing = ChronicleDecks.shortfall(deck, collection);
        assertEquals(missing.size(), 1);
        assertEquals((int) missing.get(bolt), 2);
    }

    @Test
    public void basicLandsAreNeverAShortfall() {
        //no basics in 1993-94 boosters, so a deck's lands are never "owed"
        ChronicleCollection collection = new ChronicleCollection();
        Deck deck = new Deck("mono green");
        deck.getOrCreate(DeckSection.Main).add(card("Forest"), 24);
        assertTrue(ChronicleDecks.isPlayable(deck, collection));
    }

    @Test
    public void decksSurviveSaveAndReload() {
        ChronicleDecks decks = new ChronicleDecks();
        PaperCard bolt = card("Lightning Bolt");
        Deck deck = new Deck("burn");
        deck.getOrCreate(DeckSection.Main).add(bolt, 3);
        decks.put(deck);

        ChronicleDecks reloaded = new ChronicleDecks();
        reloaded.load(decks.save(), ChronicleController.cardDbResolver());
        assertEquals(reloaded.size(), 1);
        assertEquals(reloaded.get("burn").getOrCreate(DeckSection.Main).count(bolt), 3);
    }

    // --- ante ---------------------------------------------------------------

    @Test
    public void anteMovesCardsOffTheRivalsDerivedBase() {
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);
        ChronicleRivalLedger ledger = new ChronicleRivalLedger();

        CardPool before = pool.poolFor(marcy, 20, ledger);
        PaperCard taken = before.iterator().next().getKey();
        int held = before.count(taken);

        ledger.settle("marcy", java.util.Collections.singletonList(taken), java.util.Collections.emptyList());
        CardPool after = pool.poolFor(marcy, 20, ledger);
        assertEquals(after.count(taken), held - 1, "the rival should be down the card you won");
        assertEquals(after.countAll(), before.countAll() - 1);

        //and the seed-pure base is untouched — only the delta moved
        assertEquals(pool.derivedPoolFor(marcy, 20).count(taken), held);
    }

    @Test
    public void aStrippedRivalRecoversAsTheirAllowanceRollsIn() {
        //the catch-up mechanic that needed no mechanic: the derived term keeps
        //growing on schedule, so extraction can never outrun the release calendar
        ChronicleRival marcy = roster().byId("marcy");
        ChronicleRivalPool pool = new ChronicleRivalPool(ChronicleData.loadCalendar(), RUN_SEED);
        ChronicleRivalLedger ledger = new ChronicleRivalLedger();

        CardPool day20 = pool.poolFor(marcy, 20, ledger);
        List<PaperCard> looted = new ArrayList<>();
        int wanted = 10;
        for (Map.Entry<PaperCard, Integer> e : day20) {
            if (looted.size() >= wanted) {
                break;
            }
            looted.add(e.getKey());
        }
        ledger.settle("marcy", looted, java.util.Collections.emptyList());

        int strippedNow = pool.poolFor(marcy, 20, ledger).countAll();
        int laterAfterLoss = pool.poolFor(marcy, 40, ledger).countAll();
        assertTrue(laterAfterLoss > strippedNow, "twenty days of allowance should rebuild them");
        //but never past where they would have been anyway
        assertTrue(laterAfterLoss < pool.derivedPoolFor(marcy, 40).countAll() + 1,
                "recovery must not exceed the derived curve");
    }

    @Test
    public void cardsWonBackCancelRatherThanAccumulate() {
        //one card ping-ponging between binders must not grow both piles forever
        ChronicleRivalLedger ledger = new ChronicleRivalLedger();
        PaperCard bolt = card("Lightning Bolt");

        ledger.settle("marcy", java.util.Collections.singletonList(bolt), java.util.Collections.emptyList());
        assertEquals(ledger.lostBy("marcy").count(bolt), 1);

        ledger.settle("marcy", java.util.Collections.emptyList(), java.util.Collections.singletonList(bolt));
        assertEquals(ledger.lostBy("marcy").count(bolt), 0, "winning it back should cancel the loss");
        assertEquals(ledger.wonBy("marcy").count(bolt), 0, "and must not book a phantom gain");
        assertTrue(ledger.isEmpty());
    }

    @Test
    public void theLedgerSurvivesSaveAndReload() {
        ChronicleRivalLedger ledger = new ChronicleRivalLedger();
        PaperCard bolt = card("Lightning Bolt");
        PaperCard shivan = card("Shivan Dragon");
        ledger.settle("marcy", java.util.Collections.singletonList(bolt),
                java.util.Collections.singletonList(shivan));

        ChronicleRivalLedger reloaded = new ChronicleRivalLedger();
        reloaded.load(ledger.save(), ChronicleController.cardDbResolver());
        assertEquals(reloaded.lostBy("marcy").count(bolt), 1);
        assertEquals(reloaded.wonBy("marcy").count(shivan), 1);
    }

    @Test
    public void aRivalStopsPlayingForKeepsWhenTheyRunLow() {
        ChronicleConfig config = config();
        assertTrue(ChronicleKitchen.rivalWillAnte(config, config.anteRivalFloorCards));
        assertTrue(ChronicleKitchen.rivalWillAnte(config, config.anteRivalFloorCards + 50));
        assertFalse(ChronicleKitchen.rivalWillAnte(config, config.anteRivalFloorCards - 1),
                "a cleaned-out rival should decline rather than hand over their last playable");
    }

    @Test
    public void anteWinsAreRealAcquisitionsAndLossesAreRecorded() {
        ChronicleAcquisitionLog log = new ChronicleAcquisitionLog();
        PaperCard shivan = card("Shivan Dragon");
        PaperCard bolt = card("Lightning Bolt");

        log.recordAnteWon(12, "marcy", java.util.Collections.singletonList(shivan));
        assertTrue(log.firstAcquiredOrdinal(shivan) > 0,
                "a card won at ante can be the first copy you ever owned — it earns its NEW badge");
        assertEquals(log.eventsFor(shivan).get(0).kind, ChronicleAcquisitionLog.Source.ANTE_WON);
        assertEquals(log.eventsFor(shivan).get(0).origin, "marcy");

        log.recordAnteLost(12, "marcy", java.util.Collections.singletonList(bolt));
        assertEquals(log.firstAcquiredOrdinal(bolt), 0, "losing a card must not mint an acquisition ordinal");
        assertEquals(log.eventsFor(bolt).get(0).kind, ChronicleAcquisitionLog.Source.ANTE_LOST);
        assertFalse(ChronicleAcquisitionLog.Source.ANTE_LOST.isAcquisition());
    }

    @Test
    public void theJournalStillLoadsSavesWrittenBeforeAnteExisted() {
        //Source keeps SealedItem.Kind's names precisely so old records resolve
        ChronicleAcquisitionLog log = new ChronicleAcquisitionLog();
        log.record(3, forge.gamemodes.chronicle.SealedItem.Kind.BOOSTER, "LEA",
                java.util.Collections.singletonList(card("Lightning Bolt")));
        ChronicleAcquisitionLog reloaded = new ChronicleAcquisitionLog();
        reloaded.load(log.save());
        assertEquals(reloaded.all().get(0).kind, ChronicleAcquisitionLog.Source.BOOSTER);
        assertEquals(reloaded.all().get(0).origin, "LEA");
    }

    // --- the deck editor's catalog -------------------------------------------

    @Test
    public void theCatalogPoolIsEverythingYouOwn() {
        //"nothing in my inventory" (v18, found in play): this is the supplier the
        //editor's catalog reads, so it gets asserted where a test can reach it
        //rather than living in the screen where only play could catch it.
        ChronicleController controller = newLoadedController();
        controller.newRun(RUN_SEED);
        controller.collectRation(controller.rationChoices().get(0).editionCode);
        for (SealedItem item : new ArrayList<>(controller.getRun().sealed.all())) {
            controller.openSealed(item.itemId);
        }

        CardPool pool = controller.collectionAsPool();
        assertTrue(pool.countAll() > 0, "opening the daily ration must put cards in the catalog pool");
        assertEquals(pool.countAll(), controller.getRun().collection.totalCopies());
        assertEquals(pool.countDistinct(), controller.getRun().collection.distinctOwned());
        for (Map.Entry<PaperCard, Integer> e : controller.getRun().collection.entries()) {
            assertEquals(pool.count(e.getKey()), (int) e.getValue(), "catalog lost copies of " + e.getKey());
        }
    }

    @Test
    public void theCatalogPoolIsACopyNotTheCollection() {
        //the editor mutates the pool it is handed; that must not reach the binder
        ChronicleController controller = newLoadedController();
        controller.newRun(RUN_SEED);
        controller.collectRation(controller.rationChoices().get(0).editionCode);
        for (SealedItem item : new ArrayList<>(controller.getRun().sealed.all())) {
            controller.openSealed(item.itemId);
        }

        CardPool pool = controller.collectionAsPool();
        int before = controller.getRun().collection.totalCopies();
        PaperCard any = pool.iterator().next().getKey();
        pool.remove(any, 1);
        assertEquals(controller.getRun().collection.totalCopies(), before,
                "editing the catalog pool must not touch the collection");
    }

    private static ChronicleController newLoadedController() {
        ChronicleConfig cfg = ChronicleData.loadConfig();
        return new ChronicleController(ChronicleData.loadCalendar(), cfg, ChronicleData.loadPricing(cfg),
                ChronicleData.loadRoster(), ChronicleController.cardDbResolver(), java.time.Clock.systemDefaultZone());
    }

    // --- helpers ------------------------------------------------------------

    private static PaperCard card(String name) {
        return forge.StaticData.instance().getCommonCards().getUniqueByName(name);
    }

    private static String digest(CardPool pool) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> e : pool) {
            lines.add(e.getKey().getName() + "|" + e.getKey().getEdition() + "|" + e.getValue());
        }
        java.util.Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static String deckDigest(Deck deck) {
        return digest(deck.getOrCreate(DeckSection.Main));
    }
}
