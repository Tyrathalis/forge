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
    public void damageLethalInOrderThenRemainder() {
        // 6 damage, two blockers needing 2 and 3, trample allowed
        List<int[]> r = Surfaces.enumerate(Surfaces.DAMAGE, 2, 6, 6, a(2, 3, 1), a(2, 3, 1), 12, new Random(1));
        AssertJUnit.assertEquals("[2, 3, 1]", java.util.Arrays.toString(r.get(0)));
        Set<String> k = keys(r);
        AssertJUnit.assertTrue(k.contains("[6, 0, 0]")); // everything on the first
        AssertJUnit.assertTrue(k.contains("[2, 4, 0]")); // lethal to the first, the rest on the second
        for (int[] x : r) {
            int sum = 0;
            for (int v : x) {
                AssertJUnit.assertTrue(v >= 0);
                sum += v;
            }
            AssertJUnit.assertEquals(6, sum);
        }
        // no trample: the defender slot stays 0 and the natural (which tramples) is only the seed
        for (int[] x : Surfaces.enumerate(Surfaces.DAMAGE, 2, 6, 6, a(2, 4, 0), a(2, 3, 0), 12, new Random(1))) {
            AssertJUnit.assertEquals(0, x[2]);
        }
    }

    @Test
    public void naturalNullStillEnumerates() {
        List<int[]> r = Surfaces.enumerate(Surfaces.ENTITY_ONE, 3, 1, 1, null, null, 12, new Random(1));
        AssertJUnit.assertEquals(3, r.size());
        List<int[]> s = Surfaces.enumerate(Surfaces.ENTITY_SET, 4, 2, 2, null, null, 12, new Random(1));
        AssertJUnit.assertEquals(6, s.size());
    }
}
