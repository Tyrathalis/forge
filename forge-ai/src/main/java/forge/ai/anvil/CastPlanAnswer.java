package forge.ai.anvil;

import java.util.List;

/**
 * Composite one-shot priority answer (bridge-protocol-v0 CastPlan, rung-1
 * host-level form). Transport-agnostic mirror of the proto message so forge-ai
 * carries no protobuf dependency; GrpcBridge translates proto <-> this.
 */
public final class CastPlanAnswer {
    /** Reference into the observation namespace (entity ids == engine card ids). */
    public static final class Ref {
        public final boolean isPlayer;
        public final int player;    // registered player index
        public final int entity;    // card id
        public final boolean onStack; // proto ns=1: stack entry keyed by host id

        public Ref(boolean isPlayer, int player, int entity, boolean onStack) {
            this.isPlayer = isPlayer;
            this.player = player;
            this.entity = entity;
            this.onStack = onStack;
        }
    }

    /** Label-space index: 0 = pass, i >= 1 = options[i-1] (selectOne convention). */
    public final int optionIndex;
    /** Rung-1 marker: the index identifies a HOST; the realizer disambiguates
     *  among the options sharing that host (model candidates are dedup rows). */
    public final boolean hostLevel;
    /** Flattened target refs, label-extractor order (main SA, then sub-chain). */
    public final List<Ref> targets;
    public final boolean hasX;
    public final int x;

    public CastPlanAnswer(int optionIndex, boolean hostLevel, List<Ref> targets,
            boolean hasX, int x) {
        this.optionIndex = optionIndex;
        this.hostLevel = hostLevel;
        this.targets = targets;
        this.hasX = hasX;
        this.x = x;
    }
}
