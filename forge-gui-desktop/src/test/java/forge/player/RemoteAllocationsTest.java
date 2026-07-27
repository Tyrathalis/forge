package forge.player;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardView;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.gui.interfaces.IGuiGame;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F-05: the host must treat an allocation reply as a proposal, not an
 * instruction.
 *
 * <p>{@code PlayerControllerHuman} asks its GUI to split a fixed budget, and
 * in a network game that GUI is a proxy for a remote peer. The host applied
 * whatever integers came back: combat damage was copied into the damage map
 * after checking only that the <i>key</i> was a known blocker, and the divided
 * allocation path tested {@code getStillToDivide() > 0}, which rejects
 * under-allocation but waves through a negative remainder — handing out more
 * than existed.
 *
 * <p>This is a cheating bug rather than an RCE, which is the distinction that
 * matters least to the victim: it needs no exotic tooling, only a modified
 * client, and it is invisible from the other side of the table.
 */
public class RemoteAllocationsTest extends AITest {

    // ------------------------------------------------------------------
    // The arithmetic guard itself
    // ------------------------------------------------------------------

    @Test
    public void testAcceptsALegalSplit() {
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(2, 3), 5), 5);
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(0, 0), 5), 0);
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Collections.emptyList(), 5), 0);
    }

    @Test
    public void testAcceptsUnderAllocation() {
        // Only ever costs the allocating player — a bad play, not an exploit.
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(1, 1), 5), 2);
    }

    @Test
    public void testRejectsOverAllocation() {
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(3, 3), 5),
                RemoteAllocations.ILLEGAL);
    }

    @Test
    public void testRejectsNegativeShare() {
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(-1, 6), 5),
                RemoteAllocations.ILLEGAL);
    }

    @Test
    public void testRejectsMissingShare() {
        // A client that omits a key left a null share recorded against that
        // target; addDividedAllocation takes an Integer and accepts it.
        Assert.assertEquals(RemoteAllocations.totalIfLegal(Arrays.asList(1, null), 5),
                RemoteAllocations.ILLEGAL);
    }

    /**
     * Extreme values are rejected rather than wrapping.
     *
     * <p>Named honestly: this does <i>not</i> demonstrate overflow handling.
     * Because the budget is tested inside the loop, the first oversized share
     * trips it and the sum never gets near {@code Integer.MAX_VALUE} — so this
     * passes with either an {@code int} or a {@code long} accumulator. It
     * pins the behaviour a client sending absurd numbers should see; the
     * {@code long} guards a refactor that moves the test out of the loop,
     * which no unit test can force today.
     */
    @Test
    public void testRejectsExtremeShares() {
        Assert.assertEquals(
                RemoteAllocations.totalIfLegal(
                        Arrays.asList(Integer.MAX_VALUE, Integer.MAX_VALUE, 2), 5),
                RemoteAllocations.ILLEGAL);
    }

    /**
     * The divided-allocation path guarded over-allocation with
     * {@code getStillToDivide() > 0}, which only catches an under-allocated
     * remainder. Handing out more than existed leaves the remainder negative,
     * and negative is not greater than zero — so the guard passed exactly the
     * case it needed to stop. allocatesExactly() is the replacement predicate,
     * and it must be strict in both directions.
     */
    @Test
    public void testAllocatesExactlyIsStrictBothWays() {
        Assert.assertTrue(RemoteAllocations.allocatesExactly(Arrays.asList(2, 3), 5));
        Assert.assertFalse(RemoteAllocations.allocatesExactly(Arrays.asList(2, 2), 5));
        Assert.assertFalse(RemoteAllocations.allocatesExactly(Arrays.asList(4, 4), 5));
    }

    // ------------------------------------------------------------------
    // The enforcement point, driven through the real controller
    // ------------------------------------------------------------------

    private static final int DAMAGE_DEALT = 5;

    /**
     * An {@link IGuiGame} that answers combat damage with a fixed map and
     * returns defaults for everything else. A dynamic proxy rather than a
     * hand-written stub because the interface is wide and this test cares
     * about exactly one method.
     */
    private static IGuiGame guiAnswering(final Map<CardView, Integer> reply) {
        return (IGuiGame) Proxy.newProxyInstance(
                IGuiGame.class.getClassLoader(),
                new Class<?>[] { IGuiGame.class },
                (proxy, method, args) -> {
                    if ("assignCombatDamage".equals(method.getName())) {
                        return reply;
                    }
                    final Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) { return false; }
                    if (ret == int.class) { return 0; }
                    return null;
                });
    }

    private static int total(final Map<Card, Integer> map) {
        int n = 0;
        for (final Integer v : map.values()) {
            if (v != null) {
                n += v;
            }
        }
        return n;
    }

    /**
     * Two blockers is enough to reach the branch that defers the split to the
     * player — which is the branch a modified client gets to answer.
     */
    private Map<Card, Integer> assignWith(final Map<CardView, Integer> reply,
            final Game game, final Player human, final Player ai,
            final Card attacker, final Card blockerA, final Card blockerB) {
        final PlayerControllerHuman controller =
                new PlayerControllerHuman(game, human, human.getLobbyPlayer());
        controller.setGui(guiAnswering(reply));

        final CardCollection blockers = new CardCollection();
        blockers.add(blockerA);
        blockers.add(blockerB);

        return controller.assignCombatDamage(attacker, blockers, null, DAMAGE_DEALT, ai, true);
    }

    // ------------------------------------------------------------------
    // Divided allocation (counters, divided damage, shields)
    // ------------------------------------------------------------------

    /**
     * Drives {@code applyDividedAllocation} directly. Going through
     * {@code chooseTargetsFor} would mean driving target selection through a
     * stub GUI, which exercises the targeting UI rather than the rule under
     * test; the seam exists so this rule can be pinned on its own.
     */
    private boolean applyDivision(final Game game, final Player human,
            final Map<Object, Integer> proposed, final List<GameEntity> targets, final int amount) {
        final Card source = addCard("Grizzly Bears", human);
        final SpellAbility sa = source.getFirstSpellAbility();
        final PlayerControllerHuman controller =
                new PlayerControllerHuman(game, human, human.getLobbyPlayer());
        controller.setGui(guiAnswering(Collections.emptyMap()));
        return controller.applyDividedAllocation(sa, targets, proposed, amount);
    }

    @Test
    public void testRejectsDivisionExceedingTheBudget() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card t1 = addCard("Grizzly Bears", ai);
        final Card t2 = addCard("Grizzly Bears", ai);
        final List<GameEntity> targets = Arrays.asList(t1, t2);

        final Map<Object, Integer> poisoned = new HashMap<>();
        poisoned.put(GameEntityView.get(t1), 3);
        poisoned.put(GameEntityView.get(t2), 3);

        Assert.assertFalse(applyDivision(game, human, poisoned, targets, 4),
                "Peer divided 6 out of a budget of 4 — the old getStillToDivide() > 0 test "
                        + "let a negative remainder through");
    }

    @Test
    public void testRejectsDivisionWithAMissingShare() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card t1 = addCard("Grizzly Bears", ai);
        final Card t2 = addCard("Grizzly Bears", ai);
        final List<GameEntity> targets = Arrays.asList(t1, t2);

        // Only one target named; the other used to unbox null and throw.
        final Map<Object, Integer> poisoned = new HashMap<>();
        poisoned.put(GameEntityView.get(t1), 4);

        Assert.assertFalse(applyDivision(game, human, poisoned, targets, 4),
                "A division omitting a target must be rejected, not crash the host");
    }

    @Test
    public void testAcceptsALegalDivision() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card t1 = addCard("Grizzly Bears", ai);
        final Card t2 = addCard("Grizzly Bears", ai);
        final List<GameEntity> targets = Arrays.asList(t1, t2);

        final Map<Object, Integer> honest = new HashMap<>();
        honest.put(GameEntityView.get(t1), 1);
        honest.put(GameEntityView.get(t2), 3);

        Assert.assertTrue(applyDivision(game, human, honest, targets, 4),
                "An exact, non-negative division must still be accepted");
    }

    @Test
    public void testHostRejectsOverAllocatedCombatDamage() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card attacker = addCard("Grizzly Bears", human);
        final Card blockerA = addCard("Grizzly Bears", ai);
        final Card blockerB = addCard("Grizzly Bears", ai);

        final Map<CardView, Integer> poisoned = new HashMap<>();
        poisoned.put(CardView.get(blockerA), 999);
        poisoned.put(CardView.get(blockerB), 999);

        final Map<Card, Integer> assigned =
                assignWith(poisoned, game, human, ai, attacker, blockerA, blockerB);

        Assert.assertNotNull(assigned, "Host should still produce an assignment");
        Assert.assertTrue(total(assigned) <= DAMAGE_DEALT,
                "Host applied " + total(assigned) + " damage from a creature dealing "
                        + DAMAGE_DEALT + " — a modified client dictated the damage map");
    }

    @Test
    public void testHostRejectsNegativeCombatDamage() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card attacker = addCard("Grizzly Bears", human);
        final Card blockerA = addCard("Grizzly Bears", ai);
        final Card blockerB = addCard("Grizzly Bears", ai);

        // A negative share to one blocker buys extra for the other while a
        // naive sum still looks within budget.
        final Map<CardView, Integer> poisoned = new HashMap<>();
        poisoned.put(CardView.get(blockerA), -5);
        poisoned.put(CardView.get(blockerB), DAMAGE_DEALT + 5);

        final Map<Card, Integer> assigned =
                assignWith(poisoned, game, human, ai, attacker, blockerA, blockerB);

        Assert.assertNotNull(assigned);
        for (final Map.Entry<Card, Integer> e : assigned.entrySet()) {
            Assert.assertTrue(e.getValue() >= 0,
                    "Host applied a negative damage assignment: " + e.getValue());
        }
        Assert.assertTrue(total(assigned) <= DAMAGE_DEALT,
                "Host applied more damage than the creature deals");
    }

    /**
     * The other half of the claim: bounding must not degrade honest play. A
     * legal split has to be applied exactly as sent, not replaced by the
     * default assignment.
     */
    @Test
    public void testHostHonoursALegalSplit() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card attacker = addCard("Grizzly Bears", human);
        final Card blockerA = addCard("Grizzly Bears", ai);
        final Card blockerB = addCard("Grizzly Bears", ai);

        final Map<CardView, Integer> honest = new HashMap<>();
        honest.put(CardView.get(blockerA), 2);
        honest.put(CardView.get(blockerB), DAMAGE_DEALT - 2);

        final Map<Card, Integer> assigned =
                assignWith(honest, game, human, ai, attacker, blockerA, blockerB);

        Assert.assertNotNull(assigned);
        Assert.assertEquals(assigned.get(blockerA), Integer.valueOf(2),
                "A legal split must be applied as sent");
        Assert.assertEquals(assigned.get(blockerB), Integer.valueOf(DAMAGE_DEALT - 2));
    }
}
