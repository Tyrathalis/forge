import { TYPES } from '../deck/catalogue';
import { TradePage, pickOf, type Pick, type TradeWords } from '../campaign/trade';
import { imageUrl, hideBroken } from '../images';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { Product } from '../protocol';
import { t } from '../text';
import { CREDITS, Price } from './credits';

/** The token that marks where a sentence shows the credits coin. */
const COIN = '{CR}';

/** The shop's packs, boxes and decks. Each one is picked whole, the same way as a card. */
function Products({ products, picked, toggle }: { products: Product[]; picked: Map<string, Pick>; toggle: (pick: Pick) => void }) {
  if (!products.length) return null;
  return (
    <div class="qu-products">
      <span class="qu-products-head">{t('lblWebQuestProducts')}</span>
      {products.map(p => (
        <button key={p.key} class="qu-product" aria-pressed={picked.has(p.key)}
          onClick={() => toggle({ key: p.key, name: p.name, value: p.price, count: 1, most: 1 })}>
          <span class="qu-product-pic"><img loading="lazy" alt="" src={imageUrl(p.image)} onError={hideBroken} /></span>
          <span class="qu-product-kind">{p.kind}</span>
          <b>{p.name}</b>
          <Price n={p.price} />
        </button>
      ))}
    </div>
  );
}

export function Shop({ model, actions }: { model: Model; actions: Actions }) {
  const words: Record<string, TradeWords> = {
    shop: {
      label: t('lblCardsForSale'),
      verb: n => t('lblWebQuestBuyN', n),
      row: price => `${COIN} ${price}`,
      confirm: (_, total) => t('lblWebQuestBuyConfirm', COIN, total),
      count: n => t('lblWebCatalogueLeft', n),
    },
    inventory: {
      label: t('lblYourCards'),
      verb: n => t('lblWebQuestSellN', n),
      row: price => `${COIN} ${price}`,
      confirm: (_, total) => t('lblWebQuestSellConfirm', COIN, total),
      pays: true,
      count: n => `×${n}`,
    },
  };
  const extras = model.trading?.extras ?? [];
  return <TradePage model={model} actions={actions} icon={CREDITS} iconLabel={t('lblCredits')} token={COIN} words={words}
    types={TYPES} blocked="" top={(source, picked, toggle, askAbout) => (source === 'shop'
      ? <Products products={model.trading?.products ?? []} picked={picked} toggle={toggle} />
      : (
        <div class="qu-products qu-extras">
          <button disabled={!extras.length} onClick={() => askAbout(extras.map(row => pickOf(row, row.count ?? 1)))}>{t('lblSellAllExtras')}</button>
        </div>
      ))} />;
}
