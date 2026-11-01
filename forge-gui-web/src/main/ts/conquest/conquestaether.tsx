// The Aether. The server decides which cards match the filters, what a pull costs and which card is pulled

import { useEffect, useRef, useState } from 'preact/hooks';
import { skinIconUrl, imageUrl } from '../images';
import { isRare } from './conquestpack';
import { balance } from '../campaign/campaign';
import { PricedButton } from '../campaign/trade';
import { ColourToggles, Pip, Pips } from '../symbols';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestAetherState, ConquestOption, PackCard } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';
import { reducedMotion } from '../dom';

const RARITIES: TextKey[] = ['lblCommon', 'lblUncommon', 'lblRare', 'lblMythic'];
/** The number of cards pulled this visit that stay in view. */
const MOST_RECENT = 12;

const shard = (size: number) => <img class="cq-shard" alt={t('lblAetherShards')} style={{ width: `${size}px`, height: `${size}px` }} src={skinIconUrl('IMG_AETHER_SHARD')} />;

export function Aether({ model, actions }: { model: Model; actions: Actions }) {
  const a = model.conquestAether;
  const [recent, setRecent] = useState<PackCard[]>([]);
  /** The pulled card the player sent to the collection. The stage shows the orb again until the next pull. */
  const [sent, setSent] = useState<PackCard | null>(null);
  useEffect(() => { actions.conquestAether({ colors: '', type: '', rarity: '', cmc: '', pull: false }); }, []);
  useEffect(() => { if (a?.pulled) setRecent(r => [a.pulled!, ...r].slice(0, MOST_RECENT)); }, [a?.pulled]);
  // Each pull is counted in the render that first shows it, so its animation starts with the new card. A card shown before it fades out under it
  const shown = useRef<PackCard | null>(null);
  const turn = useRef<{ card: PackCard | null; count: number; before: PackCard | null }>({ card: null, count: 0, before: null });
  if (a?.pulled && a.pulled !== turn.current.card) turn.current = { card: a.pulled, count: turn.current.count + 1, before: shown.current };
  const card = a?.pulled && a.pulled !== sent ? a.pulled : null;
  shown.current = card;
  if (!a) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const ask = (patch: Partial<Pick<ConquestAetherState, 'colors' | 'type' | 'rarity' | 'cmc'>>, pull = false) =>
    actions.conquestAether({ colors: a.colors, type: a.type, rarity: a.rarity, cmc: a.cmc, pull, ...patch });
  const toggle = (letter: string) => ask({ colors: [...'WUBRG'].filter(c => (c === letter) !== a.colors.includes(c)).join('') });
  // The price is on the button, so the button is disabled when the player cannot pay for a pull
  const short = a.cost > balance(model.campaignBar, 'IMG_AETHER_SHARD');
  const pull = <PricedButton label={t('lblWebConquestPull')} icon="IMG_AETHER_SHARD" iconLabel={t('lblAetherShards')} size={18} cost={a.cost}
    have={balance(model.campaignBar, 'IMG_AETHER_SHARD')} disabled={!a.cost || !a.strict} cls="cq-ae-pull" onClick={() => ask({}, true)} />;
  return (
    <div class="cq-aether">
      <aside class="cq-ae-filters">
        <div>
          <h4>{t('lblColor')}<span>{t('lblWebConquestPlayableIn')} <span class="pips">{a.commanderColors ? <Pips colors={a.commanderColors} /> : <Pip letter="C" />}</span></span></h4>
          <ColourToggles label={t('lblColor')} pressed={c => a.colors.includes(c)} toggle={toggle} />
        </div>
        <Options title={t('lblType')} options={a.types} chosen={a.type} pick={type => ask({ type })} />
        <Options title={t('lblRarity')} options={a.rarities} chosen={a.rarity} pick={rarity => ask({ rarity })} />
        <Options title={t('lblCMC')} options={a.cmcs} chosen={a.cmc} pick={cmc => ask({ cmc })} two />
      </aside>
      <div class="cq-ae-stage">
        {card
          ? (
            <div class="cq-ae-card">
              <div key={`card${turn.current.count}`} class={`cq-ae-box${turn.current.before ? '' : ' from-orb'}${isRare(card.rarity) ? ' rare' : ''}${card.rarity === 'MythicRare' ? ' mythic' : ''}`}>
                {turn.current.before
                  ? <img class="cq-ae-ghost" alt="" src={imageUrl(turn.current.before.image)} />
                  : <span class="cq-ae-orb cq-ae-ghost"><img alt="" src={skinIconUrl('IMG_MULTIVERSE')} /></span>}
                <i class="rise" />
                <button class="cq-ae-face" onClick={e => { toCollection(e.currentTarget.querySelector('img')!); setSent(card); }}>
                  <span class="cq-ae-turn"><span class="cq-ae-back" /><img alt={card.name} src={imageUrl(card.image)} data-image={card.image} /></span>
                </button>
              </div>
              <div key={`name${turn.current.count}`} class="what"><span class="new-card">{t('lblNew')}</span><b>{card.name}</b></div>
              {pull}
            </div>
          )
          : (
            <div class="cq-ae-core">
              <span class="cq-ae-orb"><img alt="" src={skinIconUrl('IMG_MULTIVERSE')} /></span>
              <div class="cq-chips">
                {RARITIES.map((name, i) => <span key={name} class="cq-chip">{a.byRarity[i]} {t(name)}</span>)}
              </div>
              {pull}
            </div>
          )}
        {(a.problem || (short && a.cost > 0)) && <p class="cq-warn">{a.problem ?? t('lblWebConquestTooFewShards')}</p>}
        <div class="cq-ae-count">{formatNumber(a.matching)} / {formatNumber(a.locked)}</div>
        <div class="cq-ae-recent">
          {recent.map((c, i) => <img key={`${c.image}:${recent.length - i}`} alt={c.name} src={imageUrl(c.image)} data-image={c.image} />)}
        </div>
      </div>
    </div>
  );
}

