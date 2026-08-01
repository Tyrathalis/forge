package forge.gamemodes.chronicle;

import static org.testng.Assert.assertTrue;

import java.util.List;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.item.PaperCard;

/**
 * The Ante pack-EV ledger's own audit: the exact sheet-computed EV must obey
 * the pack-EV-negative invariant against the shipped price tables (including
 * at the maximum LGS discount), and must agree with the empirical mean of
 * actually-generated packs — the ledger is downstream-verified against the
 * generator it describes.
 */
public class ChroniclePackEvTest extends AITest {

    /** Deepest LGS discount the stock roll can produce (see ChronicleLgs: 0-15% off). */
    private static final int MAX_DISCOUNT_PERCENT = 15;

    @Test
    public void packEvIsNegativeForEveryShippedProductEvenAtMaxDiscount() {
        ChronicleCalendar calendar = ChronicleData.loadCalendar();
        ChronicleConfig config = ChronicleData.loadConfig();
        ChroniclePackEv ev = new ChroniclePackEv(ChronicleData.loadPricing(config));

        for (ChronicleRelease release : calendar.all()) {
            long boosterEv = ev.evCents(SealedItem.Kind.BOOSTER, release.editionCode);
            long floorPrice = release.boosterCents * (100 - MAX_DISCOUNT_PERCENT) / 100;
            assertTrue(boosterEv < floorPrice,
                    release.editionCode + " booster EV " + boosterEv + " must stay below the discounted price " + floorPrice);

            // Boxes are materialized boosters: the per-pack floor price of a box must also beat EV.
            long boxPerPack = release.boxCents * (100 - MAX_DISCOUNT_PERCENT) / 100 / release.packsPerBox;
            assertTrue(boosterEv < boxPerPack,
                    release.editionCode + " booster EV " + boosterEv + " must stay below the per-pack box price " + boxPerPack);

            if (release.hasStarter()) {
                long starterEv = ev.evCents(SealedItem.Kind.STARTER, release.editionCode);
                long starterFloor = release.starterCents * (100 - MAX_DISCOUNT_PERCENT) / 100;
                assertTrue(starterEv < starterFloor,
                        release.editionCode + " starter EV " + starterEv + " must stay below " + starterFloor);
            }
        }
    }

    @Test
    public void exactEvAgreesWithGeneratedPackMean() {
        ChronicleConfig config = ChronicleData.loadConfig();
        ChroniclePricing pricing = ChronicleData.loadPricing(config);
        ChroniclePackEv ev = new ChroniclePackEv(pricing);

        for (String edition : new String[] { "LEA", "ARN", "FEM" }) {
            long exact = ev.evCents(SealedItem.Kind.BOOSTER, edition);
            int samples = 400;
            long total = 0;
            for (int i = 0; i < samples; i++) {
                // Fixed seeds: this is a deterministic cross-check, not a statistical one.
                SealedItem item = new SealedItem(i, SealedItem.Kind.BOOSTER, edition,
                        ChronicleSeeds.deriveItem(20260731L, "ev-audit", i), 0);
                List<PaperCard> pack = ChroniclePackGenerator.open(item);
                for (PaperCard card : pack) {
                    total += pricing.buylistCents(card);
                }
            }
            double mean = total / (double) samples;
            double tolerance = Math.max(30, exact * 0.25);
            assertTrue(Math.abs(mean - exact) <= tolerance,
                    edition + ": sampled mean " + mean + " vs exact EV " + exact + " (tolerance " + tolerance + ")");
        }
    }
}
