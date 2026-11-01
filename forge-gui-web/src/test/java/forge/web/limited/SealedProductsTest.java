package forge.web.limited;

import forge.deck.CardPool;
import forge.gamemodes.limited.SealedCardPoolGenerator;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.web.WebTestSupport;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/** Tests forge-gui's sealed pool builder as the web calls it without desktop's prompts, and the sealed lists the web offers. */
public class SealedProductsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** Fails if a combo is parsed differently from desktop's prompted path. */
    @Test
    public void createBlockUsesTheComboSets() {
        final CardBlock innistrad = FModel.getBlocks().get("Innistrad");
        final String combo = SealedCardPoolGenerator.blockCombos(innistrad).stream()
                .filter(c -> c.contains("ISD") && c.contains("DKA")).findFirst().orElseThrow();
        final List<String> codes = new ArrayList<>();
        for (final String part : combo.split(",")) {
            final String[] pieces = part.trim().split(" ");
            codes.add(pieces[pieces.length - 1]);
        }
        final CardPool pool = SealedCardPoolGenerator.createBlock(innistrad, combo).getCardPool(false);
        assertTrue(pool.countAll() > 0);
        for (final Map.Entry<PaperCard, Integer> e : pool) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                assertTrue(codes.contains(e.getKey().getEdition()), e.getKey() + " is not from " + combo);
            }
        }
    }

    /** Fails if the web offers a block, or a combo, that desktop's sealed dialog does not. */
    @Test
    public void sealedListsMatchDesktop() {
        final DraftProducts.SealedLists lists = DraftProducts.sealed();
        int offered = 0;
        for (final CardBlock b : FModel.getBlocks()) {
            try {
                if (!SealedCardPoolGenerator.blockCombos(b).isEmpty()) {
                    offered++;
                }
            } catch (final RuntimeException e) {
                // Desktop fails on such a block, so the web leaves it out
            }
        }
        assertEquals(lists.blocks().size(), offered);
        final DraftProducts.Block innistrad = lists.blocks().stream().filter(b -> b.name().equals("Innistrad")).findFirst().orElseThrow();
        final CardBlock desktop = FModel.getBlocks().get("Innistrad");
        assertEquals(innistrad.combos(), SealedCardPoolGenerator.blockCombos(desktop));
        assertEquals(innistrad.packs(), desktop.getCntBoostersSealed());
    }
}
