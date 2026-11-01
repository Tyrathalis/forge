// Only a tile's picture shows its state. White is used only for the player's place and the selected tile

import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { artUrl } from '../sleeves';
import { skinIconUrl } from '../images';
import { Pips } from '../symbols';
import { BH, G, SPINE, TH, TW, Y0, isFolded, layout } from './conquestlayout';
import { DeckRow, SidePanel } from '../campaign/campaign';
import { changeUi, ui } from '../ui';
import type { Actions } from '../actions';
import type { ConquestCell, ConquestPlace, ConquestState } from '../protocol';
import { t, type TextKey } from '../text';
import { byId, reducedMotion } from '../dom';
import { dimIntoMatch } from '../match/entry';
import { isPortrait } from '../form';

/** The time the marker takes for one step, the same as mobile's token. */
const STEP_MS = 500;
/** When the counts go up after a win, and when the win animation ends. They match the .cq-map.won rules in conquest.css. */
const COUNT_MS = 2000;
const WON_MS = 2600;
const HOVER_W = 232;

const key = (p: ConquestPlace): string => `${p.region}:${p.row}:${p.col}`;
const same = (a: ConquestPlace, b: ConquestPlace): boolean => a.region === b.region && a.row === b.row && a.col === b.col;

const VARIANT_NAMES: Record<string, TextKey> = { Commander: 'lblCommander', Vanguard: 'lblVanguard', Planeswalker: 'lblPlaneswalker', Planechase: 'lblPlanechase' };
const VARIANT_DESC: Record<string, TextKey> = { Commander: 'lblCommanderDesc', Vanguard: 'lblVanguardDesc', Planeswalker: 'lblPlaneswalkerDesc', Planechase: 'lblPlanechaseDesc' };
const VARIANT_ICONS: Record<string, string> = { Commander: 'IMG_COMMANDER', Vanguard: 'IMG_ZONE_AVATAR', Planeswalker: 'IMG_PLANESWALKER', Planechase: 'IMG_ZONE_PLANAR' };
const variantName = (v: string): string => (VARIANT_NAMES[v] ? t(VARIANT_NAMES[v]) : v);

const fight = (actions: Actions) => dimIntoMatch(byId('campaign'), () => actions.conquestBattle());

/** Each tile's state the last time this plane's map was shown. The map compares against it after a battle to find what the win changed. */
let lastSeen: { plane: string; states: Map<string, string> } | null = null;

