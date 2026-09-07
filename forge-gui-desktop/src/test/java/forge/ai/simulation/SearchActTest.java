package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.anvil.SearchDirective;

/**
 * Anvil M12 Build 2: the acting rule (SearchDirective.Pending.decide) is a
 * pure function of the first-ply values, the bar, the temperature and the
 * sample seed — margin vs the natural pick, softmax sampling above the bar,
 * the natural line below it, and the classes where no margin exists.
 */
public class SearchActTest {

    private static SearchDirective.Pending pend(double bar, double temp, long seed, double... v) {
        String[] cands = new String[v.length];
        cands[0] = null; // pass
        for (int i = 1; i < v.length; i++) {
            cands[i] = "opt" + i;
        }
        return new SearchDirective.Pending("{\"ev\":\"search\"", null, cands, v, bar, temp, seed);
    }

    @Test
    public void belowBarIsNatural() {
        SearchDirective.Pending.Decision d = pend(0.05, 0.025, 7, 0.40, 0.43, 0.41).decide("pass");
        AssertJUnit.assertEquals("natural", d.by);
        AssertJUnit.assertEquals(-1, d.actIdx);
        AssertJUnit.assertEquals(0.03, d.margin, 1e-9);
    }

    @Test
    public void naturalNotACandidate() {
        SearchDirective.Pending.Decision d = pend(0.05, 0.025, 7, 0.40, 0.50).decide("elsewhere");
        AssertJUnit.assertEquals("nat_unsearched", d.by);
        AssertJUnit.assertEquals(-1, d.actIdx);
        AssertJUnit.assertTrue(Double.isNaN(d.margin));
    }

    @Test
    public void naturalUnvalued() {
        SearchDirective.Pending.Decision d = pend(0.05, 0.025, 7, Double.NaN, 0.50).decide("pass");
        AssertJUnit.assertEquals("nat_unvalued", d.by);
        AssertJUnit.assertEquals(-1, d.actIdx);
    }

    @Test
    public void zeroTemperatureIsArgmax() {
        SearchDirective.Pending.Decision d = pend(0.05, 0.0, 7, 0.40, 0.50, 0.45).decide("pass");
        AssertJUnit.assertEquals("search", d.by);
        AssertJUnit.assertEquals(1, d.actIdx);
        AssertJUnit.assertEquals(0.10, d.margin, 1e-9);
        AssertJUnit.assertEquals(1.0, d.p[1], 1e-12);
        AssertJUnit.assertEquals(0.0, d.logp, 1e-12);
    }

    @Test
    public void softmaxSamplesAtTheExpectedRateAndIsSeeded() {
        // values [0.40, 0.50, 0.45] at T = 0.025: p = softmax([-4, 0, -2])
        // = [0.0177, 0.8650, 0.1170]; the natural (pass) keeps ~1.8%.
        int argmax = 0, nat = 0, n = 4000;
        for (long s = 0; s < n; s++) {
            SearchDirective.Pending.Decision d = pend(0.05, 0.025, s, 0.40, 0.50, 0.45).decide("pass");
            AssertJUnit.assertTrue(d.actIdx >= 0);
            AssertJUnit.assertEquals(1.0, d.p[0] + d.p[1] + d.p[2], 1e-9);
            AssertJUnit.assertEquals(Math.log(d.p[d.actIdx]), d.logp, 1e-12);
            AssertJUnit.assertEquals(d.actIdx == 0 ? "search_nat" : "search", d.by);
            if (d.actIdx == 1) {
                argmax++;
            }
            if (d.actIdx == 0) {
                nat++;
            }
            AssertJUnit.assertEquals(d.actIdx, pend(0.05, 0.025, s, 0.40, 0.50, 0.45).decide("pass").actIdx);
        }
        double fa = argmax / (double) n, fn = nat / (double) n;
        AssertJUnit.assertTrue("argmax share " + fa, fa > 0.83 && fa < 0.90);
        AssertJUnit.assertTrue("natural share " + fn, fn > 0.008 && fn < 0.03);
    }

    @Test
    public void unvaluedCandidatesNeverSampled() {
        for (long s = 0; s < 200; s++) {
            SearchDirective.Pending.Decision d = pend(0.01, 0.025, s, 0.40, Double.NaN, 0.45).decide("pass");
            AssertJUnit.assertTrue(d.actIdx != 1);
            AssertJUnit.assertEquals(0.0, d.p[1], 1e-12);
        }
    }

    @Test
    public void completedRowCarriesTheVerdict() {
        final StringBuilder got = new StringBuilder();
        String[] cands = {null, "opt1"};
        SearchDirective.Pending p = new SearchDirective.Pending("{\"ev\":\"search\"", got::append,
                cands, new double[] {0.40, 0.50}, 0.05, 0.0, 1L);
        SearchDirective.Pending.Decision d = p.decide("pass");
        p.complete("pass", d, "act");
        String row = got.toString();
        AssertJUnit.assertTrue(row, row.contains("\"nat\":\"pass\""));
        AssertJUnit.assertTrue(row, row.contains("\"by\":\"search\""));
        AssertJUnit.assertTrue(row, row.contains("\"margin\":0.10000"));
        AssertJUnit.assertTrue(row, row.contains("\"act_o\":1"));
        AssertJUnit.assertTrue(row, row.contains("\"act\":\"opt1\""));
        AssertJUnit.assertTrue(row, row.contains("\"applied\":\"act\""));
        AssertJUnit.assertTrue(row, row.endsWith("}"));
        got.setLength(0);
        new SearchDirective.Pending("{\"ev\":\"search\"", got::append).complete("pass");
        AssertJUnit.assertEquals("{\"ev\":\"search\",\"nat\":\"pass\"}", got.toString());
    }
}
