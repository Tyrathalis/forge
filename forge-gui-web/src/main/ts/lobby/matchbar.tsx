// The bar above the seats. It shows what is played and by how many players, as labelled fields that each open their own control

import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { BackButton, HeadControls, PageHeader, type SetupStep } from '../header';
import { isPortrait } from '../form';
import { ICONS, Icon } from '../icons';
import { t, type TextKey } from '../text';
import type { Actions } from '../actions';
import type { Model } from '../model';
import { Dropdown } from '../dialog';
import type { Address, CardPoolGroup, Format, LobbyTable } from '../protocol';

/** name is the button's accessible name, for a label with no words. cls replaces the button's classes. */
function Popup({ label, name, cls, disabled, children, wide, onOpen }: {
  label: ComponentChildren; name?: string; cls?: string; disabled?: boolean; children: (close: () => void) => ComponentChildren; wide?: boolean; onOpen?: () => void;
}) {
  return (
    <Dropdown cls="popup-anchor" button={(open, toggle) => (
      <button class={cls ?? 'field-value menu-button'} title={name} aria-label={name} aria-expanded={open} disabled={disabled} onClick={() => {
        if (!open) onOpen?.();
        toggle();
      }}>{label}</button>
    )}>{close => <div class={wide ? 'popup wide' : 'popup'} role="dialog">{children(close)}</div>}</Dropdown>
  );
}

function Field({ name, grow, children }: { name: string; grow?: boolean; children: ComponentChildren }) {
  return (
    <div class={grow ? 'field grow' : 'field'}>
      <span class="field-name">{name}</span>
      {children}
    </div>
  );
}

/** Draft and Sealed in the same shape the server uses for a format, so the menu's card shows them the same way. */
function limitedFormats(): (Format & { kind: 'draft' | 'sealed' })[] {
  return [
    { id: 'Draft', kind: 'draft', name: t('lblDraft'), group: 'Limited', desc: t('lblWebMatchBarDescDraft'),
      facts: [t('lblWebMatchBarFactPicks'), t('lblWebFactLife', 20)], play: t('lblWebMatchBarPlayDraft'), deckCards: 40, deckAtLeast: false },
    { id: 'Sealed', kind: 'sealed', name: t('lblSealed'), group: 'Limited', desc: t('lblWebMatchBarDescSealed'),
      facts: [t('lblWebMatchBarFactPool'), t('lblWebFactLife', 20)], play: t('lblWebMatchBarPlaySealed'), deckCards: 40, deckAtLeast: false },
  ];
}

/** A game's deck size in a few words: "60+ cards", "100 cards", or "no deck" when the deck is dealt. */
function deckMark(format: Format): string {
  return format.deckCards == null ? t('lblNoDeck') : t('lblWebNCards', `${format.deckCards}${format.deckAtLeast ? '+' : ''}`);
}

const GROUP_ICONS: Record<string, ComponentChildren> = {
  Constructed: <svg viewBox="0 0 16 16"><rect x="3" y="2" width="9" height="12" rx="1.5" /><path d="M5 4.5v10.5h8V5" opacity=".6" /></svg>,
  Commander: <svg viewBox="0 0 16 16"><path d="M2 12 L3 5 L6 8 L8 3 L10 8 L13 5 L14 12 Z" /></svg>,
  Other: <svg viewBox="0 0 16 16"><rect x="2.5" y="2.5" width="11" height="11" rx="2.5" /><circle cx="5.7" cy="5.7" r="1.3" fill="currentColor" stroke="none" />
    <circle cx="8" cy="8" r="1.3" fill="currentColor" stroke="none" /><circle cx="10.3" cy="10.3" r="1.3" fill="currentColor" stroke="none" /></svg>,
  Limited: <svg viewBox="0 0 16 16"><path d="M4 2h8l1 3v9H3V5z" /><path d="M3 5h10" /></svg>,
};

/** The name of each family of games. The server names a format's family with these keys. */
const GROUP_NAMES: Record<string, TextKey> = {
  Constructed: 'lblConstructed', Commander: 'lblCommander', Other: 'lblOther', Limited: 'lblLimited',
};