/** held is true while a reward is revealed over the map. The win animation waits for it to close. */
export function ConquestMap({ actions, state, held }: { actions: Actions; state: ConquestState; held: boolean }) {
  const cells = new Map(state.cells.map(c => [key(c), c]));
  // The tile just won and the tiles it unlocked, kept until the animation has played
  const [won, setWon] = useState<{ tile: ConquestCell; opened: string[]; revealed: boolean; counted: boolean } | null>(null);
  useLayoutEffect(() => {
    const states = new Map(state.cells.map(c => [key(c), c.state]));
    const before = lastSeen?.plane === state.plane ? lastSeen.states : null;
    lastSeen = { plane: state.plane, states };
    const tile = before && state.cells.find(c => c.state === 'won' && before.get(key(c)) !== 'won');
    if (!tile || reducedMotion()) return;
    setWon({ tile, opened: state.cells.filter(c => c.state !== 'fog' && before.get(key(c)) === 'fog').map(key), revealed: false, counted: false });
  }, [state]);
  // The server sends the reward after the map, so the animation waits until the reveal has opened and closed
  useEffect(() => { if (won && held && !won.revealed) setWon({ ...won, revealed: true }); }, [won, held]);
  const showing = !!won?.revealed && !held;
  useEffect(() => {
    if (!showing) return;
    const count = setTimeout(() => setWon(w => w && { ...w, counted: true }), COUNT_MS);
    const end = setTimeout(() => setWon(null), WON_MS);
    return () => { clearTimeout(count); clearTimeout(end); };
  }, [showing]);
  const skip = () => { if (showing) setWon(null); };
  // The marker is first placed at the walk's start without moving. The map message with the path already puts the player at its end
  const [walk, setWalk] = useState<{ at: ConquestPlace; moving: boolean } | null>(null);
  const battleWanted = useRef(false);
  const pathKey = state.path.map(key).join('>');
  useLayoutEffect(() => {
    if (state.path.length < 2) {
      // A map with no path ends a walk in progress. Any battle queued after the walk is dropped
      battleWanted.current = false;
      setWalk(null);
      return;
    }
    const done = () => {
      setWalk(null);
      if (battleWanted.current) {
        battleWanted.current = false;
        fight(actions);
      }
    };
    if (reducedMotion()) {
      done();
      return;
    }
    let i = 0;
    let timer = 0;
    setWalk({ at: state.path[0], moving: false });
    // Wait two frames. The first draws the marker at the start, and the second starts it moving
    let frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        const step = () => {
          i += 1;
          if (i < state.path.length) setWalk({ at: state.path[i], moving: true });
          else { clearInterval(timer); done(); }
        };
        step();
        timer = window.setInterval(step, STEP_MS);
      });
    });
    return () => { cancelAnimationFrame(frame); clearInterval(timer); };
  }, [pathKey]);

  // The player's region and the regions on the walk are always open. The player's own open and fold choices are stored by plane
  const opened: Record<number, boolean> = {};
  state.regions.forEach((_, r) => {
    const chosen = ui.conquestOpened[`${state.plane}:${r}`];
    if (chosen !== undefined) opened[r] = chosen;
  });
  const keepOpen = new Set([state.at.region, ...(walk ? state.path.map(p => p.region) : [])]);
  const folded = (r: number): boolean => isFolded(state, r, opened, keepOpen);
  const l = layout(state, folded);

  // The view follows the player, as mobile's does
  const trail = useRef<HTMLDivElement>(null);
  const marker = walk?.at ?? state.at;
  const markerAt = l.at(marker.region, marker.row, marker.col);
  useEffect(() => {
    const el = trail.current;
    if (el) el.scrollTo({ left: Math.max(0, markerAt.x + TW / 2 - el.clientWidth * 0.55), behavior: reducedMotion() ? 'auto' : 'smooth' });
  }, [markerAt.x, state.plane]);

  const [hover, setHover] = useState<string | null>(null);
  const hovered = hover ? cells.get(hover) : undefined;

  // A region the player opens or folds animates as an accordion: the regions after it slide along the road while it grows or shrinks
  const [moved, setMoved] = useState<{ region: number; open: boolean } | null>(null);
  useEffect(() => {
    if (!moved) return;
    const timer = window.setTimeout(() => setMoved(null), 900);
    return () => clearTimeout(timer);
  }, [moved]);
  const fullWidth = state.rows * TW + (state.rows - 1) * G;
  const selected = cells.get(key(state.selected));

  // A line joins two adjacent places when at least one is conquered. Lines run along the road, into the next region and down to the next lane
  const ties: { a: ConquestCell; b: ConquestCell }[] = [];
  for (const c of state.cells) {
    const next = c.row + 1 < state.rows ? cells.get(key({ region: c.region, row: c.row + 1, col: c.col }))
      : cells.get(key({ region: c.region + 1, row: 0, col: c.col }));
    const below = cells.get(key({ region: c.region, row: c.row, col: c.col + 1 }));
    for (const other of [next, below]) {
      if (other && (c.state === 'won' || other.state === 'won') && !folded(c.region) && !folded(other.region)) ties.push({ a: c, b: other });
    }
  }

  const battle = () => {
    if (state.steps > 0) {
      battleWanted.current = true;
      actions.conquestMove();
    } else {
      fight(actions);
    }
  };

  const wonHere = showing && !won!.counted ? won!.tile : null;
  const sealAt = wonHere && l.at(wonHere.region, wonHere.row, wonHere.col);
  return (
    <div class={showing ? 'cq-map won' : 'cq-map'}>
      <div class="cq-stage" onPointerDown={skip}>
        <div class="cq-map-head"><h2>{state.plane}</h2>
          <span class="muted cq-count">{state.cells.filter(c => c.state === 'won').length - (wonHere ? 1 : 0)} / {state.cells.length}</span></div>
        <div class="cq-trail" ref={trail}>
          <div class={['cq-trail-in', walk?.moving && 'walking', moved && 'moving'].filter(Boolean).join(' ')} style={{ width: `${l.width}px`, height: `${l.height}px` }}>
            {state.regions.map((region, r) => {
              const at = l.regions[r];
              const conquered = region.conquered - (wonHere?.region === r ? 1 : 0);
              const done = conquered === region.total;
              const count = `${conquered} / ${region.total}`;
              const toggle = r === state.at.region ? undefined : () => {
                const open = folded(r);
                setMoved({ region: r, open });
                changeUi(u => { u.conquestOpened[`${state.plane}:${r}`] = open; });
              };
              const motion = moved?.region === r ? (moved.open ? ' grow' : ' shrink') : '';
              if (at.folded) {
                return (
                  <button key={`s${r}`} class={(done ? 'cq-spine done' : 'cq-spine') + motion} title={region.name} onClick={toggle}
                    style={{ left: `${at.x}px`, top: `${Y0}px`, width: `${SPINE}px`, height: `${l.height - Y0 - 20}px`, '--from': `${fullWidth}px` }}>
                    <i style={{ backgroundImage: `url("${artUrl(region.art)}")` }} />
                    <b>{region.name}</b><span>{count}</span>
                  </button>
                );
              }
              return (
                <button key={`b${r}`} class={(done ? 'cq-banner done' : 'cq-banner') + motion} disabled={!toggle} onClick={toggle}
                  style={{ left: `${at.x}px`, top: `${Y0}px`, width: `${at.w}px`, height: `${BH}px`, backgroundImage: `url("${artUrl(region.art)}")`, '--from': `${SPINE}px` }}>
                  <b>{region.name}</b><span class={showing && won!.tile.region === r ? 'cq-count' : undefined}>{done && <img alt={t('lblWebConquestConquered')} src={skinIconUrl('IMG_PW_BADGE_COMMON')} />}{count}</span>
                </button>
              );
            })}
            {state.cells.filter(c => !folded(c.region)).map(c => {
              const p = l.at(c.region, c.row, c.col);
              const here = same(c, state.at) && !walk;
              const sel = same(c, state.selected) && !same(c, state.at);
              const opened = won ? won.opened.indexOf(key(c)) : -1;
              const cls = ['cq-tile', c.state, here && 'here', sel && 'sel', hover === key(c) && 'hover', won && same(c, won.tile) && 'sealed',
                opened >= 0 && 'unveiled', moved?.open && moved.region === c.region && 'arrive'].filter(Boolean).join(' ');
              return (
                <button key={key(c)} class={cls} disabled={c.state === 'fog'} aria-label={c.name ?? undefined}
                  style={{ left: `${p.x}px`, top: `${p.y}px`, width: `${TW}px`, height: `${TH}px`, backgroundImage: c.avatar ? `url("${artUrl(c.avatar)}")` : undefined,
                    '--i': opened, '--step': c.row }}
                  onPointerEnter={() => setHover(key(c))} onPointerLeave={() => setHover(null)}
                  onClick={() => { if (!walk) actions.conquestSelect(c.region, c.row, c.col); }}>
                  {(c.state === 'open' || here) && <span>{c.name}</span>}
                </button>
              );
            })}
            {ties.map(({ a, b }) => {
              const p = l.at(a.region, a.row, a.col), q = l.at(b.region, b.row, b.col);
              const kind = a.state === 'won' && b.state === 'won' ? 'won' : 'open';
              // A new line is animated outward from the tile just won
              const far = won && (same(a, won.tile) ? b : same(b, won.tile) ? a : null);
              const drawn = !!far && far.state !== 'won';
              const cls = ['cq-tie', p.y !== q.y && 'down', kind, drawn && 'drawn', drawn && far === a && 'back',
                moved?.open && (moved.region === a.region || moved.region === b.region) && 'arrive'].filter(Boolean).join(' ');
              return p.y === q.y
                ? <i key={`${key(a)}-${key(b)}`} class={cls} style={{ left: `${p.x + TW}px`, top: `${p.y + TH / 2}px`, width: `${q.x - p.x - TW}px` }} />
                : <i key={`${key(a)}-${key(b)}`} class={cls} style={{ left: `${p.x + TW / 2}px`, top: `${p.y + TH}px`, height: `${G}px` }} />;
            })}
            {sealAt && <>
              <i class="cq-burst" style={{ left: `${sealAt.x}px`, top: `${sealAt.y}px`, width: `${TW}px`, height: `${TH}px` }} />
              <span class="cq-stamp" style={{ left: `${sealAt.x + TW / 2}px`, top: `${sealAt.y + 54}px` }}><img alt="" src={skinIconUrl('IMG_PW_BADGE_COMMON')} /></span>
            </>}
            <div class="cq-you" style={{ left: `${markerAt.x + TW / 2}px`, top: `${markerAt.y + 54}px` }}>
              <i style={{ backgroundImage: `url("${artUrl(state.walkerImage)}")` }} /><b>{t('lblWebLobbyKindYou')}</b>
            </div>
            {hovered && hovered.state !== 'fog' && !walk && <HoverCard cell={hovered} at={l.at(hovered.region, hovered.row, hovered.col)} width={l.width} />}
          </div>
        </div>
      </div>
      {selected && <EventPanel state={state} cell={selected} busy={!!walk} battle={battle} />}
    </div>
  );
}

