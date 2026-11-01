// The deck editor. Every change is sent to the server, which saves it at once, so there is no Save button

import { useEffect, useRef, useState } from 'preact/hooks';
import { useDismiss } from '../hooks';
import { Catalogue } from './catalogue';
import { CardMenu, PrintingPicker, type MenuAt } from './cardmenu';
import { DeckHalf } from './deckhalf';
import { DECK_FORMATS } from './deckview';
import { addCard } from './preferred';
import { saveText } from '../dom';
import { startDrag, verdictFor, type CardHandlers, type Carried, type Verdict } from './drag';
import { deckText } from './decklist';
import { changeUi } from '../ui';
import { HeadControls, PageHeader } from '../header';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { DeckSection, EditorState } from '../protocol';
import { CARD_POOL_NAMES, t } from '../text';
import { Backdrop } from '../dialog';
import { ICONS, Icon } from '../icons';

export function Editor({ model, actions }: { model: Model; actions: Actions }) {
  const state = model.editor;
  const [menu, setMenu] = useState<'menu' | 'new' | null>(null);
  const [dialog, setDialog] = useState<'text' | 'delete' | null>(null);
  const [renaming, setRenaming] = useState(false);
  const nameMenu = useRef<HTMLDivElement>(null);
  useDismiss(menu !== null, nameMenu, () => setMenu(null));
  const [cardMenu, setCardMenu] = useState<MenuAt | null>(null);
  const [picking, setPicking] = useState<{ name: string; zone: DeckSection | null } | null>(null);
  /** The half shown on a phone, which has room for only one at a time. */
  const [half, setHalf] = useState<'find' | 'deck'>('find');
  /** The non-main section the catalogue adds to, such as planes. Null means the main deck. */
  const [addingTo, setAddingTo] = useState<DeckSection | null>(null);
  // A list pasted outside a text field opens the importer with it, as a file dropped on the page does
  useEffect(() => {
    const pasted = (e: ClipboardEvent) => {
      const target = e.target as HTMLElement | null;
      const text = e.clipboardData?.getData('text') ?? '';
      if (target?.closest('input, textarea') || !text.trim()) return;
      e.preventDefault();
      changeUi(u => { u.importer = { from: 'editor', text }; });
    };
    document.addEventListener('paste', pasted);
    return () => document.removeEventListener('paste', pasted);
  }, []);
  if (!state) {
    return null;
  }
  // The section chosen for the previous deck may not exist in this one
  const adding = addingTo && state.sections.some(s => s.section === addingTo) ? addingTo : null;
  const dropped = (carried: Carried, v: Verdict) => {
    if (v.zone === 'catalogue' && carried.from !== 'catalogue') {
      actions.edit({ op: 'remove', name: carried.name, from: carried.from, count: carried.count });
    } else if (v.zone === 'Commander') {
      actions.edit({ op: 'commander', name: carried.name, from: carried.from === 'catalogue' ? undefined : carried.from, count: 1 });
    } else if (v.zone !== 'catalogue' && carried.from === 'catalogue') {
      addCard(actions, state, carried.name, v.zone, carried.count);
    } else if (v.zone !== 'catalogue' && carried.from !== 'catalogue') {
      actions.edit({ op: 'move', name: carried.name, from: carried.from, to: v.zone, count: carried.count });
    }
  };
  // Holding Alt at the press drags every copy. The command zone accepts any card because the server decides what can be a commander
  const handlers: CardHandlers = (name, from, image, count, limit) => ({
    // A long press opens the card's preview. Its More button opens this menu
    'data-menu': '',
    onPointerDown: e => {
      const carried: Carried = { name, from, count: e.altKey ? Math.max(1, count) : 1, limit };
      startDrag(e, carried, image, zone => verdictFor(carried, zone, state), v => dropped(carried, v));
    },
    onContextMenu: e => {
      e.preventDefault();
      setCardMenu({ name, from, x: e.clientX, y: e.clientY });
    },
  });
  const openAnother = () => {
    const seat = model.screen === 'setup' ? model.lobby?.mySeat : undefined;
    actions.closeEditor();
    changeUi(u => {
      if (seat !== undefined) u.picker = { kind: 'deck', seat };
      else u.browse = { format: state.format };
    });
  };
  // A draft or sealed pool, or a deck built from a campaign's cards, cannot be created, opened, renamed or deleted here
  const ownDeck = !state.limited && !state.collection;
  /** A row of the deck's menu. Choosing it closes the menu unless it opens another menu. */
  const item = (icon: string, label: string, act: () => void, stays = false, cls?: string) => (
    <button role="menuitem" class={cls} onClick={() => {
      if (!stays) setMenu(null);
      act();
    }}><Icon d={icon} /><span>{label}</span></button>
  );
  // Shown in the header, and on a phone in the deck summary, next to the verdict it explains
  const check = state.limited
    ? <span class="check-fixed">{state.format === 'CommanderDraft' ? state.check : t('lblWebEditorLimitedFixed')}</span>
    : state.collection ? <span class="check-fixed">{state.check}</span>
    : <CheckControl model={model} state={state} actions={actions} />;
  return (
    <div class="editor-page">
      <PageHeader class="editor-head">
        {state.collection && <span class="deck-owner">{state.collection} &rsaquo;</span>}
        {renaming
          ? <RenameField name={state.name} done={name => {
              setRenaming(false);
              if (name && name !== state.name) actions.renameDeck(name);
            }} />
          : (
            <div ref={nameMenu} class="menu-anchor name-menu">
              <button class="deck-name" aria-expanded={menu !== null} onClick={() => setMenu(menu ? null : 'menu')}>
                <span>{state.name}</span><svg class="chevron" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
              </button>
              {menu === 'menu' && (
                <div class="deck-menu" role="menu">
                  {ownDeck && item(ICONS.plus, t('lblWebEditorNewDeck'), () => setMenu('new'), true)}
                  {ownDeck && item(ICONS.folder, t('lblWebEditorOpenAnother'), openAnother)}
                  {ownDeck && <hr />}
                  {ownDeck && item(ICONS.copy, t('lblDuplicate'), () => actions.deckOp('duplicate'))}
                  {ownDeck && item(ICONS.pen, t('lblRename'), () => setRenaming(true))}
                  {ownDeck && <hr />}
                  {!state.limited && item(ICONS.download, t('lblWebEditorImportList'), () => changeUi(u => { u.importer = { from: 'editor' }; }))}
                  {item(ICONS.upload, t('lblWebEditorCopyAsText'), () => setDialog('text'))}
                  {item(ICONS.upload, t('lblWebEditorDownloadDeckFile'), () => actions.deckOp('file'))}
                  {/* A deck opened as a copy, such as a precon or someone else's deck, has nothing of the player's to delete */}
                  {ownDeck && !state.copyOf && <hr />}
                  {ownDeck && !state.copyOf && item(ICONS.bin, t('lblWebEditorDeleteThisDeck'), () => setDialog('delete'), false, 'danger')}
                </div>
              )}
              {menu === 'new' && (
                <div class="deck-menu" role="menu">
                  <span class="menu-cap">{t('lblWebEditorNewDeckFor')}</span>
                  {DECK_FORMATS.map(([id, name]) => (
                    <button key={id} role="menuitem" onClick={() => { setMenu(null); actions.openEditor({ newFormat: id }); }}>{t(name)}</button>
                  ))}
                </div>
              )}
            </div>
          )}
        {check}
        <div class="head-right">
          <span class="save-state">{saveState(state)}</span>
          <button disabled={!state.canUndo} onClick={() => actions.editorUndo()} title={t('lblWebEditorUndoTip')} aria-label={t('lblUndo')}>&#8630;<span class="word"> {t('lblUndo')}</span></button>
          <HeadControls />
          <button class="primary" onClick={() => actions.closeEditor()}>{t('lblDone')}</button>
        </div>
      </PageHeader>
      <div class="editor-tabs" role="group">
        <button aria-pressed={half === 'find'} onClick={() => setHalf('find')}>{t('lblWebEditorFindCards')}</button>
        <button aria-pressed={half === 'deck'} onClick={() => setHalf('deck')}>
          {t('lblDeck')} <span class={state.verdict ? 'n no' : 'n'}>{state.verdict ? '! ' : ''}{state.stats.main}</span>
        </button>
      </div>
      <div class={half === 'deck' ? 'editor-shell deck' : 'editor-shell'}>
        <Catalogue model={model} actions={actions} state={state} handlers={handlers} adding={adding} stopAdding={() => setAddingTo(null)} />
        <DeckHalf actions={actions} state={state} handlers={handlers} check={check} adding={adding} addTo={section => {
          setAddingTo(section);
          if (section) setHalf('find');
        }} />
      </div>
      {dialog === 'text' && <TextDialog state={state} close={() => setDialog(null)} />}
      {dialog === 'delete' && (
        <Backdrop dismiss={() => setDialog(null)}>
          <div class="dialog">
            <h3>{t('lblWebEditorDeleteDeck', state.name)}</h3>
            <p class="hint">{t('lblWebEditorCannotUndo')}</p>
            <div class="actions">
              <button onClick={() => setDialog(null)}>{t('lblCancel')}</button>
              <button class="primary" onClick={() => { setDialog(null); actions.deckOp('delete'); }}>{t('lblDelete')}</button>
            </div>
          </div>
        </Backdrop>
      )}
      {cardMenu && <CardMenu at={cardMenu} state={state} actions={actions} close={() => setCardMenu(null)}
        printings={zone => setPicking({ name: cardMenu.name, zone })} />}
      {picking && <PrintingPicker name={picking.name} zone={picking.zone} model={model} state={state} actions={actions}
        close={() => setPicking(null)} />}
    </div>
  );
}