/** A list of every game, beside a card that explains the chosen game or the one last under the pointer. */
export function GameMenu({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const lim = lobby.limited;
  const LIMITED = limitedFormats();
  const chosen: Format | undefined = lim ? LIMITED.find(l => l.kind === lim.kind) : lobby.formats.find(f => f.id === lobby.format);
  const [pointed, setPointed] = useState<Format | null>(null);
  // A new kind of event must wait until the started one is over. Other formats must wait until the draft ends
  const drafting = lim?.phase === 'DRAFTING' && !lim.activeEventId;
  const limitedOffered = lobby.shareable || !!lim;
  const shown = pointed ?? chosen ?? null;
  const pick = (f: Format, close: () => void) => {
    const kind = LIMITED.find(l => l.id === f.id)?.kind;
    if (kind) {
      actions.setLimited(kind);
    } else {
      if (lim) actions.setLimited(null);
      actions.setFormat(f.id);
    }
    setPointed(null);
    close();
  };
  const blocked = (f: Format) => (LIMITED.some(l => l.id === f.id) ? !!lim?.started : drafting);
  const groups: [string, Format[]][] = [...groupsOf(lobby.formats), ...(limitedOffered ? [['Limited', LIMITED] as [string, Format[]]] : [])];
  return (
    <Popup label={chosen?.name ?? lobby.format} disabled={!lobby.host} wide>
      {close => (
        <div class="game-menu-list">
          <div class="game-choices">
            {groups.map(([group, formats]) => (
              <div key={group} class="game-group">
                <span class="game-group-name">{GROUP_NAMES[group] ? t(GROUP_NAMES[group]) : group}</span>
                {formats.map(f => (
                  <button key={f.id} class="game-choice" aria-pressed={f === chosen} disabled={blocked(f)}
                    onPointerEnter={e => { if (e.pointerType === 'mouse') setPointed(f); }} onFocus={() => setPointed(f)}
                    onClick={() => pick(f, close)}>
                    <i class="game-icon" aria-hidden="true">{GROUP_ICONS[f.group]}</i>
                    <span class="game-name">{f.name}</span><span class="game-size">{deckMark(f)}</span>
                  </button>
                ))}
              </div>
            ))}
          </div>
          {shown && (
            <div class="game-card">
              <h5>{shown.name}</h5>
              <p class="desc">{shown.desc}</p>
              <div class="facts">{shown.facts.map(x => <span key={x} class="fact">{x}</span>)}</div>
              <p class="format-play"><b>{t('lblWebMatchBarInMatch')}</b> {shown.play}</p>
            </div>
          )}
        </div>
      )}
    </Popup>
  );
}

/** Formats under their group, in the order the server lists them. */
function groupsOf(formats: Format[]): [string, Format[]][] {
  const groups = new Map<string, Format[]>();
  for (const f of formats) groups.set(f.group, [...(groups.get(f.group) ?? []), f]);
  return [...groups];
}

/** Why a casual variant cannot be switched on, or null. Momir Basic and MoJhoSto bring their own avatars. */
function variantBlocked(lobby: LobbyTable, id: string): string | null {
  const group = lobby.formats.find(f => f.id === lobby.format)?.group;
  return id === 'Vanguard' && group === 'Other' ? t('lblWebMatchBarVanguardOff') : null;
}

function VariantsMenu({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const on = lobby.casualVariants.filter(v => lobby.variantsOn.includes(v.id));
  return (
    <Popup label={on.length ? on.map(v => v.name).join(', ') : <span class="muted">{t('lblNone')}</span>} disabled={!lobby.host}>
      {() => (
        <div class="variant-list">
          {lobby.casualVariants.map(v => {
            const blocked = variantBlocked(lobby, v.id);
            return (
              <label key={v.id} class={blocked ? 'variant blocked' : 'variant'}>
                <input type="checkbox" checked={lobby.variantsOn.includes(v.id)} disabled={!!blocked}
                  onChange={e => actions.setVariant(v.id, e.currentTarget.checked)} />
                <span><b>{v.name}</b><span class="muted">{blocked ?? v.desc}</span></span>
              </label>
            );
          })}
        </div>
      )}
    </Popup>
  );
}

/** The seats a lower count removes, in the server's order: open seats, then computers, from the end. A person's seat is never removed. */
export function seatsLeaving(lobby: LobbyTable, count: number): Set<number> {
  const out = new Set<number>();
  let extra = lobby.seats.length - count;
  for (const type of ['OPEN', 'AI']) {
    for (let i = lobby.seats.length - 1; i >= 0 && extra > 0; i--) {
      if (!lobby.seats[i].mine && lobby.seats[i].type === type && !out.has(i)) {
        out.add(i);
        extra--;
      }
    }
  }
  return out;
}

function fewestSeats(lobby: LobbyTable): number {
  return Math.max(2, lobby.seats.filter(s => s.mine || s.type === 'LOCAL' || s.type === 'REMOTE').length);
}

export function PlayerCount({ lobby, actions, preview }: { lobby: LobbyTable; actions: Actions; preview: (count: number | null) => void }) {
  const fewest = fewestSeats(lobby);
  const counts = Array.from({ length: lobby.maxSeats - 1 }, (_, i) => i + 2);
  const drafting = lobby.limited?.phase === 'DRAFTING' && !lobby.limited.activeEventId;
  return (
    <span class="count" role="group" aria-label={t('lblPlayers')} onPointerLeave={() => preview(null)}>
      {counts.map(n => (
        <button key={n} aria-pressed={n === lobby.seats.length} disabled={!lobby.host || drafting || n < fewest}
          title={n < fewest ? t('lblWebMatchBarFewerSeats') : ''}
          onPointerEnter={() => preview(n < lobby.seats.length ? n : null)}
          onClick={() => { preview(null); actions.setPlayerCount(n); }}>{n}</button>
      ))}
    </span>
  );
}

/** The highest Commander bracket the table plays at, stored in the host's own preferences. 5 means any. Only the host may change it. */
function MaxBracket({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  return (
    <span class="count" role="group" aria-label={t('lblBracket')}>
      {[1, 2, 3, 4, 5].map(n => (
        <button key={n} aria-pressed={n === lobby.maxBracket} disabled={!lobby.host} title={n < 5 ? t('lblWebMatchBarBracketTip', n) : ''}
          onClick={() => actions.setMaxBracket(n)}>{n < 5 ? n : t('lblAny')}</button>
      ))}
    </span>
  );
}

/** Free-for-all, or the seats split into teams. A guest can only read it. Only the host has choices to pick from. */
function Teams({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  const name = (n: number) => (n ? t('lblWebMatchBarNTeams', n) : t('lblWebMatchBarFreeForAll'));
  return (
    <Popup label={name(lobby.teams)} disabled={!lobby.teamChoices.length}>
      {close => (
        <div class="menu-list" role="menu">
          {[0, ...lobby.teamChoices].map(n => (
            <button key={n} role="menuitemradio" aria-checked={n === lobby.teams} onClick={() => { close(); actions.setTeams(n); }}>{name(n)}</button>
          ))}
        </div>
      )}
    </Popup>
  );
}

const MATCH_LENGTHS = [[1, 'lblWebMatchBarBestOfOne'], [3, 'lblWebMatchBarBestOfThree'], [5, 'lblWebMatchBarBestOfFive']] as const;

/** Best-of-one, three or five, stored in the host's own preferences. Only the host may change it. */
function MatchLength({ lobby, actions }: { lobby: LobbyTable; actions: Actions }) {
  return (
    <span class="count" role="group" aria-label={t('lblMatch')}>
      {MATCH_LENGTHS.map(([n, name]) => (
        <button key={n} aria-pressed={n === lobby.gamesPerMatch} disabled={!lobby.host} title={t(name)}
          onClick={() => actions.setMatchLength(n)}>{t('lblWebMatchBarBestOfShort', n)}</button>
      ))}
    </span>
  );
}

/** Which cards a Constructed game allows. The line saying where each format's cards come from is requested when the picker first opens. */
function CardPoolPicker({ model, lobby, actions }: { model: Model; lobby: LobbyTable; actions: Actions }) {
  const details = model.cardPoolDetails;
  const lines = new Map((details?.lines ?? []).map(l => [l.name, l.line]));
  const group = (kind: CardPoolGroup['kind']) => model.cardPools.find(g => g.kind === kind)?.formats ?? [];
  return (
    <Popup label={lobby.cardPool ?? t('lblWebMatchBarAnyCards')} disabled={!lobby.host} wide
      onOpen={() => { if (!details) actions.askCardPoolDetails(); }}>
      {close => {
        const choose = (name: string | null) => { actions.setCardPool(name); close(); };
        return (
          <div class="pool-picker">
            <span class="field-name">{t('lblWebMatchBarAnyOrFormat')}</span>
            <div class="pool-tiles">
              <button class="pool-tile" aria-pressed={!lobby.cardPool} onClick={() => choose(null)}><b>{t('lblWebMatchBarAnyCards')}</b><span>{t('lblWebMatchBarNoLimit')}</span></button>
              {group('Sanctioned').map(name => (
                <button key={name} class="pool-tile" aria-pressed={lobby.cardPool === name} onClick={() => choose(name)}>
                  <b>{name}</b><span>{lines.get(name) ?? ''}</span>
                </button>
              ))}
            </div>
            {group('Casual').length > 0 && <>
              <span class="field-name">{t('lblWebMatchBarCasual')}</span>
              <div class="pool-chips">
                {group('Casual').map(name => (
                  <button key={name} class="pool-chip" aria-pressed={lobby.cardPool === name} title={lines.get(name)} onClick={() => choose(name)}>{name}</button>
                ))}
              </div>
            </>}
            {group('Block').length > 0 && (
            <div class="pool-more">
                <label>{t('lblWebMatchBarBlock')}
                  <span class="pill-select"><select value={group('Block').includes(lobby.cardPool ?? '') ? lobby.cardPool : ''}
                    onChange={e => choose(e.currentTarget.value)}>
                    <option value="" disabled>{t('lblWebMatchBarChooseBlock')}</option>
                    {group('Block').map(name => <option key={name} value={name}>{name}</option>)}
                  </select></span>
                </label>
            </div>
            )}
          </div>
        );
      }}
    </Popup>
  );
}

/** The field names follow the tournament rules. A format says which cards are allowed. A match is the games played between decks. */
export function MatchBar({ model, lobby, actions, preview }: {
  model: Model; lobby: LobbyTable; actions: Actions; preview: (count: number | null) => void;
}) {
  return (
    <div class="match-bar">
      <div class="fields">
        <Field name={t('lblWebMatchBarMode')}><GameMenu lobby={lobby} actions={actions} /></Field>
        {lobby.format === 'Constructed' && <Field name={t('lblFormat')}><CardPoolPicker model={model} lobby={lobby} actions={actions} /></Field>}
        <Field name={t('lblPlayers')}><PlayerCount lobby={lobby} actions={actions} preview={preview} /></Field>
        {lobby.format === 'Commander' && <Field name={t('lblBracket')}><MaxBracket lobby={lobby} actions={actions} /></Field>}
        <Field name={t('lblMatch')}><MatchLength lobby={lobby} actions={actions} /></Field>
        {(lobby.teamChoices.length > 0 || lobby.teams > 0) && <Field name={t('lblWebMatchBarTeams')}><Teams lobby={lobby} actions={actions} /></Field>}
        <Field name={t('lblVariants')} grow><VariantsMenu lobby={lobby} actions={actions} /></Field>
      </div>
    </div>
  );
}

/** The button that shows the addresses others join the table at. On a phone it is an icon in the header. */
export function Invite({ model }: { model: Model }) {
  const phone = isPortrait();
  return (
    <Popup label={phone ? <Icon d={ICONS.link} /> : <><Icon d={ICONS.link} />{t('lblWebMatchBarInvite')}</>} name={phone ? t('lblWebMatchBarInvite') : undefined}
      cls={phone ? 'field-value menu-button invite-button' : 'outline invite-button'}>
      {() => (
        <div class="invite">
          <b>{t('lblWebMatchBarOthersJoinAt')}</b>
          <Addresses list={model.addresses ?? []} />
        </div>
      )}
    </Popup>
  );
}

/** How others join, the sound and the options. The dock shows who is here. */
export function TableHeader({ model, lobby, trail }: { model: Model; lobby: LobbyTable; trail: SetupStep[] }) {
  const invite = lobby.shareable ? <Invite model={model} /> : undefined;
  return (
    <PageHeader brand action={invite}>
      <div class="head-right">
        {invite}
        <HeadControls />
        <BackButton trail={trail} />
      </div>
    </PageHeader>
  );
}

function Addresses({ list }: { list: Address[] }) {
  const [copied, setCopied] = useState<string | null>(null);
  if (!list.length) {
    return <p class="muted">{t('lblWebMatchBarWorkingOutAddress')}</p>;
  }
  return <>{list.map(a => (
    <button key={a.url} class="share-row" onClick={() => navigator.clipboard?.writeText(a.url).then(() => setCopied(a.url), () => {})}>
      <span class="share-label">{a.label}</span><code class="share-url">{a.url}</code>
      <span class="share-copy">{t(copied === a.url ? 'lblWebMatchBarCopied' : 'lblCopy')}</span>
    </button>
  ))}</>;
}
