package forge.ai.simulation;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.anvil.Surfaces;

/**
 * Anvil M12 Build 3 (ADR-0103): the surface enumerators are pure index
 * functions — natural first, deduplicated, capped, seeded, every answer
 * well-formed for its shape.
 */
public class SurfacesEnumerateTest {

    private static int[] a(int... v) {
        return v;
    }

    private static Set<String> keys(List<int[]> l) {
        Set<String> s = new HashSet<>();
        for (int[] x : l) {
            s.add(java.util.Arrays.toString(x));
        }
        return s;
    }

    @Test
    public void entityOneEnumeratesEverySingleUnderCap() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_ONE, 5, 1, 1, a(3), null, 12, new Random(1));
        AssertJUnit.assertEquals(5, r.size());
        AssertJUnit.assertEquals("[3]", java.util.Arrays.toString(r.get(0)));
        AssertJUnit.assertEquals(5, keys(r).size());
    }

    @Test
    public void payEnumeratesAutoAndEveryGoalNaturalFirst() {
        // evening 4: {auto} ∪ goals, one pick; the natural (auto = 0) first
        List<int[]> r = Surfaces.enumerate(Surfaces.PAY, 4, 1, 1, a(0), null, 12, new Random(1));
        AssertJUnit.assertEquals(4, r.size());
        AssertJUnit.assertEquals("[0]", java.util.Arrays.toString(r.get(0)));
        AssertJUnit.assertEquals(4, keys(r).size());
        AssertJUnit.assertTrue(Surfaces.nontrivial(Surfaces.PAY, 2, 1));
        AssertJUnit.assertFalse(Surfaces.nontrivial(Surfaces.PAY, 1, 1));
    }

    @Test
    public void entityOneCapsWithNaturalFirst() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_ONE, 60, 1, 1, a(41), null, 8, new Random(7));
        AssertJUnit.assertEquals(8, r.size());
        AssertJUnit.assertEquals(41, r.get(0)[0]);
        AssertJUnit.assertEquals(8, keys(r).size());
        List<int[]> r2 = Surfaces.enumerate(Surfaces.ENTITY_ONE, 60, 1, 1, a(41), null, 8, new Random(7));
        AssertJUnit.assertEquals(keys(r), keys(r2)); // seeded = reproducible (CRN)
    }

    @Test
    public void entitySetSmallIsExhaustiveAndSizeNeighbours() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_SET, 4, 1, 3, a(0, 2), null, 12, new Random(1));
        AssertJUnit.assertEquals("[0, 2]", java.util.Arrays.toString(r.get(0)));
        Set<String> k = keys(r);
        AssertJUnit.assertEquals(r.size(), k.size());
        AssertJUnit.assertTrue(k.contains("[1, 3]")); // every 2-subset
        AssertJUnit.assertTrue(k.contains("[0]")); // drop one
        AssertJUnit.assertTrue(k.contains("[0, 1, 2]")); // add one
        for (int[] x : r) {
            AssertJUnit.assertTrue(x.length >= 1 && x.length <= 3);
        }
    }

    @Test
    public void entitySetLargeSwapsAroundNatural() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_SET, 30, 3, 3, a(1, 5, 9), null, 10, new Random(3));
        AssertJUnit.assertEquals(10, r.size());
        AssertJUnit.assertEquals("[1, 5, 9]", java.util.Arrays.toString(r.get(0)));
        for (int[] x : r) {
            AssertJUnit.assertEquals(3, x.length);
            Set<Integer> d = new HashSet<>();
            for (int i : x) {
                AssertJUnit.assertTrue(i >= 0 && i < 30);
                AssertJUnit.assertTrue(d.add(i));
            }
        }
    }

    @Test
    public void orderSmallIsAllPermutations() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ORDER, 3, 3, 3, a(2, 0, 1), null, 12, new Random(1));
        AssertJUnit.assertEquals(6, r.size());
        AssertJUnit.assertEquals("[2, 0, 1]", java.util.Arrays.toString(r.get(0)));
    }

    @Test
    public void orderLargeIsAdjacentSwapsThenSamples() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ORDER, 6, 6, 6, a(0, 1, 2, 3, 4, 5), null, 8, new Random(1));
        AssertJUnit.assertEquals(8, r.size());
        for (int[] x : r) {
            Set<Integer> d = new HashSet<>();
            for (int i : x) {
                AssertJUnit.assertTrue(d.add(i));
            }
            AssertJUnit.assertEquals(6, d.size());
        }
        AssertJUnit.assertTrue(keys(r).contains("[1, 0, 2, 3, 4, 5]")); // an adjacent swap of the natural
    }

    @Test
    public void scrySmallIsEveryPartition() {
        List<int[]> r = Surfaces.enumerate(Surfaces.SCRY, 3, 3, 3, a(0, 1, 0), null, 12, new Random(1));
        AssertJUnit.assertEquals(8, r.size());
        AssertJUnit.assertEquals("[0, 1, 0]", java.util.Arrays.toString(r.get(0)));
    }

    @Test
    public void modeCombosWithinRange() {
        List<int[]> r = Surfaces.enumerate(Surfaces.MODE, 3, 1, 2, a(1), null, 12, new Random(1));
        Set<String> k = keys(r);
        AssertJUnit.assertEquals("[1]", java.util.Arrays.toString(r.get(0)));
        AssertJUnit.assertTrue(k.contains("[0]") && k.contains("[2]"));
        AssertJUnit.assertTrue(k.contains("[0, 1]") || k.contains("[1, 2]") || k.contains("[1, 0]"));
        for (int[] x : r) {
            AssertJUnit.assertTrue(x.length >= 1 && x.length <= 2);
        }
    }

    @Test
    public void damageKillOrdersUnderTheModernRule() {
        // 6 damage, two blockers needing 2 and 3, trample: the family is every
        // kill order (evening 3; no damage assignment order in force), the
        // remainder trampling over by rule
        List<int[]> r = Surfaces.enumerate(Surfaces.DAMAGE, 2, 6, 6, a(2, 3, 1), a(2, 3, 1), 12, new Random(1));
        AssertJUnit.assertEquals("[2, 3, 1]", java.util.Arrays.toString(r.get(0)));
        Set<String> k = keys(r);
        // the second killed first realizes the SAME amounts ([2, 3, 1]: lethal is
        // per blocker) and dedupes into the natural — amounts, not orders, are the family
        AssertJUnit.assertTrue(k.contains("[2, 0, 4]")); // lethal to the first, the rest tramples over
        AssertJUnit.assertTrue(k.contains("[0, 3, 3]")); // lethal to the second, the rest tramples over
        AssertJUnit.assertTrue(k.contains("[0, 0, 6]")); // all of it tramples over
        for (int[] x : r) {
            int sum = 0;
            for (int v : x) {
                AssertJUnit.assertTrue(v >= 0);
                sum += v;
            }
            AssertJUnit.assertEquals(6, sum);
        }
        // no trample: the remainder lands on the last blocker killed; the
        // defender slot stays 0 (the natural, which tramples, is only the seed)
        List<int[]> r2 = Surfaces.enumerate(Surfaces.DAMAGE, 2, 6, 6, a(2, 4, 0), a(2, 3, 0), 12, new Random(1));
        Set<String> k2 = keys(r2);
        AssertJUnit.assertTrue(k2.contains("[6, 0, 0]")); // everything on the first
        AssertJUnit.assertTrue(k2.contains("[0, 6, 0]")); // everything on the second
        AssertJUnit.assertTrue(k2.contains("[3, 3, 0]")); // lethal to the second, the rest on the first
        for (int[] x : r2) {
            AssertJUnit.assertEquals(0, x[2]);
        }
    }

    @Test
    public void damageFromSequenceRealizesKillOrders() {
        int[] aux = a(2, 3, 1); // lethal 2 / 3, trample
        AssertJUnit.assertEquals("[2, 3, 1]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(0, 1), 2, 6, aux)));
        AssertJUnit.assertEquals("[2, 3, 1]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(1, 0), 2, 6, aux)));
        AssertJUnit.assertEquals("[2, 0, 4]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(0), 2, 6, aux)));
        AssertJUnit.assertEquals("[2, 0, 4]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(0, 2), 2, 6, aux)));
        AssertJUnit.assertEquals("[0, 0, 6]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(2), 2, 6, aux)));
        // damage runs out inside the sequence: lethal to the first, the rest (1) on the second
        AssertJUnit.assertEquals("[2, 1, 0]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(0, 1), 2, 3, aux)));
        int[] noTrample = a(2, 3, 0);
        AssertJUnit.assertEquals("[6, 0, 0]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(0), 2, 6, noTrample)));
        AssertJUnit.assertEquals("[3, 3, 0]", java.util.Arrays.toString(Surfaces.damageFromSequence(a(1, 0), 2, 6, noTrample)));
        // invalid: empty, a repeat, out of range, the defender not last, the defender without trample
        AssertJUnit.assertNull(Surfaces.damageFromSequence(a(), 2, 6, aux));
        AssertJUnit.assertNull(Surfaces.damageFromSequence(a(0, 0), 2, 6, aux));
        AssertJUnit.assertNull(Surfaces.damageFromSequence(a(3), 2, 6, aux));
        AssertJUnit.assertNull(Surfaces.damageFromSequence(a(2, 0), 2, 6, aux));
        AssertJUnit.assertNull(Surfaces.damageFromSequence(a(2), 2, 6, noTrample));
    }

    @Test
    public void naturalNullStillEnumerates() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_ONE, 3, 1, 1, null, null, 12, new Random(1));
        AssertJUnit.assertEquals(3, r.size());
        List<int[]> s = Surfaces.enumerate(Surfaces.ENTITY_SET, 4, 2, 2, null, null, 12, new Random(1));
        AssertJUnit.assertEquals(6, s.size());
    }

    // ---- evening 2 (the label run's mode misses)

    @Test
    public void modeEmptyNaturalNeverEmitsBelowMin() {
        // "choose two" (n 3), the heuristic declined: every non-natural answer has size 2
        List<int[]> r = Surfaces.enumerate(Surfaces.MODE, 3, 2, 2, a(), null, 8, new Random(1), false);
        AssertJUnit.assertEquals(0, r.get(0).length);
        for (int i = 1; i < r.size(); i++) {
            AssertJUnit.assertEquals(2, r.get(i).length);
        }
        Set<String> k = keys(r);
        AssertJUnit.assertTrue(k.contains("[0, 1]") && k.contains("[0, 2]") && k.contains("[1, 2]"));
        AssertJUnit.assertEquals(4, r.size());
    }

    @Test
    public void modeRepeatEnumeratesMultisets() {
        // "choose three, repeats allowed" with two available modes: the four 3-multisets
        List<int[]> r = Surfaces.enumerate(Surfaces.MODE, 2, 3, 3, a(), null, 8, new Random(1), true);
        Set<String> k = keys(r);
        AssertJUnit.assertTrue(k.contains("[0, 0, 0]") && k.contains("[0, 0, 1]")
                && k.contains("[0, 1, 1]") && k.contains("[1, 1, 1]"));
        AssertJUnit.assertEquals(5, r.size()); // the empty natural + 4
        for (int i = 1; i < r.size(); i++) {
            AssertJUnit.assertEquals(3, r.get(i).length);
        }
    }

    @Test
    public void modeRepeatOverCapSamplesMultisetsInRange() {
        // three modes, choose three with repeats = 10 multisets > cap 6: natural first, all size 3, sorted
        List<int[]> r = Surfaces.enumerate(Surfaces.MODE, 3, 3, 3, a(0, 1, 2), null, 6, new Random(3), true);
        AssertJUnit.assertEquals("[0, 1, 2]", java.util.Arrays.toString(r.get(0)));
        AssertJUnit.assertEquals(6, r.size());
        for (int[] x : r) {
            AssertJUnit.assertEquals(3, x.length);
            for (int j = 1; j < x.length; j++) {
                AssertJUnit.assertTrue(x[j - 1] <= x[j]);
            }
        }
        AssertJUnit.assertEquals(6, keys(r).size());
    }

    @Test
    public void modeWithoutRepeatNeverRepeats() {
        List<int[]> r = Surfaces.enumerate(Surfaces.MODE, 3, 3, 3, a(), null, 8, new Random(1), false);
        AssertJUnit.assertEquals(2, r.size()); // the empty natural + [0, 1, 2]
        AssertJUnit.assertEquals("[0, 1, 2]", java.util.Arrays.toString(r.get(1)));
    }

    @Test
    public void setNeighboursStayInsideRange() {
        // choose 1..2 of 4, natural of size 1 over cap: sizes 1 and 2 only
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_SET, 30, 1, 2, a(7), null, 6, new Random(2), false);
        for (int[] x : r) {
            AssertJUnit.assertTrue(x.length >= 1 && x.length <= 2);
        }
    }
}
