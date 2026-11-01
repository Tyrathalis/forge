import { TYPES as CARD_TYPES } from '../deck/catalogue';
import { TradePage, type TradeWords } from '../campaign/trade';
import type { Actions } from '../actions';
import type { Model } from '../model';
import { t } from '../text';

/** Conquest's planes contain no battle cards, so the type filter leaves them out. */
const TYPES = CARD_TYPES.filter(([id]) => id !== 'battle');
/** The token that marks where mobile's sentences put the shard symbol. */
const SHARD = '{AE}';

const cards = (n: number): string => (n === 1 ? t('lblCard') : t('lblCards'));

export function Collection({ model, actions }: { model: Model; actions: Actions }) {
  const words: Record<string, TradeWords> = {
    collection: {
      label: t('lblCollection'),
      verb: n => (n === 1 ? t('lblExileCard') : t('lblExileNCard', n)),
      row: price => t('lblExileForNAE', price, SHARD),
      confirm: (n, total) => t('lblExileFollowCardsToReceiveNAE', cards(n), SHARD, total).trim(),
      pays: true,
    },
    exile: {
      label: t('lblExile'),
      verb: n => (n === 1 ? t('lblRetrieveCard') : t('lblRetrieveNCard', n)),
      row: price => t('lblRetrieveForNAE', price, SHARD),
      confirm: (n, total) => t('lblSpendAECostToRetrieveCardsFromExile', SHARD, total, cards(n)).trim(),
    },
  };
  return <TradePage model={model} actions={actions} icon="IMG_AETHER_SHARD" iconLabel={t('lblAetherShards')} token={SHARD} words={words}
    types={TYPES} groupLabel={t('lblAllPlanes')} blocked={t('lblWebConquestInUse')} />;
}