function Record({ cell }: { cell: ConquestCell }) {
  return cell.wins || cell.losses ? <span class="cq-chip">{cell.wins}W / {cell.losses}L</span> : null;
}

/** The reward for winning here: an emblem for a first conquest, and always a spin of the wheel. */
function Reward({ cell }: { cell: ConquestCell }) {
  return (
    <div class="cq-reward-line">
      <span class="cq-kicker">{t('lblWebConquestReward')}</span>
      {cell.state !== 'won' && <span class="cq-chip"><img alt={t('lblPlaneswalkEmblems')} src={skinIconUrl('IMG_PW_BADGE_COMMON')} />1</span>}
      <span class="cq-chip">{t('lblWebConquestChaosWheel')}</span>
    </div>
  );
}

/** The event panel's Format and Reward sections. Each format and each prize is a row with an icon, a name and a line of text. */
function Sections({ cell }: { cell: ConquestCell }) {
  const variants = cell.variants ?? [];
  return (
    <>
      {variants.length > 0 && (
        <div class="cq-sec">
          <span class="cq-kicker">{t('lblWebConquestFormat')}</span>
          {variants.map(v => <SectionRow key={v} icon={VARIANT_ICONS[v]} name={variantName(v)} line={VARIANT_DESC[v] && t(VARIANT_DESC[v])} />)}
        </div>
      )}
      <div class="cq-sec">
        <span class="cq-kicker">{t('lblWebConquestReward')}</span>
        {cell.state !== 'won' && <SectionRow icon="IMG_PW_BADGE_COMMON" brass name={t('lblWebConquestEmblemReward')} tag={t('lblWebConquestFirstWin')} line={t('lblWebConquestEmblemLine')} />}
        <SectionRow icon="IMG_CHAOS" name={t('lblWebConquestWheelSpin')} line={t('lblWebConquestWheelLine')} />
      </div>
    </>
  );
}

