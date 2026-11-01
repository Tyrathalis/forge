import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl, skinIconUrl } from '../images';
import { artUrl } from '../sleeves';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { CampaignBar as Bar, PrefRow } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';
import { backStep, HeadControls, PageHeader, PhoneHead, SetupHead, WAY_NAMES } from '../header';
import { isPortrait } from '../form';
import { ICONS, Icon } from '../icons';
import { changeUi, eased, type UiState } from '../ui';
import { Reveal, type Owed } from './reward';
import { Backdrop } from '../dialog';

export function Purse({ icon, n, label }: { icon: string; n: number; label: string }) {
  return <span class="cq-coin" data-icon={icon} title={label}><img alt={label} src={skinIconUrl(icon)} /><b>{formatNumber(n)}</b></span>;
}

export const balance = (bar: Bar | null, icon: string): number => bar?.balances.find(b => b.icon === icon)?.amount ?? 0;

// The most the bar shows of a balance, by icon, while a trade's amounts fly to it. The new balance can arrive before they do
const ceilings = new Map<string, number>();
let redrawBar = () => {};

/** Sets the most the bar shows of a balance, or with null lets it show the server's amount again. */
export function capBalance(icon: string, most: number | null): void {
  if (most === null) ceilings.delete(icon);
  else ceilings.set(icon, most);
  redrawBar();
}

// The new items each tab has shown, by save and tab. A save's first bar counts as shown, so only what arrives while it is open is marked
const shown = new Map<string, Set<string>>();

/** Whether a tab has a new item it has not shown yet. An open tab shows all of them. */
function unseen(bar: Bar, tab: string, open: boolean): boolean {
  const fresh = bar.fresh[tab] ?? [];
  const key = `${bar.name}
${tab}`;
  const seen = shown.get(key);
  if (!seen || open) {
    shown.set(key, new Set([...(seen ?? []), ...fresh]));
    return false;
  }
  return fresh.some(name => !seen.has(name));
}

/**
 * The bar shared by every page of a campaign. It shows the save's name and line, the mode's tabs, the balances and the back button.
 * extra is a dev tool: a control beside the balances, or on a phone a row of the header's menu.
 */
export function CampaignBar<Tab extends string>({ bar, tabs, tab, setTab, held, extra, prefs, leave, under }: {
  bar: Bar; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void;
  /** The amounts a reward reveal has not shown yet, by balance icon. The server's balances already include them, so the bar subtracts them. */
  held: Record<string, number>;
  extra?: ComponentChildren; prefs?: () => void; leave: () => void; under: boolean;
}) {
  const strip = useRef<HTMLElement>(null);
  const [, redraw] = useState(0);
  useEffect(() => {
    redrawBar = () => redraw(n => n + 1);
    return () => { redrawBar = () => {}; };
  }, []);
  // A tab opened from a page, such as Change on the map, may be scrolled out of a narrow strip
  useEffect(() => { strip.current?.querySelector('[aria-current="page"]')?.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }, [tab]);
  const nav = (
    <nav class="cq-tabs" ref={strip}>
      {tabs.map(([id, name]) => (
        <button key={id} class="cq-tab" data-tab={id} aria-current={id === tab ? 'page' : undefined} onClick={() => setTab(id)}>
          {t(name)}{unseen(bar, id, id === tab) && <span class="new-card">{t('lblNew')}</span>}{id === tab && <span class="cq-tab-line" />}
        </button>
      ))}
    </nav>
  );
  const balances = bar.balances.map(b => <Purse key={b.icon} icon={b.icon} n={Math.min(b.amount - (held[b.icon] ?? 0), ceilings.get(b.icon) ?? Infinity)} label={b.label} />);
  if (isPortrait()) {
    return (
      <PhoneHead back={{ label: t('lblBack'), go: leave }} title={bar.name} inert={under} tabs={nav}
        line={<><span class="cq-purse">{balances}</span><span>{bar.line}</span></>}
        more={close => prefs && <button role="menuitem" onClick={() => { close(); prefs(); }}><Icon d={ICONS.sliders} />{t('Preferences')}</button>}
        dev={extra} />
    );
  }
  return (
    <div class="cq-bar" inert={under}>
      <div class="cq-id"><b>{bar.name}</b><span>{bar.line}</span></div>
      {nav}
      <div class="cq-purse">
        {balances}
        {prefs && <button onClick={prefs}>{t('Preferences')}</button>}
        {extra}
        <HeadControls />
        <button onClick={leave}>{t('lblBack')}</button>
      </div>
    </div>
  );
}

