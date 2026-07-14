package forge.ai.anvil;

import java.util.Collections;
import java.util.List;

/**
 * Combat declaration answer (bridge-protocol-v0 AttackMap/BlockMap, M2 D5
 * entity-ref form). Transport-agnostic mirror of the proto messages so
 * forge-ai carries no protobuf dependency; GrpcBridge translates.
 *
 * One struct serves both shapes: for an AttackMap, left = attacker (declarer's
 * creature), right = defender (player or permanent); for a BlockMap, left =
 * blocker, right = the attacking creature. Refs are observation-namespace
 * (entity ids == engine card ids; players by registered index) — the realizer
 * adjudicates legality per assignment (ADR-0005: drop + telemeter, never trust).
 */
public final class CombatMapAnswer {
    public static final class Assignment {
        public final CastPlanAnswer.Ref left;
        public final CastPlanAnswer.Ref right;

        public Assignment(CastPlanAnswer.Ref left, CastPlanAnswer.Ref right) {
            this.left = left;
            this.right = right;
        }
    }

    public final List<Assignment> assignments;

    public CombatMapAnswer(List<Assignment> assignments) {
        this.assignments = assignments;
    }

    /** The transport-failure / server-fallback answer: declare nothing.
     *  (The realizer's validate tiers repair upward only if the rules
     *  require it — never heuristic substitution on a bridged tag.) */
    public static CombatMapAnswer empty() {
        return new CombatMapAnswer(Collections.emptyList());
    }
}
