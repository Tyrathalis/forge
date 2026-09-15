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

    // ---- Evening 5 (ADR-0106 A): the answer stage

    private static SearchDirective.Pending.SurfAnswers ans(int natIdx, double... v) {
        int[][] a = new int[v.length][];
        for (int i = 0; i < v.length; i++) {
            a[i] = new int[] {i};
        }
        return new SearchDirective.Pending.SurfAnswers(forge.ai.anvil.Surfaces.MODE, 0, "Charm", a, v, natIdx);
    }

    private static SearchDirective.Pending pend(double bar, double temp, long seed, double[] v,
            SearchDirective.Pending.SurfAnswers... surf) {
        String[] cands = new String[v.length];
        cands[0] = null;
        for (int i = 1; i < v.length; i++) {
            cands[i] = "opt" + i;
        }
        return new SearchDirective.Pending("{\"ev\":\"search\"", null, cands, v, bar, temp, seed, surf);
    }

    @Test
    public void noSurfacesMeansTheAnswerStageIsOff() {
        SearchDirective.Pending.Decision d = pend(0.05, 0.025, 7, 0.40, 0.43).decide("pass");
        AssertJUnit.assertEquals("off", d.ansBy);
        AssertJUnit.assertNull(d.lifted);
        AssertJUnit.assertNull(d.arm(pend(0.05, 0.025, 7, 0.40, 0.43)));
    }

    @Test
    public void answerBelowBarLeavesOptionsUntouched() {
        // opt1's answers: natural 0.50, best 0.53 (margin 0.03 < 0.10)
        SearchDirective.Pending p = pend(0.10, 0.0, 7, new double[] {0.40, 0.50}, null, ans(0, 0.50, 0.53));
        SearchDirective.Pending.Decision d = p.decide("opt1");
        AssertJUnit.assertEquals("natural", d.by);
        AssertJUnit.assertEquals("natural", d.ansBy);
        AssertJUnit.assertEquals(1, d.ansCand);
        AssertJUnit.assertEquals(0.03, d.ansMargin, 1e-9);
        AssertJUnit.assertNull(d.lifted);
        AssertJUnit.assertNull(d.arm(p));
    }

    @Test
    public void answerAboveBarActsOnTheNaturalOption() {
        // the natural option stands (no better option) but its answer 1 beats
        // the natural answer 0 by 0.15: the answer is armed, by = natural
        SearchDirective.Pending p = pend(0.10, 0.0, 7, new double[] {0.40, 0.50}, null, ans(0, 0.50, 0.65));
        SearchDirective.Pending.Decision d = p.decide("opt1");
        AssertJUnit.assertEquals("natural", d.by);
        AssertJUnit.assertEquals("search", d.ansBy);
        AssertJUnit.assertEquals(1, d.ansIdx);
        AssertJUnit.assertEquals(0, d.ansNatIdx);
        AssertJUnit.assertEquals(0.15, d.ansMargin, 1e-9);
        AssertJUnit.assertEquals(0.0, d.ansLogp, 1e-12);
        AssertJUnit.assertNotNull(d.lifted);
        AssertJUnit.assertEquals(0.65, d.lifted[1], 1e-9);
        AssertJUnit.assertEquals(1, d.arm(p).answers[d.ansIdx][0]);
        // the option margin is read on the lifted values: max 0.65 − nat 0.65 = 0
        AssertJUnit.assertEquals(0.0, d.margin, 1e-9);
    }

    @Test
    public void aLiftedAnswerCanFlipTheOptionStage() {
        // pass 0.45 natural; opt1 0.40 at its natural answer but 0.60 at answer 1:
        // the lift makes opt1 the acted option and arms its answer
        SearchDirective.Pending p = pend(0.10, 0.0, 7, new double[] {0.45, 0.40}, null, ans(0, 0.40, 0.60));
        SearchDirective.Pending.Decision d = p.decide("pass");
        AssertJUnit.assertEquals("search", d.by);
        AssertJUnit.assertEquals(1, d.actIdx);
        AssertJUnit.assertEquals(0.15, d.margin, 1e-9);
        AssertJUnit.assertEquals("search", d.ansBy);
        AssertJUnit.assertEquals(1, d.ansCand);
        AssertJUnit.assertEquals(1, d.ansIdx);
        // without the lift the option stage would not have acted
        SearchDirective.Pending.Decision d0 = pend(0.10, 0.0, 7, new double[] {0.45, 0.40}).decide("pass");
        AssertJUnit.assertEquals("natural", d0.by);
    }

    @Test
    public void actedOptionWithoutAnExpansionIsUnsearched() {
        SearchDirective.Pending p = pend(0.10, 0.0, 7, new double[] {0.40, 0.60}, ans(0, 0.40, 0.70), null);
        SearchDirective.Pending.Decision d = p.decide("pass");
        // pass lifted to 0.70 → pass is the argmax and the natural: no option act
        AssertJUnit.assertEquals("natural", d.by);
        AssertJUnit.assertEquals("search", d.ansBy);
        AssertJUnit.assertEquals(0, d.ansCand);
        // an acted option whose path was not expanded
        SearchDirective.Pending q = pend(0.10, 0.0, 7, new double[] {0.40, 0.60}, null, null);
        SearchDirective.Pending.Decision e = q.decide("pass");
        AssertJUnit.assertEquals("search", e.by);
        AssertJUnit.assertEquals("ans_unsearched", e.ansBy);
        AssertJUnit.assertNull(e.arm(q));
        // an unvalued natural answer
        SearchDirective.Pending r = pend(0.10, 0.0, 7, new double[] {0.40, 0.60}, null, ans(-1, 0.40, 0.70));
        AssertJUnit.assertEquals("ans_unvalued", r.decide("pass").ansBy);
    }

    @Test
    public void answerSamplingIsSeededAndKeepsTheNaturalAnswer() {
        int nat = 0, best = 0, n = 4000;
        for (long s = 0; s < n; s++) {
            SearchDirective.Pending p = pend(0.05, 0.025, s, new double[] {0.40, 0.50}, null,
                    ans(0, 0.50, 0.60, 0.55));
            SearchDirective.Pending.Decision d = p.decide("opt1");
            AssertJUnit.assertTrue(d.ansIdx >= 0);
            AssertJUnit.assertEquals(1.0, d.ansP[0] + d.ansP[1] + d.ansP[2], 1e-9);
            AssertJUnit.assertEquals(d.ansIdx, pend(0.05, 0.025, s, new double[] {0.40, 0.50}, null,
                    ans(0, 0.50, 0.60, 0.55)).decide("opt1").ansIdx);
            if (d.ansIdx == 0) {
                nat++;
                AssertJUnit.assertEquals("search_nat", d.ansBy);
                AssertJUnit.assertNull(d.arm(p));
            }
            if (d.ansIdx == 1) {
                best++;
            }
        }
        double fb = best / (double) n, fn = nat / (double) n;
        AssertJUnit.assertTrue("best share " + fb, fb > 0.83 && fb < 0.90);
        AssertJUnit.assertTrue("natural share " + fn, fn > 0.008 && fn < 0.03);
    }

    @Test
    public void completedRowCarriesTheAnswerVerdict() {
        final StringBuilder got = new StringBuilder();
        String[] cands = {null, "opt1"};
        SearchDirective.Pending p = new SearchDirective.Pending("{\"ev\":\"search\"", got::append,
                cands, new double[] {0.40, 0.50}, 0.10, 0.0, 1L,
                new SearchDirective.Pending.SurfAnswers[] {null, ans(0, 0.50, 0.65)});
        SearchDirective.Pending.Decision d = p.decide("opt1");
        p.complete("opt1", d, "natural");
        String row = got.toString();
        AssertJUnit.assertTrue(row, row.contains("\"lifted\":[0.40000,0.65000]"));
        AssertJUnit.assertTrue(row, row.contains("\"ans\":{\"o\":1,\"by\":\"search\",\"kind\":\"mode\",\"ord\":0,\"nat_i\":0"));
        AssertJUnit.assertTrue(row, row.contains("\"margin\":0.15000,\"a_i\":1,\"a\":[1],\"logp\":0.0000,\"p\":[0.0000,1.0000]}"));
        AssertJUnit.assertTrue(row, row.endsWith("}}"));
    }
}