/**
 * The panel beside a page's list. It shows the chosen item's title, picture and text, and the player's own items in the foot. A pressed picture in the foot is shown large.
 * fold makes the title a button that opens and closes the rest of the panel, for a panel drawn as a small card over the page.
 */
export function SidePanel({ kicker, where, title, art, label, zoom, setZoom, children, foot, fold }: {
  kicker: string; where?: ComponentChildren; title?: string; art?: string | null; label?: string;
  zoom: string | null; setZoom: (image: string | null) => void; children: ComponentChildren; foot: ComponentChildren;
  fold?: { open: boolean; toggle: () => void };
}) {
  return (
    <aside class={fold?.open ? 'cq-side open' : 'cq-side'}>
      <div class="cq-panel">
        <div class="cq-where"><span class="cq-kicker">{kicker}</span>{where}</div>
        {fold ? <h3><button class="cq-fold" aria-expanded={fold.open} onClick={fold.toggle}>{title}</button></h3> : <h3>{title}</h3>}
        <div class="cq-art" data-image={art ?? undefined} style={{ backgroundImage: art ? `url("${artUrl(art)}")` : undefined }}>
          <b>{label}</b>
        </div>
        {children}
      </div>
      <div class="cq-foot">{foot}</div>
      {zoom && <div class="backdrop" onClick={() => setZoom(null)}><img class="cq-zoom" alt="" src={imageUrl(zoom)} /></div>}
    </aside>
  );
}

export function DeckRow({ image, name, line, problem, zoom, change }: {
  image?: string | null; name: string; line: string; problem: boolean; zoom: (image: string) => void; change?: () => void;
}) {
  return (
    <div class="cq-mine">
      {image && <button class="cq-face2" aria-label={name} data-image={image} style={{ backgroundImage: `url("${artUrl(image)}")` }} onClick={() => zoom(image)} />}
      <div class="t">
        <b>{name}</b>
        {problem ? <span class="cq-warn">{t('lblInvalidDeck')}</span> : <span>{line}</span>}
      </div>
      {change && <button onClick={change}>{t('lblWebMenuChange')}</button>}
    </div>
  );
}

/** A campaign's statistics. It shows figures and any tables the mode sends. first is the scope requested when the page opens. */
export function Stats({ model, actions, first }: { model: Model; actions: Actions; first?: string }) {
  const s = model.campaignStats;
  useEffect(() => { actions.campaignStats(first); }, []);
  if (!s) return <p class="muted pools-wait">{t('lblLoadingEllipsis')}</p>;
  return (
    <div class="cq-stats">
      <div class="cq-stats-head">
        <div><span class="muted">{model.campaignBar?.name}</span><h2>{s.title}</h2></div>
        {s.scopes.length > 0 && (
          <span class="seg" role="group">
            {s.allScopes && <button aria-pressed={!s.scope} onClick={() => actions.campaignStats(undefined)}>{s.allScopes}</button>}
            {s.scopes.map(p => <button key={p} aria-pressed={p === s.scope} onClick={() => actions.campaignStats(p)}>{p}</button>)}
          </span>
        )}
      </div>
      <div class="cq-figures">
        {s.figures.map(f => (
          <div key={f.label} class="cq-figure">
            <span class="cq-kicker">{f.label}</span>
            <p><b>{formatNumber(f.amount)}</b>{f.of != null && <span> / {formatNumber(f.of)} ({f.of ? Math.round(100 * f.amount / f.of) : 0}%)</span>}</p>
            {f.of != null && <div class="cq-pbar"><i style={{ width: `${f.of ? 100 * f.amount / f.of : 0}%` }} /></div>}
          </div>
        ))}
      </div>
      <div class="cq-tables">
        {s.tables.map(table => (
          <table key={table.headers[0]}>
            <thead><tr>{table.headers.map(h => <th key={h}>{h}</th>)}</tr></thead>
            <tbody>
              {table.rows.map(row => <tr key={row[0]}>{row.map((cell, i) => <td key={i}>{cell}</td>)}</tr>)}
            </tbody>
          </table>
        ))}
      </div>
    </div>
  );
}