function SectionRow({ icon, name, line, tag, brass }: { icon?: string; name: string; line?: string; tag?: string; brass?: boolean }) {
  return (
    <div class="cq-sec-row">
      <span class={brass ? 'cq-sec-icon brass' : 'cq-sec-icon'}>{icon && <img alt="" src={skinIconUrl(icon)} />}</span>
      <div>
        <b>{name}{tag && <span class="cq-sec-tag">{tag}</span>}</b>
        {line && <p>{line}</p>}
      </div>
    </div>
  );
}

/** formats is false in the event panel, which lists the formats in their own section. */
function Chips({ cell, formats = true }: { cell: ConquestCell; formats?: boolean }) {
  return (
    <div class="cq-chips">
      {formats && (cell.variants ?? []).map(v => <span key={v} class="cq-chip">{variantName(v)}</span>)}
      {cell.state === 'won' && <span class="cq-chip brass"><img alt="" src={skinIconUrl('IMG_PW_BADGE_COMMON')} />{t('lblWebConquestConquered')}</span>}
      <Record cell={cell} />
      {cell.opens && <span class="cq-chip brass">{t('lblWebConquestPortal')}: {cell.opens}</span>}
    </div>
  );
}

/** The card shown while the pointer is over a tile. It opens on the side of the tile that has room. */
function HoverCard({ cell, at, width }: { cell: ConquestCell; at: { x: number; y: number }; width: number }) {
  const left = at.x + TW + 12 + HOVER_W > width;
  return (
    <div class={left ? 'cq-hov left' : 'cq-hov'} style={{ left: `${left ? at.x - 12 - HOVER_W : at.x + TW + 12}px`, top: `${at.y + 8}px`, width: `${HOVER_W}px` }}>
      <b>{cell.name}</b>
      <Chips cell={cell} />
      <Reward cell={cell} />
    </div>
  );
}

