package forge.view;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

/**
 * Anvil, the certifier merge (09-23, ADR-0117): the -replay jobs contract —
 * CensusRun -certify's flat job line plus seat / profiles / phase — parsed
 * by {@link AnvilRun.ReplayJob}. Pure parsing; the fork point and the copies
 * are smoked on real games (the merge's parity smoke), not here.
 */
public class ReplayJobTest {

    private static Map<String, String> flat(String line) {
        return CensusRun.flatJson(line);
    }

    /** An M9 job of record parses as CensusRun would read it: the seat off
     *  the census name, the defaults for the optional fields, no profiles. */
    @Test
    public void testM9JobDefaults() {
        AnvilRun.ReplayJob j = new AnvilRun.ReplayJob(flat(
                "{\"job\": 7, \"seed\": 20264708, \"deck1\": \"dc-864562.dck\", \"deck2\": \"dc-864787.dck\", "
                + "\"p\": \"Census(2)-dc-864787\", \"t\": 17, \"sa\": \"Aragorn, King of Gondor - Creature 4 / 4\", "
                + "\"ord\": 0, \"arms\": 5, \"k\": 8, \"horizon\": 2}"));
        AssertJUnit.assertEquals(7, j.job);
        AssertJUnit.assertEquals(20264708L, j.seed);
        AssertJUnit.assertEquals(1, j.seat);
        AssertJUnit.assertEquals(17, j.t);
        AssertJUnit.assertEquals(5, j.arms);
        AssertJUnit.assertEquals(5, j.maxArm());
        AssertJUnit.assertEquals(8, j.k);
        AssertJUnit.assertEquals(19, j.leafAfter(17));
        AssertJUnit.assertFalse(j.observe);
        AssertJUnit.assertNull(j.profiles);
        AssertJUnit.assertNull(j.ph);
    }

    /** The merge's additions: an explicit seat wins over the name, the
     *  coordinate's profiles and phase ride along, observe = arm 0 only, a
     *  negative horizon = the outcome leaf. */
    @Test
    public void testReplayAdditions() {
        AnvilRun.ReplayJob j = new AnvilRun.ReplayJob(flat(
                "{\"job\": 0, \"seed\": 1, \"deck1\": \"a.dck\", \"deck2\": \"b.dck\", \"p\": \"Heur(2)-b\", "
                + "\"seat\": 0, \"t\": 5, \"sa\": \"x\", \"arms\": 3, \"k\": 2, \"horizon\": -1, "
                + "\"mode\": \"observe\", \"profile1\": \"Reckless\", \"profile2\": \"Default\", \"ph\": \"MAIN2\"}"));
        AssertJUnit.assertEquals(0, j.seat);
        AssertJUnit.assertTrue(j.observe);
        AssertJUnit.assertEquals(0, j.maxArm());
        AssertJUnit.assertEquals(Integer.MAX_VALUE, j.leafAfter(5));
        AssertJUnit.assertEquals("Reckless", j.profiles[0]);
        AssertJUnit.assertEquals("Default", j.profiles[1]);
        AssertJUnit.assertEquals("MAIN2", j.ph);
    }

    /** The seat off a player name of either runner's shape; 0 when absent. */
    @Test
    public void testSeatOf() {
        AssertJUnit.assertEquals(0, AnvilRun.ReplayJob.seatOf("Census(1)-dc-864792"));
        AssertJUnit.assertEquals(1, AnvilRun.ReplayJob.seatOf("Anvil(2)-dc-864792"));
        AssertJUnit.assertEquals(1, AnvilRun.ReplayJob.seatOf("Heur(2)-dc-864792"));
        AssertJUnit.assertEquals(0, AnvilRun.ReplayJob.seatOf("nobody"));
        AssertJUnit.assertEquals(0, AnvilRun.ReplayJob.seatOf(null));
    }

    /** A jobs file reads in order, blank lines skipped. */
    @Test
    public void testReadJobsFile() throws Exception {
        Path f = Files.createTempFile("replay-jobs", ".jsonl");
        Files.write(f, List.of(
                "{\"job\": 3, \"seed\": 9, \"deck1\": \"a.dck\", \"deck2\": \"b.dck\", \"p\": \"Census(1)-a\", \"t\": 4, \"sa\": \"s\"}",
                "",
                "{\"job\": 5, \"seed\": 10, \"deck1\": \"a.dck\", \"deck2\": \"b.dck\", \"seat\": 1, \"t\": 6, \"sa\": \"s\"}"),
                StandardCharsets.UTF_8);
        List<AnvilRun.ReplayJob> jobs = AnvilRun.ReplayJob.read(f.toString());
        AssertJUnit.assertEquals(2, jobs.size());
        AssertJUnit.assertEquals(3, jobs.get(0).job);
        AssertJUnit.assertEquals(0, jobs.get(0).seat);
        AssertJUnit.assertEquals(5, jobs.get(1).job);
        AssertJUnit.assertEquals(1, jobs.get(1).seat);
        AssertJUnit.assertEquals(8, jobs.get(1).arms); // the M9 default
        AssertJUnit.assertEquals(2, jobs.get(1).horizon);
        Files.delete(f);
    }
}
