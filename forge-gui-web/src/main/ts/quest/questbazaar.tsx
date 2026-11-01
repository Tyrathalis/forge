import { useEffect } from 'preact/hooks';
import { balance } from '../campaign/campaign';
import { PricedButton } from '../campaign/trade';
import { imageUrl, skinIconUrl, hideBroken } from '../images';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { QuestItemRow } from '../protocol';
import { t } from '../text';
import { CREDITS } from './credits';

/** The most levels drawn as squares. An item with more levels shows its level in words only. */
const MOST_SQUARES = 6;

function Item({ item, stall, credits, actions }: { item: QuestItemRow; stall: string; credits: number; actions: Actions }) {
  return (
    <article class="qu-item">
      <span class="qu-item-pic">
        {item.card ? <img alt="" src={imageUrl(item.card)} onError={hideBroken} />
          : item.icon ? <img alt="" src={skinIconUrl(item.icon)} /> : null}
      </span>
      <div class="qu-item-t">
        <b>{item.name}</b>
        <p>{item.description}</p>
        {item.now && <p class="qu-item-stats">{item.next && item.next !== 'N/A' ? `${item.now} → ${item.next}` : item.now}</p>}
        <div class="qu-item-foot">
          {item.maxLevel > 1 && <>
            {item.maxLevel <= MOST_SQUARES && <span class="cq-owned">{Array.from({ length: item.maxLevel }, (_, i) => <i key={i} class={i < item.level ? 'on' : undefined} />)}</span>}
            <span class="muted">{t('lblWebQuestLevelOf', item.level, item.maxLevel)}</span>
          </>}
          <span class="sp" />
          {item.price >= 0 && (
            <PricedButton label={t('lblBuy')} icon={CREDITS} iconLabel={t('lblCredits')} size={16} cost={item.price} have={credits}
              onClick={() => actions.questBuy(stall, item.name)} />
          )}
        </div>
      </div>
    </article>
  );
}

export function Bazaar({ model, actions }: { model: Model; actions: Actions }) {
  const bazaar = model.questBazaar;
  useEffect(() => { actions.questStall(null); }, []);
  if (!bazaar) return <p class="muted pools-wait">{t('lblWebQuestReading')}</p>;
  const stall = bazaar.stalls.find(s => s.name === bazaar.stall);
  const credits = balance(model.campaignBar, CREDITS);
  return (
    <div class="qu-bazaar">
      <nav class="qu-stalls">
        <span class="cq-kicker">{t('lblBazaar')}</span>
        {bazaar.stalls.map(s => (
          <button key={s.name} class="qu-stall" aria-pressed={s.name === bazaar.stall} onClick={() => actions.questStall(s.name)}>
            <img alt="" src={skinIconUrl(s.icon)} />
            <span><b>{s.displayName}</b><span class="muted">{s.name}</span></span>
          </button>
        ))}
      </nav>
      {stall && (
        <section class="qu-stall-page">
          <header class="qu-stall-head">
            <img alt="" src={skinIconUrl(stall.icon)} />
            <div><span class="cq-kicker">{stall.name}</span><h2>{stall.displayName}</h2><p>{stall.fluff}</p></div>
          </header>
          <div class="qu-items">
            {bazaar.items.map(item => <Item key={item.name} item={item} stall={stall.name} credits={credits} actions={actions} />)}
          </div>
        </section>
      )}
    </div>
  );
}
