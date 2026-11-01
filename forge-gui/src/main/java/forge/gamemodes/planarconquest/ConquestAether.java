package forge.gamemodes.planarconquest;

import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.Iterables;

import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.gamemodes.planarconquest.ConquestPreferences.CQPref;
import forge.gamemodes.planarconquest.ConquestUtil.CMCFilter;
import forge.gamemodes.planarconquest.ConquestUtil.RarityFilter;
import forge.gamemodes.planarconquest.ConquestUtil.TypeFilter;
import forge.item.PaperCard;
import forge.util.Aggregates;
import forge.util.IterableUtil;
import forge.util.MyRandom;

/** The Aether: which of the current plane's locked cards a filter allows, what a pull costs, and the pull. */
public final class ConquestAether {
    private ConquestAether() {}

    public record Filter(ColorSet colors, TypeFilter type, RarityFilter rarity, CMCFilter cmc) {
        public static Filter startingFor(ConquestCommander commander) {
            return new Filter(commander.getCard().getRules().getColorIdentity(), TypeFilter.CREATURE, RarityFilter.COMMON, CMCFilter.CMC_LOW_MID);
        }

        private boolean test(PaperCard card) {
            return card.getRules().getColorIdentity().hasNoColorsExcept(colors) && type.test(card) && rarity.test(card) && cmc.test(card);
        }
    }

    public record Pools(Set<PaperCard> locked, Set<PaperCard> filtered, Set<PaperCard> strict) {}

    public static Pools pools(ConquestData model, Filter filter) {
        final Set<PaperCard> locked = new HashSet<>();
        for (PaperCard card : model.getCurrentPlane().getCardPool().getAllCards()) {
            if (!model.hasUnlockedCard(card) && !card.getRules().getType().isBasicLand()) { //don't allow pulling basic lands
                locked.add(card);
            }
        }

        final ColorSet commanderColors = model.getSelectedCommander().getCard().getRules().getColorIdentity();
        final CardRarity selectedRarity = filter.rarity().getRarity();
        final Set<PaperCard> filtered = new HashSet<>();
        final Set<PaperCard> strict = new HashSet<>();
        for (PaperCard card : locked) {
            if (filter.test(card)) {
                filtered.add(card);
                if (selectedRarity == card.getRarity()) {
                    strict.add(card);
                }
            } else if (card.getRarity() == CardRarity.BasicLand
                    && !card.isVeryBasicLand()
                    && !card.getName().equals("Wastes")
                    && !MagicColor.Constant.SNOW_LANDS.contains(card.getName())
                    && selectedRarity == CardRarity.Common
                    && filter.cmc() == CMCFilter.CMC_LOW
                    && card.getRules().getColorIdentity().hasNoColorsExcept(commanderColors)) {
                filtered.add(card);
            }
        }
        return new Pools(locked, filtered, strict);
    }

    public static int cost(Pools pools, Filter filter) {
        if (pools.filtered().isEmpty()) {
            return 0;
        }
        return ConquestUtil.getShardValue(filter.rarity().getRarity(), CQPref.AETHER_BASE_PULL_COST);
    }

    /** Spends, unlocks and saves. Null, with nothing changed, when nothing can be pulled or the shards are short. */
    public static PaperCard pull(ConquestData model, Filter filter) {
        final Pools pools = pools(model, filter);
        if (pools.filtered().isEmpty() || pools.strict().isEmpty()) { return null; }

        final int shardCost = cost(pools, filter);
        if (model.getAEtherShards() < shardCost) { return null; }

        //determine final pool to pull from based on rarity odds
        Iterable<PaperCard> rewardPool;
        CardRarity minRarity = filter.rarity().getRarity();
        CardRarity rarity = filter.rarity().getRarity(MyRandom.getRandom().nextDouble());
        while (true) {
            final CardRarity allowedRarity = rarity;
            rewardPool = IterableUtil.filter(pools.filtered(), card -> allowedRarity == card.getRarity()
                    || allowedRarity == CardRarity.Rare && card.getRarity() == CardRarity.Special
                    || allowedRarity == CardRarity.Common && card.getRarity() == CardRarity.BasicLand); // allow L rarity for Common (except very basic lands)
            if (Iterables.isEmpty(rewardPool)) { //if pool is empty, must reduce rarity and try again
                if (rarity == minRarity) {
                    return null;
                }
                switch (rarity) {
                case MythicRare:
                    rarity = CardRarity.Rare;
                    continue;
                case Rare:
                    rarity = CardRarity.Uncommon;
                    continue;
                case Uncommon:
                    rarity = CardRarity.Common;
                    continue;
                default:
                    break;
                }
            }
            break;
        }

        PaperCard card = Aggregates.random(rewardPool);
        if (card == null) { return null; } //shouldn't happen, but prevent crash if it does

        model.spendAEtherShards(shardCost);
        model.unlockCard(card);
        model.saveData();
        return card;
    }
}