function saveState(state: EditorState): string {
  if (state.copyOf) return t('lblWebEditorSavesAsCopy', state.copyOf);
  return state.inBrowser ? t('lblWebEditorSavedInBrowser') : t('lblWebEditorSaved');
}

function RenameField({ name, done }: { name: string; done: (name: string | null) => void }) {
  const field = useRef<HTMLInputElement>(null);
  useEffect(() => {
    field.current?.select();
  }, []);
  return (
    <input ref={field} class="deck-name-field" defaultValue={name} maxLength={60} aria-label={t('lblDeckName')}
      onKeyDown={e => {
        if (e.key === 'Enter') done(e.currentTarget.value.trim());
        if (e.key === 'Escape') done(null);
      }}
      onBlur={e => done(e.currentTarget.value.trim())} />
  );
}

function CheckControl({ model, state, actions }: { model: Model; state: EditorState; actions: Actions }) {
  return (
    <CheckSelect model={model} value={state.unrestricted ? 'none' : `${state.format}|${state.cardPool ?? ''}`}
      change={(format, pool, unrestricted) => actions.setCheck(format, pool, unrestricted)} />
  );
}

/** "Check legality against" a format, a Constructed card pool or nothing. value is "format|pool" or "none". */
export function CheckSelect({ model, value, change }: {
  model: Model; value: string; change: (format: string, cardPool: string | null, unrestricted: boolean) => void;
}) {
  const pools = model.cardPools.length ? model.cardPools : [{ kind: 'Sanctioned' as const, formats: model.cardFormats }];
  return (
    <label class="legality set">
      {t('lblWebEditorCheckAgainst')}
      <span class="pill-select"><select value={value} onChange={e => {
        const [format, pool] = e.currentTarget.value.split('|');
        if (format === 'none') change('Constructed', null, true);
        else change(format, pool || null, false);
      }}>
        <option value="Constructed|">{t('lblWebEditorConstructedAnyCards')}</option>
        {pools.map(group => (
          <optgroup key={group.kind} label={t('lblWebEditorConstructedPool', t(CARD_POOL_NAMES[group.kind]))}>
            {group.formats.map(f => <option key={f} value={`Constructed|${f}`}>{f}</option>)}
          </optgroup>
        ))}
        {DECK_FORMATS.filter(([id]) => id !== 'Constructed').map(([id, name]) => <option key={id} value={`${id}|`}>{t(name)}</option>)}
        <option value="none">{t('lblWebEditorNoRestriction')}</option>
      </select></span>
    </label>
  );
}

/** The deck as text to copy or save as a file. For a guest, that file is the only copy outside this browser. */
function TextDialog({ state, close }: { state: EditorState; close: () => void }) {
  const text = deckText(state);
  const area = useRef<HTMLTextAreaElement>(null);
  const [copied, setCopied] = useState(false);
  useEffect(() => {
    area.current?.select();
  }, []);
  const download = () => saveText(text, `${state.name}.txt`, 'text/plain');
  return (
    <Backdrop dismiss={close}>
      <div class="dialog deck-text">
        <h3>{state.name}</h3>
        <textarea ref={area} readOnly value={text} rows={16} />
        <div class="actions">
          <button onClick={download}>{t('lblWebEditorDownloadFile')}</button>
          {navigator.clipboard && (
            <button onClick={async () => { await navigator.clipboard.writeText(text); setCopied(true); }}>{copied ? t('lblWebMatchBarCopied') : t('lblCopy')}</button>
          )}
          <button class="primary" onClick={close}>{t('lblClose')}</button>
        </div>
      </div>
    </Backdrop>
  );
}