function EventPanel({ state, cell, busy, battle }: { state: ConquestState; cell: ConquestCell; busy: boolean; battle: () => void }) {
  const [zoom, setZoom] = useState<string | null>(null);
  // On a phone the panel is a small card over the map, and the title opens the rest of it
  const [open, setOpen] = useState(false);
  const region = state.regions[cell.region];
  const lead = state.commander;
  return (
    <SidePanel kicker={`${state.plane} - ${region.name}`} where={<span class="pips"><Pips colors={region.colors} /></span>}
      title={cell.name} art={cell.avatar} label={cell.opponent} zoom={zoom} setZoom={setZoom}
      fold={isPortrait() ? { open, toggle: () => setOpen(!open) } : undefined}
      foot={<>
        <DeckRow image={lead.image} name={lead.name} line={t('lblWebConquestDeckCards', lead.deckSize)} problem={!!lead.problem}
          zoom={setZoom} change={() => changeUi(u => { u.conquestTab = 'party'; })} />
        {(cell.variants ?? []).includes('Planeswalker') && (
          <div class="cq-mine">
            <button class="cq-face2 round" aria-label={state.walker} data-image={state.walkerImage} style={{ backgroundImage: `url("${artUrl(state.walkerImage)}")` }} onClick={() => setZoom(state.walkerImage)} />
            <div class="t"><b>{state.walker}</b><span>{t('lblPlaneswalker')}</span></div>
          </div>
        )}
        {lead.problem && <p class="cq-warn">{lead.problem}</p>}
        <button class="primary cq-big" disabled={!!lead.problem || busy} onClick={battle}>{t('lblBattle')}</button>
      </>}>
      <Chips cell={cell} formats={false} />
      <Sections cell={cell} />
      {state.steps > 0 && <p class="cq-steps">{state.steps === 1 ? t('lblWebConquestOneStep') : t('lblWebConquestSteps', state.steps)}</p>}
    </SidePanel>
  );
}
