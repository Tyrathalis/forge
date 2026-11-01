package forge.web.limited;

import forge.deck.CardPool;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.LimitedPoolType;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.model.FModel;
import forge.web.WebTestSupport;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.Map;

import static org.testng.Assert.assertEquals;

/** Tests the forge-gui draft builders that the web calls with the choices desktop would prompt for. */
public class DraftProductsTest {
    @BeforeClass
    public void setUp() {
        WebTestSupport.initModel();
    }

    /** Fails if a full draft prompts, or builds a product other than three generic packs. */
    @Test
    public void createFullDraftDealsThreeGenericPacks() {
        final BoosterDraft draft = BoosterDraft.createFullDraft();
        assertEquals(draft.getNumRounds(), 3);
        draft.initializeBoosters();
        final CardPool pack = draft.nextChoice();
        assertEquals(pack.countAll(), 15);
    }

    /** Fails if a combo is parsed differently from desktop's prompted path. */
    @Test
    public void createBlockDraftUsesTheComboSets() {
        final CardBlock innistrad = FModel.getBlocks().get("Innistrad");
        final BoosterDraft draft = BoosterDraft.createBlockDraft(LimitedPoolType.Block, innistrad, "ISD/ISD/DKA");
        assertEquals(draft.getNumRounds(), 3);
        draft.initializeBoosters();
        for (final Map.Entry<PaperCard, Integer> e : draft.nextChoice()) {
            if (!e.getKey().getRules().getType().isBasicLand()) {
                assertEquals(e.getKey().getEdition(), "ISD", e.getKey() + " is not from the first pack's set");
            }
        }
    }
}
