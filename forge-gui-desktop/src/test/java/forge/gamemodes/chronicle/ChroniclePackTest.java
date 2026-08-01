package forge.gamemodes.chronicle;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.Test;

import forge.StaticData;
import forge.ai.AITest;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;

/**
 * Card-DB-backed tests: pack generation behind the committed-seed wrapper,
 * and the collection service against real printings. Rides AITest's one-time
 * card DB load.
 */
public class ChroniclePackTest extends AITest {

    private static String contentsDigest(List<PaperCard> cards) {
        StringBuilder sb = new StringBuilder();
        for (PaperCard card : cards) {
            sb.append(card.getName()).append('#').append(card.getEdition()).append('#')
              .append(card.getArtIndex()).append('#').append(card.isFoil()).append(';');
        }
        return sb.toString();
    }

    @Test
    public void sameSeedSamePackDifferentSeedDifferentPack() {
        SealedItem item = new SealedItem(1, SealedItem.Kind.BOOSTER, "LEA", 123456789L, 0);
        String first = contentsDigest(ChroniclePackGenerator.open(item));
        String second = contentsDigest(ChroniclePackGenerator.open(item));
        assertEquals(first, second, "committed seed must reproduce the identical pack");

        SealedItem other = new SealedItem(2, SealedItem.Kind.BOOSTER, "LEA", 987654321L, 0);
        assertNotEquals(contentsDigest(ChroniclePackGenerator.open(other)), first,
                "different seeds must (essentially always) differ");
    }

    @Test
    public void packSizesAreEraAuthentic() {
        assertEquals(ChroniclePackGenerator.open(new SealedItem(1, SealedItem.Kind.BOOSTER, "LEA", 1L, 0)).size(), 15,
                "core-set boosters are 15 cards");
        assertEquals(ChroniclePackGenerator.open(new SealedItem(2, SealedItem.Kind.BOOSTER, "ARN", 2L, 0)).size(), 8,
                "Arabian Nights shipped 8-card boosters");
        assertEquals(ChroniclePackGenerator.open(new SealedItem(3, SealedItem.Kind.BOOSTER, "FEM", 3L, 0)).size(), 8,
                "Fallen Empires shipped 8-card boosters");
        assertEquals(ChroniclePackGenerator.open(new SealedItem(4, SealedItem.Kind.STARTER, "LEA", 4L, 0)).size(), 60,
                "starter decks are 60 cards");
    }

    @Test
    public void allCalendarProductsHaveTemplates() {
        ChronicleCalendar calendar = ChronicleData.loadCalendar();
        for (ChronicleRelease release : calendar.all()) {
            ChroniclePackGenerator.templateFor(SealedItem.Kind.BOOSTER, release.editionCode);
            if (release.hasStarter()) {
                ChroniclePackGenerator.templateFor(SealedItem.Kind.STARTER, release.editionCode);
            }
        }
    }

    @Test
    public void shippedNotablesAllResolveInTheCardDb() {
        ChronicleConfig config = ChronicleData.loadConfig();
        ChroniclePricing pricing = ChronicleData.loadPricing(config);
        // Every name in the shipped tier table must exist in the DB — a typo here
        // would silently price the game's best pull as bulk.
        for (String name : ChroniclePricing.parseNotables(
                forge.util.FileUtil.readFile(forge.localinstance.properties.ForgeConstants.CHRONICLE_DATA_DIR
                        + ChronicleData.NOTABLES_FILE)).keySet()) {
            PaperCard card = StaticData.instance().getCommonCards().getCard(name);
            assertTrue(card != null, "notable not in card DB: " + name);
            assertTrue(pricing.isNotable(name));
            assertTrue(pricing.buylistCents(card) > config.buylistBaseCents.get(forge.card.CardRarity.Common),
                    "notable must price above common base: " + name);
        }
    }

    @Test
    public void collectionRoundtripPreservesCountsAndNewFlags() {
        ChronicleCollection collection = new ChronicleCollection();
        List<PaperCard> pack = ChroniclePackGenerator.open(new SealedItem(1, SealedItem.Kind.BOOSTER, "LEA", 42L, 0));
        collection.addAll(pack);
        assertEquals(collection.totalCopies(), 15);
        assertTrue(collection.newCount() > 0, "fresh pulls carry the NEW badge");

        PaperCard seen = pack.get(0);
        collection.markSeen(seen);
        assertFalse(collection.isNew(seen));

        // Re-acquiring a seen printing is not NEW again.
        collection.add(seen, 1);
        assertFalse(collection.isNew(seen), "clear-on-seen is per-card and persistent");

        ChronicleCollection loaded = new ChronicleCollection();
        loaded.load(collection.save(), ChronicleController.cardDbResolver());
        assertEquals(loaded.totalCopies(), collection.totalCopies());
        assertEquals(loaded.distinctOwned(), collection.distinctOwned());
        assertEquals(loaded.newCount(), collection.newCount());
        assertFalse(loaded.isNew(seen), "seen-set survives reload");
        for (Map.Entry<PaperCard, Integer> entry : collection.entries()) {
            assertEquals(loaded.count(entry.getKey()), (int) entry.getValue());
        }
    }

    @Test
    public void completionCountsDistinctPrintingsAgainstSetUniverse() {
        List<PaperCard> universe = new ArrayList<>(
                StaticData.instance().getCommonCards().getAllCards(PaperCardPredicates.printedInSet("ARN")));
        assertTrue(universe.size() >= 78, "ARN universe should carry at least its 78 distinct names");

        ChronicleCollection collection = new ChronicleCollection();
        List<PaperCard> pack = ChroniclePackGenerator.open(new SealedItem(1, SealedItem.Kind.BOOSTER, "ARN", 7L, 0));
        collection.addAll(pack);

        int[] completion = collection.completion(universe);
        assertTrue(completion[0] > 0, "an opened pack must advance completion");
        assertTrue(completion[0] <= 8, "8-card pack advances by at most 8 distinct printings");
        assertEquals(completion[1], universe.size());
    }

