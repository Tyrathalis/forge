package forge.ai.anvil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The answer tape's line format (Anvil M13 Build 1): what TapeBridge writes,
 * ReplayBridge must read back as the same answer.
 */
public class AnswerTapeTest {

    @Test
    public void planRoundTrips() {
        CastPlanAnswer a = new CastPlanAnswer(3, true, Arrays.asList(
                new CastPlanAnswer.Ref(true, 1, -1, false),
                new CastPlanAnswer.Ref(false, -1, 120, false),
                new CastPlanAnswer.Ref(false, -1, 45, true)), true, 7);
        CastPlanAnswer b = AnswerTape.plan(AnswerTape.plan("mtg.priority", a).split("\t", -1));
        Assert.assertEquals(b.optionIndex, 3);
        Assert.assertTrue(b.hostLevel);
        Assert.assertTrue(b.hasX);
        Assert.assertEquals(b.x, 7);
        Assert.assertEquals(b.targets.size(), 3);
        Assert.assertTrue(b.targets.get(0).isPlayer);
        Assert.assertEquals(b.targets.get(0).player, 1);
        Assert.assertEquals(b.targets.get(1).entity, 120);
        Assert.assertFalse(b.targets.get(1).onStack);
        Assert.assertTrue(b.targets.get(2).onStack);
        Assert.assertEquals(b.targets.get(2).entity, 45);
    }

    @Test
    public void passAndNullPlansDiffer() {
        CastPlanAnswer pass = new CastPlanAnswer(0, false, java.util.Collections.emptyList(), false, 0);
        CastPlanAnswer back = AnswerTape.plan(AnswerTape.plan("t", pass).split("\t", -1));
        Assert.assertNotNull(back);
        Assert.assertEquals(back.optionIndex, 0);
        Assert.assertTrue(back.targets.isEmpty());
        Assert.assertNull(AnswerTape.plan(AnswerTape.plan("t", null).split("\t", -1)));
    }

    @Test
    public void combatMapRoundTrips() {
        CombatMapAnswer a = new CombatMapAnswer(Arrays.asList(
                new CombatMapAnswer.Assignment(new CastPlanAnswer.Ref(false, -1, 9, false),
                        new CastPlanAnswer.Ref(true, 1, -1, false)),
                new CombatMapAnswer.Assignment(new CastPlanAnswer.Ref(false, -1, 10, false),
                        new CastPlanAnswer.Ref(false, -1, 77, false))));
        CombatMapAnswer b = AnswerTape.combat(AnswerTape.combat("atk", "mtg.attack", a).split("\t", -1));
        Assert.assertEquals(b.assignments.size(), 2);
        Assert.assertEquals(b.assignments.get(0).left.entity, 9);
        Assert.assertTrue(b.assignments.get(0).right.isPlayer);
        Assert.assertEquals(b.assignments.get(1).right.entity, 77);
        // the empty map (declare nothing) is not the unanswered shape
        CombatMapAnswer empty = AnswerTape.combat(AnswerTape.combat("blk", "t", CombatMapAnswer.empty()).split("\t", -1));
        Assert.assertNotNull(empty);
        Assert.assertTrue(empty.assignments.isEmpty());
        Assert.assertNull(AnswerTape.combat(AnswerTape.combat("blk", "t", null).split("\t", -1)));
    }

    @Test
    public void indexListsKeepNullAndEmptyApart() {
        Assert.assertNull(AnswerTape.ints(AnswerTape.csv(null)));
        Assert.assertEquals(AnswerTape.ints(AnswerTape.csv(new int[0])).length, 0);
        Assert.assertEquals(AnswerTape.ints(AnswerTape.csv(new int[] { 2, 0, 5 })), new int[] { 2, 0, 5 });
    }

    @Test
    public void searchVerdictRoundTrips() {
        String label = "Choose one —\r\n• Destroy\ttarget \\ creature";
        AnswerTape.Search a = new AnswerTape.Search(1, AnswerTape.Search.ACT, label, 2, 1, new int[] { 0, 3 }, "");
        String line = a.line();
        Assert.assertEquals(line.indexOf('\n'), -1);
        Assert.assertEquals(line.indexOf('\r'), -1);
        AnswerTape.Search b = AnswerTape.Search.parse(line.split("\t", -1));
        Assert.assertEquals(b.seat, 1);
        Assert.assertEquals(b.mode, AnswerTape.Search.ACT);
        Assert.assertEquals(b.label, label);
        Assert.assertEquals(b.armKind, 2);
        Assert.assertEquals(b.armOrdinal, 1);
        Assert.assertEquals(b.armAnswer, new int[] { 0, 3 });
        Assert.assertEquals(b.armLabel, ""); // an empty guard label is not "no guard"

        AnswerTape.Search noGuard = AnswerTape.Search.parse(
                new AnswerTape.Search(0, AnswerTape.Search.NAT, null, 0, 0, new int[0], null).line().split("\t", -1));
        Assert.assertEquals(noGuard.armKind, 0);
        Assert.assertEquals(noGuard.armAnswer.length, 0);
        Assert.assertNull(noGuard.armLabel);

        AnswerTape.Search pass = AnswerTape.Search.parse(
                new AnswerTape.Search(0, AnswerTape.Search.PASS, null, -1, 0, null, null).line().split("\t", -1));
        Assert.assertEquals(pass.armKind, -1);
        Assert.assertEquals(pass.label, "");
    }

    @Test
    public void replayBridgeAnswersInOrderAndNamesADivergence() throws Exception {
        Path f = Files.createTempFile("tape", ".tsv");
        Files.write(f, Arrays.asList(
                "G\t7\t42\tmtg.priority,mtg.attack",
                "set\tmtg.surface.order\t1,0",
                "plan\tmtg.priority\t-",
                "srch\t0\tpass\t\t-\t",
                "atk\tmtg.attack\t"), StandardCharsets.UTF_8);
        Map<Integer, AnswerTape.Game> games = AnswerTape.read(f.toString());
        Files.delete(f);
        Assert.assertEquals(games.get(7).tags.size(), 2);
        ReplayBridge rb = new ReplayBridge(games);
        rb.gameStart("g7", 42L);
        Assert.assertEquals(rb.order("mtg.surface.order", Arrays.asList("a", "b")), new int[] { 1, 0 });
        Assert.assertNull(rb.takeSearch(0)); // the next entry is not a verdict
        Assert.assertNull(rb.priorityCastPlan("mtg.priority", Arrays.asList("pass"), null));
        Assert.assertEquals(rb.takeSearch(0).mode, AnswerTape.Search.PASS);
        try {
            rb.blockMap("mtg.block", null);
            Assert.fail("a block ask against an attack entry must diverge");
        } catch (ReplayBridge.TapeDivergedException expected) {
            Assert.assertNotNull(rb.diverged());
        }
        Assert.assertEquals(rb.used(), 3);
        Assert.assertEquals(rb.length(), 4);
    }
}
