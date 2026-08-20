package forge.ai.anvil;

import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/**
 * Anvil M9 D3 (§3c): census telemetry-only mode for the payment surface
 * (m9-payment-surface-spec.md §8). When enabled, every in-scope
 * payManaCost window runs class enumeration and the census record carries
 * the flag telemetry (classes/conseq/trunc/atoms) — no bridging, heuristic
 * play unchanged in policy. This is the pre-training consequential-rate
 * read the spec owes.
 *
 * Trajectory note: enumeration calls setActivatingPlayer/canPlay like the
 * obs option scan does, so telemetry mode is trajectory-perturbing the
 * same way -obs is (observation-schema-v1 finding) — replay only under
 * the same flag configuration; runs pin it.
 */
public final class PaymentTelemetry {

    /** Set once per worker JVM from the AnvilRun -paytelemetry flag. */
    public static volatile boolean enabled = false;

    private PaymentTelemetry() {
    }

    /** The census record for payManaCost — the generated controller routes
     *  through here (generator REC_OVERRIDES) so the generated file stays
     *  logic-free. Off-mode emits the record unchanged. */
    public static void rec(Game g, Player p, ManaCost toPay, SpellAbility sa, String prompt, boolean effect) {
        if (enabled && !effect && toPay != null && !toPay.isZero()) {
            try {
                // cost-modified windows: out-of-scope v1 (spec §12b) — the
                // raw toPay diverges from what auto actually pays, so goal
                // enumeration would target the wrong cost. Counted, never
                // enumerated. Zero-plan records WITHOUT this kv are the
                // static detector's measured leak (the read's backstop).
                if (PaymentEnumerator.costModified(sa)) {
                    Census.rec(g, p, "payManaCost", "sa", Census.str(sa), "prompt", prompt, "effect", effect,
                            "costmod", true);
                    return;
                }
                PaymentEnumerator.Result r = PaymentEnumerator.enumerate(p, sa, toPay);
                boolean auto = PaymentEnumerator.autoPayable(p, sa, toPay, effect);
                Census.rec(g, p, "payManaCost", "sa", Census.str(sa), "prompt", prompt, "effect", effect,
                        "goals", r.options.size(), "plans", r.planCount,
                        "conseq", PaymentEnumerator.consequential(r, auto),
                        "forced", r.planCount >= 1 && !auto,
                        "trunc", r.goalCapHit, "nodecap", r.nodeCapHit,
                        "atoms", r.atomCount, "srcclasses", r.sourceClassCount,
                        "nodes", r.nodesVisited);
                return;
            } catch (Exception e) {
                // telemetry must never kill a game — the failure is itself
                // telemetry (an enumeration gap the executor genre would
                // have adjudicated); loud in the record, quiet in the game.
                Census.rec(g, p, "payManaCost", "sa", Census.str(sa), "prompt", prompt, "effect", effect,
                        "enumerr", e.getClass().getSimpleName());
                return;
            }
        }
        Census.rec(g, p, "payManaCost", "sa", Census.str(sa), "prompt", prompt, "effect", effect);
    }
}