    @Test
    public void acquisitionLogRecordsProvenanceAndSurvivesReload() {
        ChronicleAcquisitionLog log = new ChronicleAcquisitionLog();
        List<PaperCard> pack1 = ChroniclePackGenerator.open(new SealedItem(1, SealedItem.Kind.BOOSTER, "LEA", 11L, 0));
        List<PaperCard> pack2 = ChroniclePackGenerator.open(new SealedItem(2, SealedItem.Kind.BOOSTER, "ARN", 22L, 3));
        log.record(0, SealedItem.Kind.BOOSTER, "LEA", pack1);
        log.record(3, SealedItem.Kind.BOOSTER, "ARN", pack2);

        assertEquals(log.all().size(), 2);
        PaperCard firstPull = pack1.get(0);
        assertEquals(log.eventsFor(firstPull).get(0).dayIndex, 0, "provenance: the pull's day is recorded");
        assertEquals(log.firstAcquiredOrdinal(firstPull), 1, "the run's first-ever pull has ordinal 1");
        assertTrue(log.firstAcquiredOrdinal(pack2.get(0)) > log.firstAcquiredOrdinal(firstPull),
                "later first-pulls get later ordinals (the true opening-order sort key)");

        ChronicleAcquisitionLog loaded = new ChronicleAcquisitionLog();
        loaded.load(log.save());
        assertEquals(loaded.all().size(), 2);
        assertEquals(loaded.firstAcquiredOrdinal(firstPull), log.firstAcquiredOrdinal(firstPull));
        assertEquals(loaded.eventsFor(firstPull).size(), log.eventsFor(firstPull).size());
    }

    @Test
    public void sourcesForAnswersWhereACardCanBePulled() {
        ChronicleCalendar calendar = ChronicleData.loadCalendar();
        PaperCard arnCommon = ChronicleData.setUniverse("ARN").get(0);
        assertEquals(ChronicleAcquisitionLog.sourcesFor(arnCommon, calendar),
                java.util.Arrays.asList(SealedItem.Kind.BOOSTER), "ARN has no starter: booster only");

        PaperCard leaPlains = StaticData.instance().getCommonCards().getCard("Plains", "LEA");
        assertTrue(leaPlains != null);
        assertEquals(ChronicleAcquisitionLog.sourcesFor(leaPlains, calendar),
                java.util.Arrays.asList(SealedItem.Kind.STARTER),
                "basic lands never appear in Forge's era booster sheets: starter only");

        PaperCard leaRare = StaticData.instance().getCommonCards().getCard("Black Lotus", "LEA");
        assertEquals(ChronicleAcquisitionLog.sourcesFor(leaRare, calendar),
                java.util.Arrays.asList(SealedItem.Kind.BOOSTER, SealedItem.Kind.STARTER));
    }

    @Test
    public void setUniverseResolvesVariantPrintingsInCollectorOrder() {
        List<PaperCard> atq = ChronicleData.setUniverse("ATQ");
        assertTrue(atq.size() >= 100, "ATQ carries 100 printings incl. a/b/c/d art variants, got " + atq.size());

        // The split-rarity gotcha: Urza's Mine prints at both Uncommon (83a/b) and
        // Common (83c/d) — all four must be distinct entries in the universe.
        int urzasMines = 0;
        for (PaperCard card : atq) {
            if (card.getName().equals("Urza's Mine")) {
                urzasMines++;
            }
        }
        assertEquals(urzasMines, 4, "all four Urza's Mine art variants must be distinct printings");

        // Collector order, not name order: spot-check monotonic sortable collector numbers.
        List<PaperCard> arn = ChronicleData.setUniverse("ARN");
        assertTrue(arn.size() >= 78);
        String prev = null;
        for (PaperCard card : arn) {
            String sortable = forge.card.CardEdition.getSortableCollectorNumber(card.getCollectorNumber());
            if (prev != null) {
                assertTrue(sortable.compareTo(prev) >= 0,
                        "ARN universe must be in collector order: " + prev + " then " + sortable);
            }
            prev = sortable;
        }
    }

    @Test
    public void sealedInventoryCommitsSeedsAtAcquisitionAndSurvivesReload() {
        ChronicleSealedInventory inventory = new ChronicleSealedInventory();
        List<SealedItem> bought = inventory.acquire(555L, SealedItem.Kind.BOOSTER, "LEB", 2, 3);
        assertEquals(inventory.size(), 3);
        assertNotEquals(bought.get(0).contentsSeed, bought.get(1).contentsSeed, "each item commits its own seed");

        String preReload = contentsDigest(ChroniclePackGenerator.open(bought.get(1)));

        ChronicleSealedInventory loaded = new ChronicleSealedInventory();
        loaded.load(inventory.save());
        assertEquals(loaded.size(), 3);
        SealedItem reloaded = loaded.get(bought.get(1).itemId);
        assertEquals(reloaded.contentsSeed, bought.get(1).contentsSeed, "committed seed survives reload");
        assertEquals(contentsDigest(ChroniclePackGenerator.open(reloaded)), preReload,
                "reload can never reroll a pull (seed-integrity invariant)");

        // ids never recycle after a take()
        loaded.take(bought.get(0).itemId);
        SealedItem next = loaded.acquire(555L, SealedItem.Kind.BOOSTER, "LEB", 3);
        assertEquals(next.itemId, 4, "monotonic ids continue after reload and opening");
    }
}
