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

    @Test
    public void actVoidRowCarriesItsReason() {
        // 09-21 (ADR-0114 routed): the mainline's forced ask voided -> act_vr
        StringBuilder got = new StringBuilder();
        String[] cands = {null, "opt1"};
        double[] v = {0.40, 0.60};
        SearchDirective.Pending p = new SearchDirective.Pending("{\"ev\":\"search\"", got::append,
                cands, v, 0.05, 0.0, 1);
        SearchDirective.Pending.Decision d = p.decide("pass");
        AssertJUnit.assertEquals(1, d.actIdx);
        p.complete("pass", d, "act_void", "no_shape_fit");
        String row = got.toString();
        AssertJUnit.assertTrue(row, row.contains("\"applied\":\"act_void\""));
        AssertJUnit.assertTrue(row, row.contains("\"act_vr\":\"no_shape_fit\""));
        got.setLength(0);
        p = new SearchDirective.Pending("{\"ev\":\"search\"", got::append, cands, v, 0.05, 0.0, 1);
        p.complete("pass", p.decide("pass"), "act");
        AssertJUnit.assertFalse(got.toString(), got.toString().contains("act_vr"));
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

    // ---- The partial-expansion slot (ADR-0106 C3): the deep stage

    /** A stub deep round: records the set + answer indices it was asked
     *  for and answers with fixed per-candidate values. */
    private static final class Deep implements SearchDirective.Pending.DeepRound {
        final double[] v;
        int[] set;
        int[] ans;
        int runs = 0;

        Deep(double... v) {
            this.v = v;
        }

        @Override
        public Result run(int[] set, int[] ans) {
            this.set = set;
            this.ans = ans;
            runs++;
            double[] out = new double[v.length];
            java.util.Arrays.fill(out, Double.NaN);
            for (int c : set) {
                out[c] = v[c];
            }
            return new Result(out, "[]");
        }
    }

    private static SearchDirective.Pending deepPend(double bar, long seed, double[] v, Deep deep, int top,
            double lo, double floor, double deepBar, SearchDirective.Pending.SurfAnswers... surf) {
        String[] cands = new String[v.length];
        cands[0] = null;
        for (int i = 1; i < v.length; i++) {
            cands[i] = "opt" + i;
        }
        return new SearchDirective.Pending("{\"ev\":\"search\"", null, cands, v, bar, 0.0, seed,
                surf.length == 0 ? null : surf, deep, top, lo, floor, deepBar);
    }

    @Test
    public void noDeepRoundMeansTheDeepStageIsOff() {
        SearchDirective.Pending.Decision d = pend(0.10, 0.0, 7, 0.40, 0.45).decide("pass");
        AssertJUnit.assertEquals("off", d.deepBy);
        AssertJUnit.assertTrue(Double.isNaN(d.shallowMargin));
    }

    @Test
    public void shallowMarginInTheBandRunsTheDeepRoundOnTheNaturalAndTheTopB() {
        // shallow: pass 0.40 (natural), opt1 0.45, opt2 0.44, opt3 0.30 — margin 0.05 in [0.02, 0.10)
        Deep deep = new Deep(0.60, 0.50, 0.75, 0.90);
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.40, 0.45, 0.44, 0.30}, deep,
                2, 0.02, 0.0, Double.NaN).decide("pass");
        AssertJUnit.assertEquals(1, deep.runs);
        AssertJUnit.assertEquals("band", d.deepBy);
        AssertJUnit.assertEquals("[0, 1, 2]", java.util.Arrays.toString(deep.set)); // natural + top-2
        AssertJUnit.assertEquals(0.05, d.shallowMargin, 1e-9);
        AssertJUnit.assertEquals(1, d.shallowArg);
        // the deep values decide over the set only: opt3 (0.90) is pruned
        AssertJUnit.assertEquals("deep", d.by);
        AssertJUnit.assertEquals(2, d.actIdx);
        AssertJUnit.assertEquals(0.15, d.margin, 1e-9);
        AssertJUnit.assertEquals(0.0, d.p[3], 1e-12);
        AssertJUnit.assertEquals(0.0, d.p[1], 1e-12);
    }

    @Test
    public void shallowMarginOutsideTheBandLeavesTheShallowRuleWithoutAFloor() {
        Deep deep = new Deep(0.0, 0.0, 0.0);
        // below the band: nothing to adjudicate
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.40, 0.41, 0.30}, deep,
                2, 0.02, 0.0, Double.NaN).decide("pass");
        AssertJUnit.assertEquals("gate", d.deepBy);
        AssertJUnit.assertEquals("natural", d.by);
        AssertJUnit.assertEquals(0, deep.runs);
        // at or above the bar: the shallow rule acts as before
        d = deepPend(0.10, 7, new double[] {0.40, 0.55, 0.30}, deep, 2, 0.02, 0.0, Double.NaN).decide("pass");
        AssertJUnit.assertEquals("gate", d.deepBy);
        AssertJUnit.assertEquals("search", d.by);
        AssertJUnit.assertEquals(1, d.actIdx);
        AssertJUnit.assertEquals(0, deep.runs);
    }

    @Test
    public void theFloorDrawIsSeededAndRunsTheDeepRoundOutsideTheBand() {
        int hits = 0;
        for (long s = 0; s < 2000; s++) {
            Deep deep = new Deep(0.40, 0.40, 0.40);
            SearchDirective.Pending.Decision d = deepPend(0.10, s, new double[] {0.40, 0.41, 0.30}, deep,
                    2, 0.02, 0.1, Double.NaN).decide("pass");
            AssertJUnit.assertEquals(d.deepBy, deep.runs == 1 ? "floor" : "gate");
            if (deep.runs == 1) {
                hits++;
                AssertJUnit.assertEquals("natural", d.by); // deep margin 0 < bar
                AssertJUnit.assertEquals(0.0, d.margin, 1e-9);
            }
            Deep again = new Deep(0.40, 0.40, 0.40);
            deepPend(0.10, s, new double[] {0.40, 0.41, 0.30}, again, 2, 0.02, 0.1, Double.NaN).decide("pass");
            AssertJUnit.assertEquals(deep.runs, again.runs);
        }
        double f = hits / 2000.0;
        AssertJUnit.assertTrue("floor share " + f, f > 0.07 && f < 0.13);
    }

    @Test
    public void aSingleValuedCandidateSkipsTheDeepRound() {
        Deep deep = new Deep(0.0, 0.0);
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.40, Double.NaN}, deep,
                2, 0.02, 1.0, Double.NaN).decide("pass");
        AssertJUnit.assertEquals("single", d.deepBy);
        AssertJUnit.assertEquals(0, deep.runs);
    }

    @Test
    public void theNaturalOutsideTheTopBIsStillInTheDeepSet() {
        // natural opt3 ranks last; the set is opt3 + the top-2
        Deep deep = new Deep(0.50, 0.50, 0.50, 0.80);
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.44, 0.45, 0.43, 0.40}, deep,
                2, 0.02, 0.0, Double.NaN).decide("opt3");
        AssertJUnit.assertEquals("band", d.deepBy);
        AssertJUnit.assertEquals("[0, 1, 3]", java.util.Arrays.toString(deep.set));
        AssertJUnit.assertEquals("natural", d.by); // the natural wins at depth: the set's max is itself
        AssertJUnit.assertEquals(0.0, d.margin, 1e-9);
    }

    @Test
    public void anUnvaluedDeepNaturalFallsBackToTheShallowRule() {
        Deep deep = new Deep(Double.NaN, 0.90, 0.10);
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.40, 0.45, 0.30}, deep,
                2, 0.02, 0.0, Double.NaN).decide("pass");
        AssertJUnit.assertEquals("nat_unvalued", d.deepBy);
        AssertJUnit.assertEquals("natural", d.by); // shallow margin 0.05 < bar
        AssertJUnit.assertEquals(0.05, d.margin, 1e-9);
    }

    @Test
    public void theDeepBarIsItsOwn() {
        Deep deep = new Deep(0.40, 0.47, 0.10);
        SearchDirective.Pending.Decision d = deepPend(0.10, 7, new double[] {0.40, 0.45, 0.30}, deep,
                2, 0.02, 0.0, 0.05).decide("pass");
        AssertJUnit.assertEquals("deep", d.by);
        AssertJUnit.assertEquals(0.07, d.margin, 1e-9);
        d = deepPend(0.10, 7, new double[] {0.40, 0.45, 0.30}, deep, 2, 0.02, 0.0, 0.20).decide("pass");
        AssertJUnit.assertEquals("natural", d.by);
    }

    @Test
    public void theDeepCopiesPlayTheLiftedAnswer() {
        // opt1 first-ply 0.30; its answers: natural 0.30, answer 1 at 0.45 → answer margin 0.15 ≥ bar,
        // sampled at T 0 → answer 1, opt1 lifted to 0.45 → shallow margin 0.05: in the band
        Deep deep = new Deep(0.40, 0.60);
        SearchDirective.Pending p = deepPend(0.10, 7, new double[] {0.40, 0.30}, deep, 2, 0.02, 0.0, Double.NaN,
                null, ans(0, 0.30, 0.45));
        SearchDirective.Pending.Decision d = p.decide("pass");
        AssertJUnit.assertEquals("band", d.deepBy);
        AssertJUnit.assertEquals("[-1, 1]", java.util.Arrays.toString(deep.ans)); // the deep copy plays answer 1
        AssertJUnit.assertEquals("deep", d.by);
        AssertJUnit.assertEquals("search", d.ansBy); // the acted option's answer verdict stands
    }

    @Test
    public void completedRowCarriesTheDeepVerdict() {
        final StringBuilder got = new StringBuilder();
        String[] cands = {null, "opt1", "opt2"};
        SearchDirective.Pending p = new SearchDirective.Pending("{\"ev\":\"search\"", got::append,
                cands, new double[] {0.40, 0.45, 0.30}, 0.10, 0.0, 1L, null,
                new Deep(0.50, 0.70, 0.0), 1, 0.02, 0.0, Double.NaN);
        SearchDirective.Pending.Decision d = p.decide("pass");
        p.complete("pass", d, "act");
        String row = got.toString();
        AssertJUnit.assertTrue(row, row.contains("\"by\":\"deep\""));
        AssertJUnit.assertTrue(row, row.contains("\"margin\":0.20000"));
        AssertJUnit.assertTrue(row, row.contains("\"act_o\":1"));
        AssertJUnit.assertTrue(row, row.contains(
                "\"deep\":{\"by\":\"band\",\"shallow_margin\":0.05000,\"shallow_arg\":1,\"set\":[0,1],\"v\":[0.50000,0.70000],\"copies\":[]}"));
        AssertJUnit.assertTrue(row, row.endsWith("}"));
    }
}