/** A mode's preferences. Each value is saved when it changes, because the server checks it against the current values of the others. */
export function Prefs({ model, actions, close }: { model: Model; actions: Actions; close: () => void }) {
  const p = model.campaignPrefs;
  /** Text typed but not yet accepted, by key. A refused value stays in its field so the player can correct it. */
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [last, setLast] = useState<string | null>(null);
  useEffect(() => { actions.campaignPrefs(); }, []);
  // Once a value is accepted, its field shows the server's value again. Text typed in other fields is kept
  useEffect(() => {
    if (p && !p.problem && last) setTyped(({ [last]: _, ...rest }) => rest);
  }, [p]);
  if (!p) return null;
  // A group drawn as a table spans both columns of the dialog, so it is placed after the other groups
  const tabled = (g: string) => p.rows.some(r => r.group === g && r.column);
  const named = [...new Set(p.rows.map(r => r.group))];
  const groups = [...named.filter(g => !tabled(g)), ...named.filter(tabled)];
  // save takes the field's own text, because a blur can happen before the typed text has been rendered
  const save = (key: string, text: string, was: string) => {
    // The mode decides which values are allowed. A refused value comes back as the problem line
    if (text.trim() === '' || Number(text) === Number(was)) return;
    setLast(key);
    actions.campaignPref(key, text.trim());
  };
  // A value written as a decimal can be entered as any decimal. The mode still decides whether the value is allowed
  const field = (r: PrefRow, label?: string) => (
    <input type="number" min={0} step={r.value.includes('.') ? 'any' : 1} value={typed[r.key] ?? r.value} aria-label={label}
      onInput={e => setTyped({ ...typed, [r.key]: e.currentTarget.value })}
      onBlur={e => save(r.key, e.currentTarget.value, r.value)}
      onKeyDown={e => { if (e.key === 'Enter') save(r.key, e.currentTarget.value, r.value); }} />
  );
  const bad = (r: PrefRow) => !!p.problem && last === r.key;
  return (
    <Backdrop dismiss={close}>
      <div class="dialog cq-prefs">
        <header><h3>{p.title}</h3><button aria-label={t('lblClose')} onClick={close}>×</button></header>
        <div class="cq-prefs-body">
          {groups.map(g => {
            const rows = p.rows.filter(r => r.group === g);
            // Rows with a column are cells of a row that holds several values. They are drawn as a table with one column per name
            const columns = [...new Set(rows.flatMap(r => (r.column ? [r.column] : [])))];
            const lines = [...new Set(rows.filter(r => r.column).map(r => r.label))];
            return (
              <section key={g} class={columns.length ? 'wide' : undefined}>
                <h4>{g}</h4>
                {columns.length > 0 && (
                  <table class="cq-pref-table">
                    <thead><tr><th />{columns.map(c => <th key={c}>{c}</th>)}</tr></thead>
                    <tbody>
                      {lines.map(label => (
                        <tr key={label}>
                          <th>{label}</th>
                          {columns.map(c => {
                            const r = rows.find(x => x.label === label && x.column === c);
                            return <td key={c} class={r && bad(r) ? 'bad' : undefined}>{r && field(r, `${label}, ${c}`)}</td>;
                          })}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
                {rows.filter(r => !r.column).map(r => (
                  <label key={r.key} class={bad(r) ? 'cq-pref bad' : 'cq-pref'}>
                    <span>{r.label}</span>
                    {field(r)}
                  </label>
                ))}
              </section>
            );
          })}
        </div>
        <footer>
          <span class={p.problem ? 'cq-warn' : 'muted'}>{p.problem ?? p.note}</span>
          {p.canReset && <button onClick={() => { setTyped({}); setLast(null); actions.campaignPrefsReset(); }}>{t('lblReset')}</button>}
          <button class="primary" onClick={close}>{t('lblOK')}</button>
        </footer>
      </div>
    </Backdrop>
  );
}

/**
 * A campaign mode's page. It shows the saved games, the form that starts a new one in their place, or an open save's pages.
 * icons are the balance icons. They are loaded when the mode opens, because on a first visit they would otherwise load after every picture on the first page.
 */
export function CampaignShell({ model, actions, icons, shelf, open }: {
  model: Model; actions: Actions; icons: string[];
  shelf: (creating: boolean, setCreating: (on: boolean) => void) => ComponentChildren; open: () => ComponentChildren;
}) {
  const inside = model.campaignSave !== null;
  useEffect(() => { for (const icon of icons) new Image().src = skinIconUrl(icon); }, []);
  // The new-save form closes and the saves list returns when the player goes back or the new save opens
  const [creating, setCreating] = useState(false);
  useEffect(() => { if (inside) setCreating(false); }, [inside]);
  return (
    <div key={inside ? 'open' : 'shelf'} class={inside ? 'cq-shell' : 'limited-page'}>
      {/* An open save has its back button on its bar. On a phone the shelf's trail draws the header */}
      {!inside && !isPortrait() && (
        <PageHeader class="limited-head">
          <div class="head-right">
            <HeadControls />
            <button onClick={() => (creating ? setCreating(false) : actions.campaignLeave())}>{t('lblBack')}</button>
          </div>
        </PageHeader>
      )}
      {inside ? open() : shelf(creating, setCreating)}
    </div>
  );
}

/** The saved games under a breadcrumb trail back to the start page, or the form that starts one. saves is null while they load. */
export function CampaignShelf({ model, actions, creating, setCreating, words, form, saves }: {
  model: Model; actions: Actions; creating: boolean; setCreating: (on: boolean) => void;
  words: { mode: string; new: string; yours: string; reading: string }; form: ComponentChildren; saves: ComponentChildren | null;
}) {
  const trail = [
    { label: t('lblStart'), go: () => { changeUi(u => { u.menuChoice = null; }); actions.campaignLeave(); } },
    { label: WAY_NAMES.play, go: () => { changeUi(u => { u.menuChoice = 'play'; }); actions.campaignLeave(); } },
    creating ? { label: words.mode, go: () => setCreating(false) } : { label: words.mode },
    ...(creating ? [{ label: words.new }] : []),
  ];
  const title = creating ? words.new : words.yours;
  return <>
    {isPortrait() && <PhoneHead back={backStep(trail)} title={title} />}
    <SetupHead trail={trail} title={title} />
    {model.error && !creating && <p class="limited-error">{model.error}</p>}
    {creating ? form : saves ?? <p class="muted pools-wait">{words.reading}</p>}
  </>;
}

/** A tab change slides the page in the direction of the new tab in the tab order (dialogs.css). */
export function slideTab<Tab extends string>(tabs: [Tab, TextKey][], from: Tab, to: Tab, set: (u: UiState) => void): void {
  const order = tabs.map(([id]) => id);
  if (to !== from) eased(set, () => ({ way: order.indexOf(to) > order.indexOf(from) ? 'on' : 'back' }), () => !document.querySelector('.cq-main .pools-wait'));
}

/**
 * An open save: the bar, the open tab's page, the preferences and a reward reveal. page draws the tab's page under a key that
 * changes when the preferences close. The preferences set the prices the pages show, so the page must request its data again.
 */
export function CampaignPages<Tab extends string>({ model, actions, tabs, tab, setTab, extra, page }: {
  model: Model; actions: Actions; tabs: [Tab, TextKey][]; tab: Tab; setTab: (tab: Tab) => void; extra?: ComponentChildren;
  page: (key: number) => ComponentChildren;
}) {
  // The amounts the reward reveal has not shown yet. The bar subtracts them from the server's balances
  const [owed, setOwed] = useState<Owed>({});
  const reward = model.reward;
  const [prefs, setPrefs] = useState(false);
  const [priced, setPriced] = useState(0);
  useEffect(() => { if (!reward) setOwed({}); }, [reward]);
  const bar = model.campaignBar!;
  return <>
    <CampaignBar bar={bar} tabs={tabs} tab={tab} setTab={setTab} held={reward ? owed : {}} prefs={() => setPrefs(true)}
      // Back returns to the first tab, the save's home page, before it leaves the save
      leave={() => (tab === tabs[0][0] ? actions.campaignLeave() : setTab(tabs[0][0]))} under={!!reward} extra={extra} />
    {model.error && <p class="limited-error">{model.error}</p>}
    <div class="cq-main" inert={!!reward}>{page(priced)}</div>
    {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); setPriced(priced + 1); }} />}
    {reward && <Reveal key={reward.steps.length + ':' + bar.name} reward={reward} onOwed={setOwed} done={() => actions.rewardClaim()} />}
  </>;
}