/** Flies a copy of the pulled card into the Collection tab, which then lights up as the card arrives. */
function toCollection(img: HTMLImageElement): void {
  const tab = document.querySelector<HTMLElement>('.cq-tab[data-tab="collection"]');
  if (!tab || reducedMotion()) return;
  const from = img.getBoundingClientRect(), to = tab.getBoundingClientRect();
  const face = new Image();
  face.src = img.src;
  face.alt = '';
  Object.assign(face.style, { position: 'fixed', left: `${from.left}px`, top: `${from.top}px`, width: `${from.width}px`, height: `${from.height}px`,
    zIndex: '60', pointerEvents: 'none', borderRadius: '12px', boxShadow: '0 16px 44px #000c' });
  document.body.append(face);
  const dx = to.left + to.width / 2 - (from.left + from.width / 2), dy = to.top + to.height / 2 - (from.top + from.height / 2);
  void face.animate([{ transform: 'none', opacity: 1 }, { transform: `translate(${dx}px, ${dy}px) scale(.1)`, opacity: .3 }],
    { duration: 600, easing: 'cubic-bezier(.5,0,.3,1)' }).finished.then(() => {
    face.remove();
    tab.animate([{ offset: 0, color: '#f2c344', textShadow: '0 0 12px #f2c344aa' }], { duration: 700, easing: 'ease-out' });
  });
}

/** One of the filters, shown as tiles with one chosen. A rarity tile shows what a pull at that rarity costs. */
function Options({ title, options, chosen, pick, two }: { title: string; options: ConquestOption[]; chosen: string; pick: (key: string) => void; two?: boolean }) {
  return (
    <div>
      <h4>{title}</h4>
      <div class={two ? 'cq-opts two' : 'cq-opts'} role="radiogroup" aria-label={title}>
        {options.map(o => (
          <button key={o.key} class="cq-opt" role="radio" aria-checked={o.key === chosen} onClick={() => pick(o.key)}>
            <b>{o.label}</b>
            {o.cost != null && <span class="r">{shard(13)}{formatNumber(o.cost)}</span>}
          </button>
        ))}
      </div>
    </div>
  );
}
