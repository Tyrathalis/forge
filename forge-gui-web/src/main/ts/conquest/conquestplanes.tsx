// Planeswalk. Travel to an unlocked plane is free. A locked plane is unlocked for emblems and the player travels there at once

import { useEffect, useState } from 'preact/hooks';
import { artUrl } from '../sleeves';
import { skinIconUrl } from '../images';
import { changeUi } from '../ui';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestPlaneRow } from '../protocol';
import { t } from '../text';
import { Backdrop } from '../dialog';
import { planeswalk } from './planeswalk';

const emblem = (size: number) => <img class="cq-shard" alt={t('lblPlaneswalkEmblems')} style={{ width: `${size}px`, height: `${size}px` }} src={skinIconUrl('IMG_PW_BADGE_COMMON')} />;
const share = (n: number, of: number): string => `${n} / ${of} (${of ? Math.round(100 * n / of) : 0}%)`;

export function Planes({ model, actions }: { model: Model; actions: Actions }) {
  const p = model.conquestPlanes;
  const [chosen, setChosen] = useState<string | null>(null);
  const [asking, setAsking] = useState(false);
  useEffect(() => { actions.conquestPlanes(); }, []);
  if (!p) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  const plane = p.planes.find(x => x.name === chosen) ?? p.planes.find(x => x.current) ?? p.planes[0];
  // After travel the map page is shown, as on mobile
  const go = (unlock: boolean) => {
    setAsking(false);
    planeswalk(plane.name, plane.art, plane.events - plane.conquered);
    actions.conquestPlaneswalk(plane.name, unlock);
    changeUi(u => { u.conquestTab = 'map'; });
  };
  const tile = (x: ConquestPlaneRow) => (
    <button key={x.name} class={x.unlocked ? 'cq-plane-card' : 'cq-plane-card locked'} aria-pressed={x === plane} onClick={() => setChosen(x.name)}>
      <div class="a" style={{ backgroundImage: `url("${artUrl(x.art)}")` }} />
      {x.current && <span class="cq-chip brass tag">{t('lblWebConquestCurrent')}</span>}
      {!x.unlocked && (
        <span class="lock" aria-hidden="true">
          <svg viewBox="0 0 16 16"><rect x="3.5" y="7" width="9" height="6.5" rx="1.5" /><path d="M5.5 7V5.2a2.5 2.5 0 0 1 5 0V7" /></svg>
        </span>
      )}
      <div class="t">
        <b>{x.name}</b>
        {x.unlocked
          ? <><div class="cq-pbar"><i style={{ width: `${x.events ? 100 * x.conquered / x.events : 0}%` }} /></div><span class="l">{share(x.conquered, x.events)}</span></>
          : <span class="l">{t('lblWebConquestEvents', x.events)}<span class="sp" />{emblem(12)}{p.unlockCost}</span>}
      </div>
    </button>
  );
  return (
    <div class="cq-planes">
      <div class="cq-planes-list">
        <h4>{t('lblWebConquestUnlocked')}</h4>
        <div class="cq-planes-grid">{p.planes.filter(x => x.unlocked).map(tile)}</div>
        {p.planes.some(x => !x.unlocked) && <>
          <h4>{t('lblWebConquestLocked')}</h4>
          <div class="cq-planes-grid">{p.planes.filter(x => !x.unlocked).map(tile)}</div>
        </>}
      </div>
      <aside class="cq-side">
        <div class="cq-plane-art" style={{ backgroundImage: `url("${artUrl(plane.art)}")` }}><h3>{plane.name}</h3></div>
        <div class="cq-panel">
          <p class="cq-desc">{plane.description}</p>
          <div class="cq-chips">{plane.regions.map(r => <span key={r} class="cq-chip">{r}</span>)}</div>
        </div>
        <div class="cq-foot">
          {plane.unlocked
            ? <button class="primary cq-big" disabled={plane.current} onClick={() => go(false)}>{plane.current ? t('lblWebConquestCurrent') : t('lblPlaneswalk')}</button>
            : (
              <button class="primary cq-big" disabled={p.emblems < p.unlockCost} onClick={() => setAsking(true)}>
                {t('lblWebConquestUnlock')}<span class="cq-sep">·</span>{emblem(17)}{p.emblems} / {p.unlockCost}
              </button>
            )}
        </div>
      </aside>
      {asking && (
        <Backdrop dismiss={() => setAsking(false)}>
          <div class="dialog">
            <h3>{t('lblWebConquestUnlock')} {plane.name}</h3>
            <p class="cq-exile-total">{t('lblWebConquestUnlockAsk', p.unlockCost, plane.name)}</p>
            <div class="actions">
              <button onClick={() => setAsking(false)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => go(true)}>{t('lblOK')}</button>
            </div>
          </div>
        </Backdrop>
      )}
    </div>
  );
}
