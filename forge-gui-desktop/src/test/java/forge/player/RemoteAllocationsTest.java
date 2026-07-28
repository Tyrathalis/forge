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
 * instruction. In a network game the GUI answering is a proxy for a peer, and
 * the host applied whatever integers came back — so a modified client could
 * dictate combat damage, or hand out more than existed by exploiting a
 * {@code getStillToDivide() > 0} test that a negative remainder passes.
 */
public class RemoteAllocationsTest extends AITest {

    private static final int DAMAGE_DEALT = 5;

    @Test
    public void testBoundsArithmetic() {
        // Legal, including the degenerate and under-allocated cases: giving away
        // less than you have only ever costs the allocating player.
        Assert.assertTrue(RemoteAllocations.allocatesAtMost(Arrays.asList(2, 3), 5));
        Assert.assertTrue(RemoteAllocations.allocatesAtMost(Arrays.asList(0, 0), 5));
        Assert.assertTrue(RemoteAllocations.allocatesAtMost(Collections.emptyList(), 5));
        Assert.assertTrue(RemoteAllocations.allocatesAtMost(Arrays.asList(1, 1), 5));

        // Over budget, negative, missing, and absurd shares.
        Assert.assertFalse(RemoteAllocations.allocatesAtMost(Arrays.asList(3, 3), 5));
        Assert.assertFalse(RemoteAllocations.allocatesAtMost(Arrays.asList(-1, 6), 5));
        Assert.assertFalse(RemoteAllocations.allocatesAtMost(Arrays.asList(1, null), 5));
        Assert.assertFalse(RemoteAllocations.allocatesAtMost(
                Arrays.asList(Integer.MAX_VALUE, Integer.MAX_VALUE, 2), 5));

        // The division path needs the strict form: the old test caught an
        // under-allocated remainder but not a negative one.
        Assert.assertTrue(RemoteAllocations.allocatesExactly(Arrays.asList(2, 3), 5));
        Assert.assertFalse(RemoteAllocations.allocatesExactly(Arrays.asList(2, 2), 5));
        Assert.assertFalse(RemoteAllocations.allocatesExactly(Arrays.asList(4, 4), 5));
    }

    /**
     * An {@link IGuiGame} answering combat damage with a fixed map. A dynamic
     * proxy rather than a stub because the interface is wide and this cares
     * about one method.
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
        return map.values().stream().filter(v -> v != null).mapToInt(Integer::intValue).sum();
    }

    /** Two blockers reaches the branch that defers the split to the player. */
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

    /**
     * Drives {@code applyDividedAllocation} directly; going through
     * {@code chooseTargetsFor} would exercise the targeting UI rather than the
     * rule, which is why the seam exists.
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
    public void testBoundsDividedAllocation() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card t1 = addCard("Grizzly Bears", ai);
        final Card t2 = addCard("Grizzly Bears", ai);
        final List<GameEntity> targets = Arrays.asList(t1, t2);

        final Map<Object, Integer> overAllocated = new HashMap<>();
        overAllocated.put(GameEntityView.get(t1), 3);
        overAllocated.put(GameEntityView.get(t2), 3);
        Assert.assertFalse(applyDivision(game, human, overAllocated, targets, 4),
                "Peer divided 6 out of a budget of 4 — a negative remainder is not > 0, "
                        + "so the old test passed exactly the case it had to stop");

        // Only one target named; the other recorded a null share and corrupted
        // the allocation later rather than failing here.
        final Map<Object, Integer> missingShare = new HashMap<>();
        missingShare.put(GameEntityView.get(t1), 4);
        Assert.assertFalse(applyDivision(game, human, missingShare, targets, 4),
                "A division omitting a target must be rejected");

        final Map<Object, Integer> honest = new HashMap<>();
        honest.put(GameEntityView.get(t1), 1);
        honest.put(GameEntityView.get(t2), 3);
        Assert.assertTrue(applyDivision(game, human, honest, targets, 4),
                "An exact, non-negative division must still be accepted");
    }

    @Test
    public void testBoundsCombatDamage() {
        final Game game = initAndCreateGame();
        final Player human = game.getPlayers().get(0);
        final Player ai = game.getPlayers().get(1);

        final Card attacker = addCard("Grizzly Bears", human);
        final Card blockerA = addCard("Grizzly Bears", ai);
        final Card blockerB = addCard("Grizzly Bears", ai);

        final Map<CardView, Integer> overAllocated = new HashMap<>();
        overAllocated.put(CardView.get(blockerA), 999);
        overAllocated.put(CardView.get(blockerB), 999);
        Map<Card, Integer> assigned =
                assignWith(overAllocated, game, human, ai, attacker, blockerA, blockerB);
        Assert.assertNotNull(assigned, "Host should still produce an assignment");
        Assert.assertTrue(total(assigned) <= DAMAGE_DEALT,
                "Host applied " + total(assigned) + " damage from a creature dealing "
                        + DAMAGE_DEALT);

        // A negative share to one blocker buys extra for the other while a naive
        // sum still looks within budget.
        final Map<CardView, Integer> negative = new HashMap<>();
        negative.put(CardView.get(blockerA), -5);
        negative.put(CardView.get(blockerB), DAMAGE_DEALT + 5);
        assigned = assignWith(negative, game, human, ai, attacker, blockerA, blockerB);
        Assert.assertNotNull(assigned);
        for (final Integer share : assigned.values()) {
            Assert.assertTrue(share >= 0, "Host applied a negative damage assignment: " + share);
        }

        // A null key means "the defending player". Without trample this attacker
        // cannot reach past its blockers, so the sum being legal is not enough.
        final Map<CardView, Integer> pastBlockers = new HashMap<>();
        pastBlockers.put(null, DAMAGE_DEALT);
        assigned = assignWith(pastBlockers, game, human, ai, attacker, blockerA, blockerB);
        Assert.assertNotNull(assigned);
        Assert.assertNull(assigned.get(null),
                "Damage was routed to the defender past two blockers by a creature "
                        + "without trample");
    }

    /** Bounding must not degrade honest play: a legal split applies as sent. */
    @Test
    public void testHonoursALegalSplit() {
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
