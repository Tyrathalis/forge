package forge.ai.anvil;

import java.util.Map;

import forge.ai.AiBlockController;
import forge.ai.AiController;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.player.Player;

/**
 * M2 D5 combat realizers: apply a bridged AttackMap/BlockMap to the engine's
 * Combat object. Engine legality only, never heuristic judgment (the M0 veto
 * lesson): each assignment is gated by CombatUtil legality and dropped —
 * counted, not repaired — when illegal. Requirements are then satisfied
 * engine-authoritatively:
 *
 * Attacks — the engine RE-ASKS invalid declarations (PhaseHandler do-while on
 * validateAttackers), so the realizer must return valid maps or loop forever.
 * Tiers: model map -> union-merge the missing entries of the best legal
 * attack (AttackConstraints.getLegalAttackers — the requirements oracle AND
 * the AI's own invalid-fallback) -> wholesale best attack. Unpayable attack
 * taxes are pre-dropped (mirrors AiController.declareAttackers per CR 508.1d).
 *
 * Blocks — the engine never validates the controller path (validateBlocks is
 * human-input-only; archaeology 2026-07-13), so ignoring must-block/lure
 * would produce silently rules-illegal games. The realizer forced-adds via
 * mustBlockAnAttacker to a fixed point, then gates on validateBlocks with a
 * terminal AiBlockController fallback (census-counted; expected ~never
 * in-pool). The engine's own post-pass (block costs, can't-block-alone)
 * still applies afterwards.
 */
public final class CombatRealizer {
    private CombatRealizer() {
    }

    public static final class Result {
        public int applied;
        public int dropped;
        public int forced;
        public boolean fallback; // tier-2 best-attack / AiBlockController
    }

    // ---------------------------------------------------------- attackers

    public static Result realizeAttack(Game game, Player p, Combat combat,
            AiController ai, CombatMapAnswer ans) {
        Result r = new Result();
        for (CombatMapAnswer.Assignment a : ans.assignments) {
            Card attacker = a.left != null && !a.left.isPlayer
                    ? game.findById(a.left.entity) : null;
            GameEntity defender = resolveDefender(game, combat, a.right);
            if (attacker == null || defender == null
                    || attacker.getController() != p
                    || combat.isAttacking(attacker)
                    || !CombatUtil.canAttack(attacker, defender)) {
                r.dropped++;
                continue;
            }
            combat.addAttacker(attacker, defender);
            r.applied++;
        }
        // CR 508.1d: attack-cost payment decisions are made at declaration;
        // pre-drop what can't be paid (mirrors the AI path) so the engine's
        // propaganda pass doesn't churn pay-then-cancel.
        ai.removeUnpayableAttackers(combat);

        if (!CombatUtil.validateAttackers(combat)) {
            // tier 1: union-merge the best legal attack's missing entries
            // (requirements like "must attack" satisfied minimally, logged)
            final Map<Card, GameEntity> best =
                    combat.getAttackConstraints().getLegalAttackers().getLeft();
            for (Map.Entry<Card, GameEntity> e : best.entrySet()) {
                if (!combat.isAttacking(e.getKey())
                        && CombatUtil.canAttack(e.getKey(), e.getValue())) {
                    combat.addAttacker(e.getKey(), e.getValue());
                    r.forced++;
                }
            }
            if (!CombatUtil.validateAttackers(combat)) {
                // tier 2: the AI's own invalid-fallback — wholesale best attack
                combat.clearAttackers();
                for (Map.Entry<Card, GameEntity> e : best.entrySet()) {
                    combat.addAttacker(e.getKey(), e.getValue());
                }
                r.fallback = true;
            }
        }
        return r;
    }

    private static GameEntity resolveDefender(Game game, Combat combat,
            CastPlanAnswer.Ref ref) {
        if (ref == null) {
            return null;
        }
        GameEntity target = null;
        if (ref.isPlayer) {
            if (ref.player >= 0 && ref.player < game.getRegisteredPlayers().size()) {
                target = game.getRegisteredPlayers().get(ref.player);
            }
        } else {
            target = game.findById(ref.entity);
        }
        // membership in the engine's defender enumeration is part of legality
        return target != null && combat.getDefenders().contains(target) ? target : null;
    }

    // ---------------------------------------------------------- blockers

    public static Result realizeBlock(Game game, Player defender, Combat combat,
            CombatMapAnswer ans) {
        Result r = new Result();
        for (CombatMapAnswer.Assignment a : ans.assignments) {
            Card blocker = a.left != null && !a.left.isPlayer
                    ? game.findById(a.left.entity) : null;
            Card attacker = a.right != null && !a.right.isPlayer
                    ? game.findById(a.right.entity) : null;
            if (blocker == null || attacker == null
                    || blocker.getController() != defender
                    || !combat.isAttacking(attacker)
                    || !CombatUtil.canBlock(attacker, blocker, combat)) {
                r.dropped++;
                continue;
            }
            combat.addBlocker(attacker, blocker);
            r.applied++;
        }

        // Forced blocks (lure / "blocks each combat if able" / mustBlockCards):
        // nobody else enforces these on the controller path. For each creature
        // still in violation, try legal attackers until the obligation clears;
        // keep an add only if it actually helped.
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 100) {
            changed = false;
            for (Card c : defender.getCreaturesInPlay()) {
                if (!CombatUtil.mustBlockAnAttacker(c, combat, null)) {
                    continue;
                }
                for (Card atk : combat.getAttackers()) {
                    if (!CombatUtil.canBlock(atk, c, combat)) {
                        continue;
                    }
                    combat.addBlocker(atk, c);
                    if (CombatUtil.mustBlockAnAttacker(c, combat, null)) {
                        combat.removeBlockAssignment(atk, c); // didn't help
                    } else {
                        r.forced++;
                        changed = true;
                        break;
                    }
                }
            }
        }

        if (CombatUtil.validateBlocks(combat, defender) != null) {
            // terminal fallback: a requirement this realizer can't satisfy
            // assignment-by-assignment (e.g. min-blocker counts). Hand the
            // whole window to the AI block controller, loudly counted.
            for (Card b : CardLists.filterControlledBy(combat.getAllBlockers(), defender)) {
                combat.undoBlockingAssignment(b);
            }
            new AiBlockController(defender, false).assignBlockersForCombat(combat);
            r.fallback = true;
        }
        return r;
    }
}
