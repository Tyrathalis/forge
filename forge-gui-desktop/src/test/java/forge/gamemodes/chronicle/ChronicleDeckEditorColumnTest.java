package forge.gamemodes.chronicle;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.itemmanager.ColumnDef;
import forge.itemmanager.ItemColumn;
import forge.itemmanager.ItemManagerConfig;

/**
 * Regression for the D6 deck-editor crash (found in play, 2026-08-23).
 *
 * ItemManagerConfig.QUEST_EDITOR_POOL sorts on ColumnDef.NEW, and NEW ships with
 * null sort/display functions — "functions will be set later". Every consumer
 * must supply them through getColOverrides or ItemColumn's constructor throws
 * before a single card is drawn. Quest and Adventure both do; Chronicle's first
 * deck editor did not, and opening it crashed instantly.
 *
 * Extends AITest because ItemManagerConfig's static initializer needs the model
 * up — the reason the first attempt at this test blew up in its own initializer.
 */
public class ChronicleDeckEditorColumnTest extends AITest {

    @Test
    public void newColumnStillHasNoFunctionsOfItsOwn() {
        //the premise. If upstream ever gives NEW real defaults, this fails loudly
        //and Chronicle's override becomes optional rather than load-bearing.
        try {
            new ItemColumn(ItemManagerConfig.QUEST_EDITOR_POOL.getCols().get(ColumnDef.NEW));
            fail("ColumnDef.NEW now has built-in functions — the Chronicle override may be redundant");
        } catch (NullPointerException expected) {
            //"A sort function hasn't been set for column New" — the crash, exactly
        }
    }

    @Test
    public void anOverrideIsWhatMakesTheColumnConstructible() {
        Map<ColumnDef, ItemColumn> colOverrides = new HashMap<>();
        ItemColumn.addColOverride(ItemManagerConfig.QUEST_EDITOR_POOL, colOverrides, ColumnDef.NEW,
                from -> 0L, from -> "");
        assertNotNull(colOverrides.get(ColumnDef.NEW));
        assertNotNull(colOverrides.get(ColumnDef.NEW).getFnSort());
    }
}
