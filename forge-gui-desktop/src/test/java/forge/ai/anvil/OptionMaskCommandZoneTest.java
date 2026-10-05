package forge.ai.anvil;

import java.util.ArrayList;
import java.util.List;

import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import forge.ai.AiController;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtil;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.PlayerControllerAi;
import forge.ai.simulation.SimulationTest;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CounterEnumType;
import forge.game.cost.CostAdjustment;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.Zone;
import forge.game.zone.ZoneType;

/**
 * Anvil 2026-10-05: the option-mask class the ADR-0122 agreement corpus
 * surfaced beside the target mask — heuristic casts whose host the priority
 * option scan never offered (57 of 107,247 casts; King T'Challa's back face
 * from the command zone, Dargo's sacrifice-reduced cast, X spells). Each
 * case reproduces a corpus window and reads every clause of the scan
 * (canPlay, the restrictions clause, exact payability) beside the engine's
 * own path (the AI's canPlaySa + canPayCost, then the cast and the mana it
 * actually took), so the miss is attributed to one clause.
 */
public class OptionMaskCommandZoneTest extends SimulationTest {

    /** game 50, seq 702 of the tgtmask-agree2 corpus: six untapped lands, cmdcast 2. */
    private static final String[] G50_LANDS = {"Castle Ardenvale", "Hallowed Fountain", "Reflecting Pool",
            "Seachrome Coast", "Snow-Covered Plains", "Tundra"};

