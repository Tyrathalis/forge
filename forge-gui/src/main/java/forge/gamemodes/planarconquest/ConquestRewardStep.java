package forge.gamemodes.planarconquest;

import java.util.List;

import forge.gamemodes.planarconquest.ConquestEvent.ChaosWheelOutcome;

/** One thing a won battle gave, in the order it is shown. It has already been applied to the save. */
public record ConquestRewardStep(Kind kind, int amount, ChaosWheelOutcome outcome, List<ConquestReward> cards, int number, int total, boolean chaos) {
    public enum Kind { CONQUER_EMBLEMS, REGION_COMMANDER, WHEEL, BOOSTER, DUPLICATE_SHARDS, SHARDS, EMBLEMS, ALL_PLANES_UNLOCKED, CHAOS_BATTLE }

    public static ConquestRewardStep of(Kind kind) {
        return new ConquestRewardStep(kind, 0, null, null, 0, 0, false);
    }
    public static ConquestRewardStep of(Kind kind, int amount) {
        return new ConquestRewardStep(kind, amount, null, null, 0, 0, false);
    }
    public static ConquestRewardStep commander(ConquestReward commander) {
        return new ConquestRewardStep(Kind.REGION_COMMANDER, 0, null, List.of(commander), 0, 0, false);
    }
    public static ConquestRewardStep wheel(ChaosWheelOutcome outcome) {
        return new ConquestRewardStep(Kind.WHEEL, 0, outcome, null, 0, 0, false);
    }
    public static ConquestRewardStep booster(List<ConquestReward> cards, int number, int total, boolean chaos) {
        return new ConquestRewardStep(Kind.BOOSTER, 0, null, cards, number, total, chaos);
    }
}
