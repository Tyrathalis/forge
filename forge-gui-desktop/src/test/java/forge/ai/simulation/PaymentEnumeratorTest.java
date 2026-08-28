package forge.ai.simulation;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.ComputerUtil;
import forge.ai.anvil.PaymentEnumerator;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Anvil M9 D3 rung 2 (as amended pre-D4, spec §12): unit tests for the
 * legality-derived GOAL enumeration + directed executor
 * (m9-payment-surface-spec.md §10/§12; capability audit ADR-0065 =
 * DirectedPaymentAuditTest).
 */
public class PaymentEnumeratorTest extends SimulationTest {

    private Player setUp(Game game) {
        Player p = game.getPlayers().get(1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        return p;
    }

    private PaymentEnumerator.Result enumerate(Player p, Card spellInHand) {
        SpellAbility castSa = spellInHand.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        return PaymentEnumerator.enumerate(p, castSa,
                castSa.getPayCosts().getTotalMana());
    }

    private static boolean usesChain(PaymentEnumerator.GoalOption opt) {
        for (PaymentEnumerator.Atom a : opt.plan.atoms) {
            if (!a.activationMana.isZero()) {
                return true;
            }
        }
        return false;
    }

    /** Single source class vs {1}{G}: one plan, one option, never
     *  consequential, never bridges. */
    @Test
    public void testSingleSourceNotConsequential() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Forest", p);
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand); // {1}{G}
        addCard("Forest", p); // second forest, same class
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, bear);
        AssertJUnit.assertEquals("two same-signature forests = one source class", 1, r.sourceClassCount);
        AssertJUnit.assertEquals("one plan", 1, r.planCount);
        AssertJUnit.assertEquals("one outcome-distinct option", 1, r.options.size());
        SpellAbility bearSa = bear.getFirstSpellAbility();
        bearSa.setActivatingPlayer(p);
        boolean auto = PaymentEnumerator.autoPayable(p, bearSa, bearSa.getPayCosts().getTotalMana(), false);
        AssertJUnit.assertTrue(auto);
        AssertJUnit.assertFalse("one option + auto-payable = not consequential",
                PaymentEnumerator.consequential(r, auto));
        AssertJUnit.assertFalse(r.truncated);
    }

    /** Dork vs land for {G}: residual-relevance splits the goals — the
     *  dork-as-blocker distinction the surface exists to expose. */
    @Test
    public void testCreatureVsLandSplitsOptions() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Forest", p);
        Card elves = addCard("Llanowar Elves", p);
        elves.setSickness(false);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand); // {1}{G}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, bear);
        AssertJUnit.assertEquals("creature and land are distinct source classes", 2, r.sourceClassCount);
        // {1}{G} from {Forest, Elves}: both tapped in every full payment — one option.
        // The split shows on the G shard alone:
        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result rG = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("G")));
        AssertJUnit.assertEquals("two plans for {G}", 2, rG.planCount);
        AssertJUnit.assertEquals("spare(elves) and spare(forest) = two options", 2, rG.options.size());
        boolean sparesElves = false;
        for (PaymentEnumerator.GoalOption opt : rG.options) {
            if (opt.goals.get(0).startsWith("spare:Llanowar")) {
                sparesElves = true;
                for (PaymentEnumerator.Atom a : opt.plan.atoms) {
                    AssertJUnit.assertFalse("spare(elves) plan taps no creature", a.host.isCreature());
                }
            }
        }
        AssertJUnit.assertTrue("a spare-elves option surfaced", sparesElves);
    }

    /** The ADR-0065 forced-chain board: I + I + Dimir Signet vs {1}{U}{B} —
     *  exactly one plan (the chain), surfaced as one option, consequential
     *  via the forced channel, and the executor runs it. */
    @Test
    public void testForcedChainSurfacesAndExecutes() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        Card thief = addCardToZone("Thief of Sanity", p, ZoneType.Hand); // {1}{U}{B}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, thief);
        AssertJUnit.assertTrue("the chain plan is found", r.planCount >= 1);
        PaymentEnumerator.GoalOption chained = null;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (usesChain(opt)) {
                chained = opt;
                break;
            }
        }
        AssertJUnit.assertNotNull("an option uses the Signet (nonzero activation mana)", chained);
        AssertJUnit.assertEquals("all three sources committed", 3, chained.plan.atoms.size());

        SpellAbility castSa = thief.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        boolean auto = PaymentEnumerator.autoPayable(p, castSa, castSa.getPayCosts().getTotalMana(), false);
        AssertJUnit.assertFalse("the auto-payer cannot construct the chain", auto);
        AssertJUnit.assertTrue("forced window is consequential",
                PaymentEnumerator.consequential(r, auto));

        // Directed execution: float the plan, then cast from the float.
        PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, chained.plan);
        AssertJUnit.assertEquals(PaymentEnumerator.ExecOutcome.DIRECTED_OK, out);
        AssertJUnit.assertEquals("plan floats exactly the cost", 3, p.getManaPool().totalMana());

        AssertJUnit.assertTrue("cast completes from the float",
                ComputerUtil.handlePlayingSpellAbility(p, castSa, null));
        playUntilStackClear(game);
        AssertJUnit.assertEquals(1, countCardsWithName(game, "Thief of Sanity"));
        AssertJUnit.assertEquals(0, p.getManaPool().totalMana());
    }

    /** The §12a pinned reachability check (test-verify-first, no explicit
     *  chain goal in v1): on a board where the chain is NOT the only plan,
     *  some spare-goal argmax must still reach the chained composition —
     *  sparing islands routes through the Signet. */
    @Test
    public void testChainReachableViaSpareGoal() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Island", p);
        addCard("Dimir Signet", p);
        Card curiosity = addCardToZone("Ledger Shredder", p, ZoneType.Hand); // {1}{U}
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = curiosity.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, castSa,
                castSa.getPayCosts().getTotalMana());
        AssertJUnit.assertTrue("islands-only AND chained plans exist", r.planCount >= 2);
        boolean chainSurfaced = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (usesChain(opt)) {
                chainSurfaced = true;
            }
        }
        AssertJUnit.assertTrue("the chained composition is some spare-goal's argmax "
                + "(else spec §12a adds an explicit chain goal)", chainSurfaced);
    }

    /** Yield-differing taps split goals (the D2a pin): an
     *  Overgrowth-enchanted forest is not a forest. */
    @Test
    public void testBoostedYieldSplitsOptions() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        Card f1 = addCard("Forest", p);
        addCard("Forest", p);
        Card overgrowth = addCard("Overgrowth", p);
        overgrowth.attachToEntity(f1, null, true);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result rG = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("G")));
        AssertJUnit.assertEquals("boosted vs plain forest = two source classes", 2, rG.sourceClassCount);
        AssertJUnit.assertEquals("yield difference is consequential", 2, rG.options.size());
    }

    /** Phyrexian shards: pay-mana vs pay-life split via the min_life goal
     *  (a named D1 auto-payer artifact family). */
    @Test
    public void testPhyrexianLifeVsManaSplitsOptions() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        Card probe = addCardToZone("Gitaxian Probe", p, ZoneType.Hand); // {U/P}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, probe);
        AssertJUnit.assertEquals("island-pay and life-pay options", 2, r.options.size());
        boolean sawLife = false, sawMana = false, sawMinLifeGoal = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (opt.plan.phyrexianLife > 0) {
                sawLife = true;
            } else {
                sawMana = true;
            }
            if (opt.goals.contains("pay_mana_not_life")) {
                sawMinLifeGoal = true;
                AssertJUnit.assertEquals("min_life pays with mana", 0, opt.plan.phyrexianLife);
            }
        }
        AssertJUnit.assertTrue(sawLife && sawMana);
        AssertJUnit.assertTrue("the min_life goal labels the mana payment", sawMinLifeGoal);
    }

    /** The certify-smoke salvage diagnosis (2026-08-20, devlog): a dual
     *  land hosts TWO mana abilities in TWO different source classes, and
     *  the DFS tracked availability per class — so a {W}{U} cost on a
     *  dual-heavy board committed the SAME physical card to both shards
     *  (count-feasible, executor-infeasible: the second tap fails
     *  canPlay). The §3 invariant is enumeration-feasibility =
     *  executor-feasibility; every materialized plan must use distinct
     *  hosts and execute directed_ok. */
    @Test
    public void testDualLandNotDoubleCommitted() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Hallowed Fountain", p);
        addCard("Hallowed Fountain", p);
        Card charm = addCardToZone("Azorius Charm", p, ZoneType.Hand); // {W}{U}
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, charm);
        AssertJUnit.assertTrue("a two-dual plan exists", r.planCount >= 1);
        AssertJUnit.assertTrue("at least one option surfaced", !r.options.isEmpty());
        for (PaymentEnumerator.GoalOption opt : r.options) {
            java.util.Set<Integer> hosts = new java.util.HashSet<>();
            for (PaymentEnumerator.Atom a : opt.plan.atoms) {
                AssertJUnit.assertTrue("plan commits host " + a.host.getName() + " ("
                        + a.host.getId() + ") twice — the cross-class double-commit",
                        hosts.add(a.host.getId()));
            }
        }
        PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, r.options.get(0).plan);
        AssertJUnit.assertEquals("directed execution is faithful on the dual board",
                PaymentEnumerator.ExecOutcome.DIRECTED_OK, out);
        AssertJUnit.assertEquals("plan floats exactly the cost", 2, p.getManaPool().totalMana());
    }

    /** The certify2 salvage family (2026-08-21, run-20260820-paygoals3):
     *  ALL 32 directed_salvage rows were "costs:Arena of Glory#N@1" — the
     *  enumerator admits Arena's second ability ({R}, {T}, Exert: add
     *  {R}{R}) into plans the executor then fails at payComputerCosts on
     *  the {R} activation cost. Enumeration-feasibility must equal
     *  executor-feasibility (the §3 invariant, ADR-0066 rule): either the
     *  atom is inadmissible or the directed execution must pay it. */
    @Test
    public void testExertCostedManaAbilityChainExecutes() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Mountain", p);
        addCard("Arena of Glory", p);
        Card zealot = addCardToZone("Ash Zealot", p, ZoneType.Hand); // {R}{R}
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = zealot.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, castSa,
                castSa.getPayCosts().getTotalMana());
        PaymentEnumerator.GoalOption chained = null;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (usesChain(opt)) {
                chained = opt;
                break;
            }
        }
        AssertJUnit.assertNotNull("a plan uses Arena's costed ability", chained);
        StringBuilder why = new StringBuilder();
        PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, chained.plan, why);
        AssertJUnit.assertEquals("exert-costed chain executes (salvage why: " + why + ")",
                PaymentEnumerator.ExecOutcome.DIRECTED_OK, out);
        AssertJUnit.assertEquals("chain floats exactly the cost", 2, p.getManaPool().totalMana());
    }

    /** The certify2/3 salvage mechanism isolated (2026-08-21): a chain
     *  plan whose activation cost is COLOR-starved at execution time. On
     *  Plains + Arena vs {1}{W}, the old count-based chainOrderFeasible
     *  admitted [Plains→W, Arena-costed→{1}] with the appended {R}
     *  activation shard paid by Arena's OWN second unit — mana that does
     *  not exist yet when the executor pays Arena's cost (pool holds only
     *  the Plains W; the heuristic's only red source is Arena itself,
     *  whose tap then fails). Deterministic salvage
     *  "costs:Arena of Glory#N@i" — the census signature. The §3
     *  invariant: every surfaced option must execute DIRECTED_OK. */
    @Test
    public void testColorStarvedChainNotAdmitted() {
        int nOptions = -1;
        for (int i = 0; i < Math.max(1, nOptions); i++) {
            Game game = initAndCreateGame();
            Player p = setUp(game);
            addCard("Plains", p);
            addCard("Arena of Glory", p);
            Card sky = addCardToZone("Kor Skyfisher", p, ZoneType.Hand); // {1}{W}
            game.getAction().checkStateEffects(true);

            PaymentEnumerator.Result r = enumerate(p, sky);
            AssertJUnit.assertTrue("at least the tap-both plan exists", !r.options.isEmpty());
            if (nOptions < 0) {
                nOptions = r.options.size(); // deterministic across fresh games (same add order = same ids)
            } else {
                AssertJUnit.assertEquals("enumeration deterministic across fresh games",
                        nOptions, r.options.size());
            }
            StringBuilder why = new StringBuilder();
            PaymentEnumerator.ExecOutcome out = PaymentEnumerator.executeDirected(p, r.options.get(i).plan, why);
            AssertJUnit.assertEquals("option " + i + " " + r.options.get(i).goals
                    + " salvaged (" + why + ") — enumeration-feasibility must equal executor-feasibility",
                    PaymentEnumerator.ExecOutcome.DIRECTED_OK, out);
        }
    }

    /** The §12 headline property: a wide board of distinct signatures vs a
     *  generic cost has MANY plans but few options — bounded by source
     *  classes, no truncation. */
    @Test
    public void testWideBoardBoundedOptions() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        // six distinct source classes
        addCard("Forest", p);
        addCard("Island", p);
        addCard("Mountain", p);
        addCard("Swamp", p);
        addCard("Sol Ring", p);
        Card bop = addCard("Birds of Paradise", p);
        bop.setSickness(false);
        Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = bear.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, castSa,
                new forge.card.mana.ManaCost(new forge.card.mana.ManaCostParser("2")));
        AssertJUnit.assertTrue("compositions explode (the old K_MAX=8 would truncate)",
                r.planCount > 8);
        AssertJUnit.assertTrue("options bounded by source classes",
                r.options.size() <= r.sourceClassCount);
        AssertJUnit.assertFalse("no truncation on the goal surface", r.truncated);
    }

    // ------------------------------------------------------------------
    // The cousins touch (2026-08-28): convoke/improvise/delve enumeration,
    // costmod per-spell refinement, the pool-tie spare_pool fix.

    /** Convoke spell: creature taps enter the plan space. Green bears can
     *  cover Stoke's generic pips but never its {R}{R} (a convoke tap pays
     *  a colored shard only through the creature's own colors); a pure-mana
     *  plan coexists on a 4-mountain board. The spell is no longer
     *  cost-modified. */
    @Test
    public void testConvokeCreatureTapsEnterPlans() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        for (int i = 0; i < 4; i++) {
            addCard("Mountain", p);
        }
        Card b1 = addCard("Grizzly Bears", p);
        Card b2 = addCard("Grizzly Bears", p);
        b1.setSickness(false);
        b2.setSickness(false);
        Card stoke = addCardToZone("Stoke the Flames", p, ZoneType.Hand); // {2}{R}{R}, convoke
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = stoke.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        AssertJUnit.assertFalse("convoke left the costmod detector",
                PaymentEnumerator.costModified(castSa));
        PaymentEnumerator.Result r = enumerate(p, stoke);
        AssertJUnit.assertTrue("cousin atoms collected", r.cousinAtomCount >= 2);
        boolean sawConvoke = false, sawPureMana = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (!opt.plan.convokeTaps.isEmpty()) {
                sawConvoke = true;
                for (forge.card.mana.ManaCostShard sh : opt.plan.convokeTaps.values()) {
                    AssertJUnit.assertTrue("green bears pay generic pips only",
                            sh == forge.card.mana.ManaCostShard.GENERIC);
                }
            } else if (!opt.plan.hasCousins()) {
                sawPureMana = true;
            }
        }
        AssertJUnit.assertTrue("a convoke-tapping plan surfaced", sawConvoke);
        AssertJUnit.assertTrue("the pure-mana plan coexists", sawPureMana);
    }

    /** Improvise: artifact taps cover generic only — {U}{U} stays on the
     *  islands. Two islands alone cannot pay {3}{U}{U}, so the window is
     *  forced open by the improvise plans. */
    @Test
    public void testImproviseArtifactsGenericOnly() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        addCard("Island", p);
        for (int i = 0; i < 3; i++) {
            addCard("Ornithopter", p);
        }
        Card re = addCardToZone("Reverse Engineer", p, ZoneType.Hand); // {3}{U}{U}, improvise
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = re.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        AssertJUnit.assertFalse(PaymentEnumerator.costModified(castSa));
        PaymentEnumerator.Result r = enumerate(p, re);
        AssertJUnit.assertTrue("improvise plans exist", r.planCount >= 1);
        boolean sawImprovise = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (opt.plan.improviseTaps.isEmpty()) {
                continue;
            }
            sawImprovise = true;
            AssertJUnit.assertEquals("all three thopters tapped for the generic 3",
                    3, opt.plan.improviseTaps.size());
            for (forge.card.mana.ManaCostShard sh : opt.plan.improviseTaps.values()) {
                AssertJUnit.assertTrue("improvise pays generic-payable shards only",
                        sh.canBePaidWithManaOfColor((byte) 0));
            }
        }
        AssertJUnit.assertTrue(sawImprovise);
        boolean auto = PaymentEnumerator.autoPayable(p, castSa, castSa.getPayCosts().getTotalMana(), false);
        AssertJUnit.assertTrue("improvise window is live: consequential",
                PaymentEnumerator.consequential(r, auto));
    }

    /** Delve: graveyard cards cover generic pips (strictly GENERIC), the
     *  graveyard groups into type-based source classes, and the spare_gy
     *  goals carry the spare_graveyard kind (6). */
    @Test
    public void testDelveGraveyardClassesAndGoal() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Island", p);
        addCard("Island", p);
        for (int i = 0; i < 3; i++) {
            addCardToZone("Forest", p, ZoneType.Graveyard);
            addCardToZone("Grizzly Bears", p, ZoneType.Graveyard);
        }
        Card dig = addCardToZone("Dig Through Time", p, ZoneType.Hand); // {6}{U}{U}, delve
        game.getAction().checkStateEffects(true);

        SpellAbility castSa = dig.getFirstSpellAbility();
        castSa.setActivatingPlayer(p);
        AssertJUnit.assertFalse(PaymentEnumerator.costModified(castSa));
        PaymentEnumerator.Result r = enumerate(p, dig);
        AssertJUnit.assertEquals("6 graveyard cards = 6 delve atoms", 6, r.cousinAtomCount);
        boolean sawFullDelve = false, sawGyGoal = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (opt.plan.delveExiles.size() == 6) {
                sawFullDelve = true;
            }
            for (int gi = 0; gi < opt.goals.size(); gi++) {
                if (opt.goals.get(gi).startsWith("spare_gy:")) {
                    sawGyGoal = true;
                    AssertJUnit.assertEquals("spare_graveyard kind code",
                            6, (int) opt.kinds.get(gi));
                }
            }
        }
        AssertJUnit.assertTrue("the full-delve plan surfaced (islands cover {U}{U})", sawFullDelve);
        AssertJUnit.assertTrue("spare_gy goals labeled", sawGyGoal);
    }

    /** The pool-tie residual fix (payment-completion queue item 5): with
     *  floating {U} and a {U/P} cost, the pay-life plan used to hide behind
     *  the spread-then-lex tie-break (no goal preferred it). spare_pool
     *  (kind 7) surfaces it. */
    @Test
    public void testPoolTiePayLifePlanSurfaces() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        Card island = addCard("Island", p);
        Card probe = addCardToZone("Gitaxian Probe", p, ZoneType.Hand); // {U/P}
        game.getAction().checkStateEffects(true);
        // float one blue for real: the pool carries it, the island taps out
        SpellAbility islandMana = island.getManaAbilities().get(0);
        islandMana.setActivatingPlayer(p);
        p.getManaPool().addMana(new forge.game.mana.Mana(forge.card.MagicColor.BLUE,
                island, islandMana.getManaPart(), p));
        island.tap(true, islandMana, p);

        PaymentEnumerator.Result r = enumerate(p, probe);
        boolean sawLifeViaSparePool = false, sawPoolPay = false;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (opt.plan.phyrexianLife > 0 && opt.goals.contains("spare_pool")) {
                sawLifeViaSparePool = true;
                AssertJUnit.assertEquals("spare_pool kind code", 7,
                        (int) opt.kinds.get(opt.goals.indexOf("spare_pool")));
            }
            if (opt.plan.phyrexianLife == 0 && opt.plan.poolSpend[1] == 1) {
                sawPoolPay = true;
            }
        }
        AssertJUnit.assertTrue("the lex-hidden pay-life plan surfaces via spare_pool",
                sawLifeViaSparePool);
        AssertJUnit.assertTrue("the pool-pay plan still surfaces", sawPoolPay);
    }

    /** Costmod per-spell refinement (queue item 4): Goblin Electromancer's
     *  ReduceCost static scopes out instants/sorceries ONLY — a creature
     *  spell under the same static returns to the surface (the old
     *  presence-scan flagged every window for that player). */
    @Test
    public void testCostmodPerSpellApplicability() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        addCard("Goblin Electromancer", p);
        addCard("Island", p);
        addCard("Island", p);
        Card opt = addCardToZone("Opt", p, ZoneType.Hand); // instant: static applies
        Card bear = addCardToZone("Grizzly Bears", p, ZoneType.Hand); // creature: it does not
        game.getAction().checkStateEffects(true);

        SpellAbility optSa = opt.getFirstSpellAbility();
        optSa.setActivatingPlayer(p);
        AssertJUnit.assertTrue("instant under Electromancer stays costmod",
                PaymentEnumerator.costModified(optSa));
        SpellAbility bearSa = bear.getFirstSpellAbility();
        bearSa.setActivatingPlayer(p);
        AssertJUnit.assertFalse("creature spell under Electromancer returns to the surface",
                PaymentEnumerator.costModified(bearSa));
    }

    /** CousinDirective consume semantics: an armed plan's maps are served
     *  filtered to the engine's offered list (misses counted); unarmed
     *  returns null = natural play; disarm restores natural. */
    @Test
    public void testCousinDirectiveConsume() {
        Game game = initAndCreateGame();
        Player p = setUp(game);
        for (int i = 0; i < 4; i++) {
            addCard("Mountain", p);
        }
        Card b1 = addCard("Grizzly Bears", p);
        Card b2 = addCard("Grizzly Bears", p);
        b1.setSickness(false);
        b2.setSickness(false);
        Card stoke = addCardToZone("Stoke the Flames", p, ZoneType.Hand);
        game.getAction().checkStateEffects(true);

        PaymentEnumerator.Result r = enumerate(p, stoke);
        PaymentEnumerator.PaymentClass convokePlan = null;
        for (PaymentEnumerator.GoalOption opt : r.options) {
            if (opt.plan.convokeTaps.size() == 2) {
                convokePlan = opt.plan;
                break;
            }
        }
        AssertJUnit.assertNotNull(convokePlan);

        AssertJUnit.assertNull("unarmed = natural",
                forge.ai.anvil.CousinDirective.forceConvokeOrImprovise(game, p, null,
                        p.getCardsIn(ZoneType.Battlefield), false, true));

        forge.ai.anvil.CousinDirective.Armed a = forge.ai.anvil.CousinDirective.arm(p, convokePlan);
        java.util.Map<Card, forge.card.mana.ManaCostShard> served =
                forge.ai.anvil.CousinDirective.forceConvokeOrImprovise(game, p, null,
                        p.getCardsIn(ZoneType.Battlefield), false, true);
        AssertJUnit.assertEquals("both planned taps served", 2, served.size());
        AssertJUnit.assertEquals(2, a.convokeServed);
        AssertJUnit.assertEquals(0, a.misses);
        forge.ai.anvil.CousinDirective.disarm(p);
        AssertJUnit.assertNull("disarmed = natural again",
                forge.ai.anvil.CousinDirective.forceConvokeOrImprovise(game, p, null,
                        p.getCardsIn(ZoneType.Battlefield), false, true));
    }
}