    private static List<SpellAbility> spellsOf(Card c, Player p) {
        List<SpellAbility> out = new ArrayList<>();
        for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(
                ComputerUtilAbility.getSpellAbilities(new CardCollection(c), p), p)) {
            if (sa.isSpell()) {
                out.add(sa);
            }
        }
        return out;
    }

    private static SpellAbility face(List<SpellAbility> spells, String stateName) {
        for (SpellAbility sa : spells) {
            if (stateName.equals(sa.getCardState().getName())) {
                return sa;
            }
        }
        return null;
    }

    /** The cost the engine would charge: the realizer's cast-from dance, then the adjustment (commander tax, reductions). */
    private static int adjustedCmc(SpellAbility sa) {
        Card host = sa.getHostCard();
        Zone backup = host.getCastFrom();
        host.setCastFrom(host.getZone());
        try {
            return CostAdjustment.adjust(sa.getPayCosts(), sa, false).getTotalMana().getCMC();
        } finally {
            host.setCastFrom(backup);
        }
    }

    private static boolean offered(Game game, Player p, SpellAbility sa) {
        AnvilOptions.invalidate(game, p);
        for (SpellAbility o : AnvilOptions.priorityOptions(game, p)) {
            if (o.getHostCard() == sa.getHostCard() && o.isSpell()
                    && o.getCardState().getName().equals(sa.getCardState().getName())) {
                return true;
            }
        }
        return false;
    }

    private static int untappedLands(Player p) {
        int n = 0;
        for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                n++;
            }
        }
        return n;
    }

    private static final class Row {
        boolean canPlay, restrictions, payable, offered, aiCanPay, cast;
        AiPlayDecision aiDecision;
        int adjCmc, tapped;
        Integer x;
        @Override
        public String toString() {
            return String.format("canPlay=%b restrictions=%b payable=%b offered=%b | adjCmc=%d aiCanPlaySa=%s aiCanPay=%b x=%s | cast=%b landsTapped=%d",
                    canPlay, restrictions, payable, offered, adjCmc, aiDecision, aiCanPay, x, cast, tapped);
        }
    }

    /** The scan's clauses, then the heuristic's own path (canPlaySa sets X / costs; canPayCost; the cast). */
    private static Row read(Game game, Player p, SpellAbility sa, boolean doCast) {
        Row r = new Row();
        sa.setActivatingPlayer(p);
        r.canPlay = sa.canPlay();
        r.restrictions = CastPlanRealizer.passesRestrictions(game, p, sa);
        r.payable = AnvilOptions.payable(game, p, sa);
        r.offered = offered(game, p, sa);
        r.adjCmc = adjustedCmc(sa);
        AiController ai = ((PlayerControllerAi) p.getController()).getAi();
        r.aiDecision = ai.canPlaySa(sa);
        r.aiCanPay = ComputerUtilCost.canPayCost(sa, p, false);
        r.x = sa.getXManaCostPaid();
        if (doCast) {
            int before = untappedLands(p);
            r.cast = ComputerUtil.handlePlayingSpellAbility(p, sa, null);
            r.tapped = before - untappedLands(p);
        }
        return r;
    }

    @Test
    public void kingTChallaBackFaceFromTheCommandZone() {
        for (int cmdcast = 0; cmdcast <= 2; cmdcast++) {
            Game game = initAndCreateGame();
            Player p = game.getPlayers().get(1);
            for (String land : G50_LANDS) {
                addCard(land, p);
            }
            Card king = addCardToZone("King T'Challa", p, ZoneType.Command);
            p.addCommander(king);
            for (int i = 0; i < cmdcast; i++) {
                p.incCommanderCast(king);
            }
            game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
            game.getAction().checkStaticAbilities(); // the Commander Effect's MayPlay static (the match runs this)

            List<SpellAbility> spells = spellsOf(king, p);
            SpellAbility front = face(spells, "King T'Challa");
            SpellAbility back = face(spells, "Black Panther, Hope Enduring");
            AssertJUnit.assertNotNull("front face spell", front);
            AssertJUnit.assertNotNull("back face spell", back);
            Row f = read(game, p, front, false);
            Row b = read(game, p, back, false);
            System.out.println("[cmdzone] T'Challa cmdcast=" + cmdcast + " lands=6 FRONT " + f);
            System.out.println("[cmdzone] T'Challa cmdcast=" + cmdcast + " lands=6 BACK  " + b);
            // the heuristic's own payability test: canPlayAndPayFor swaps the spell's host to
            // Spell.getAlternateHost's LKI copy (switched to the back-face state) before
            // ComputerUtilCost.canPayCost; that copy has no zone, so calculateManaCost's cast-from
            // dance finds nothing and the commander tax never enters the test.
            Card alt = ((forge.game.spellability.Spell) back).canPlayFromHost();
            boolean aiCanPayOnAlt;
            back.setHostCard(alt);
            try {
                aiCanPayOnAlt = ComputerUtilCost.canPayCost(back, p, false);
            } finally {
                back.setHostCard(king);
            }
            // ... and the cast the heuristic then attempts: the real payment (castFrom set by the
            // zone move) charges the tax, fails, and leaves the card in the stack zone with the
            // lands tapped and the mana floating — the corpus signature of the ten "casts".
            int before = untappedLands(p);
            boolean castOk = ComputerUtil.handlePlayingSpellAbility(p, back, null);
            Card kingNow = game.getCardState(king);
            System.out.println("[cmdzone] T'Challa cmdcast=" + cmdcast + " altHostZone=" + alt.getZone()
                    + " aiCanPayOnAltHost=" + aiCanPayOnAlt + " | forced back-face cast=" + castOk
                    + " zone=" + kingNow.getZone().getZoneType() + " stackEmpty=" + game.getStack().isEmpty()
                    + " landsTapped=" + (before - untappedLands(p)) + " floatingMana=" + p.getManaPool().totalMana());

            if (cmdcast == 0) {
                AssertJUnit.assertTrue("front offered at tax 0", f.offered);
                AssertJUnit.assertTrue("back offered at tax 0", b.offered);
                AssertJUnit.assertTrue("the engine pays the back face at tax 0", castOk);
            }
            if (cmdcast == 2) {
                // the scan and the engine's own test agree on the real host: 6 lands pay neither 7 nor 10
                AssertJUnit.assertEquals(7, f.adjCmc);
                AssertJUnit.assertEquals(10, b.adjCmc);
                AssertJUnit.assertFalse(f.offered);
                AssertJUnit.assertFalse(b.offered);
                AssertJUnit.assertFalse(f.aiCanPay);
                AssertJUnit.assertFalse(b.aiCanPay);
                // the heuristic's blind spot (upstream forge-ai): the alternate host drops the tax
                AssertJUnit.assertNull("the alternate host carries no zone", alt.getZone());
                AssertJUnit.assertTrue("canPayCost on the alternate host ignores the commander tax", aiCanPayOnAlt);
                // the attempted cast strands the card and the mana (the corpus state after each miss)
                AssertJUnit.assertFalse(castOk);
                AssertJUnit.assertEquals(ZoneType.Stack, kingNow.getZone().getZoneType());
                AssertJUnit.assertTrue(game.getStack().isEmpty());
                AssertJUnit.assertEquals(0, untappedLands(p));
                AssertJUnit.assertEquals(6, p.getManaPool().totalMana());
            }
        }
    }

    @Test
    public void dargoReducedByItsOwnSacrificeFromTheCommandZone() {
        Game game = initAndCreateGame();
        Player p = game.getPlayers().get(1);
        addCard("Great Furnace", p);
        addCard("Silverbluff Bridge", p);
        addCard("Vault of Whispers", p);
        addCard("Ornithopter", p);
        addCard("Memnite", p);
        addCard("Grizzly Bears", p);
        Card dargo = addCardToZone("Dargo, the Shipwrecker", p, ZoneType.Command);
        p.addCommander(dargo);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN2, p);
        game.getAction().checkStaticAbilities();
        List<SpellAbility> spells = spellsOf(dargo, p);
        AssertJUnit.assertFalse(spells.isEmpty());
        SpellAbility cast = spells.get(0); // the plain cast; the alt-cost variant follows it
        Row r = read(game, p, cast, true);
        System.out.println("[cmdzone] Dargo (3 lands, 3 fodder) " + cast.getDescription() + " :: " + r
                + " zoneAfter=" + game.getCardState(dargo).getZone().getZoneType());
        // the ADR-0005 class: exact payability reads {6}{R} with no sacrifice chosen and refuses;
        // the heuristic's canPlaySa chooses X = 3 first and pays {R} with one land
        AssertJUnit.assertEquals(7, r.adjCmc);
        AssertJUnit.assertFalse(r.payable);
        AssertJUnit.assertFalse(r.offered);
        AssertJUnit.assertEquals(AiPlayDecision.WillPlay, r.aiDecision);
        AssertJUnit.assertEquals(Integer.valueOf(3), r.x);
        AssertJUnit.assertTrue(r.aiCanPay);
        AssertJUnit.assertTrue(r.cast);
        AssertJUnit.assertEquals(1, r.tapped);
    }

    @Test
    public void kozileksCommandOverVariableColorlessSources() {
        // A: two plain {C} sources — the shape the 10-04 agreement test already passes
        {
            Game game = initAndCreateGame();
            Player p = game.getPlayers().get(1);
            addCards("Wastes", 2, p);
            addCards("Forest", 2, p);
            Card koz = addCardToZone("Kozilek's Command", p, ZoneType.Hand);
            game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, p);
            game.getAction().checkStaticAbilities();
            for (SpellAbility sa : spellsOf(koz, p)) {
                Row r = read(game, p, sa, true);
                System.out.println("[cmdzone] Kozilek A (2 Wastes + 2 Forest) :: " + r);
                AssertJUnit.assertTrue(r.offered); // the X spell is offered on its X = 0 payability
            }
        }
        // B: the {C}{C} only from a counter-scaled source (game 918: Everflowing Chalice) — the corpus miss shape
        {
            Game game = initAndCreateGame();
            Player p = game.getPlayers().get(1);
            Card chalice = addCard("Everflowing Chalice", p);
            chalice.addCounterInternal(CounterEnumType.CHARGE, 2, p, false, null, null);
            addCards("Forest", 2, p);
            Card koz = addCardToZone("Kozilek's Command", p, ZoneType.Hand);
            game.getPhaseHandler().devModeSet(PhaseType.UPKEEP, p);
            game.getAction().checkStaticAbilities();
            for (SpellAbility sa : spellsOf(koz, p)) {
                Row r = read(game, p, sa, true);
                System.out.println("[cmdzone] Kozilek B (Chalice x2 + 2 Forest) :: " + r);
                AssertJUnit.assertTrue(r.offered); // a counter-scaled {C} source is read at its value
            }
        }
    }
}
