import { useEffect, useState } from 'preact/hooks';
import { imageUrl } from '../images';
import { FIVE_COLOURS } from '../symbols';
import { DeckPanel, Plate } from '../campaign/deckplate';
import { AnyOf, FilterBar, type FilterKind, OneOf } from '../deck/filters';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestCommanderRow, ConquestWalkerRow } from '../protocol';
import { t } from '../text';
import { CardGroup, Curve } from '../deck/deckview';

/** The filter for both lists: any of these colours, and for a commander the plane it comes from. A planeswalker has no origin. */
interface PartyFilter { colours: string[]; plane: string | null }
const NO_FILTER: PartyFilter = { colours: [], plane: null };

/** A commander's origin is its plane and region. The filter offers only the plane. */
const planeOf = (origin: string): string => origin.split(' - ')[0];

export function Party({ model, actions }: { model: Model; actions: Actions }) {
  const party = model.conquestParty;
  const [chosen, setChosen] = useState<{ walker: boolean; name: string } | null>(null);
  const [typed, setTyped] = useState('');
  const [filter, setFilter] = useState<PartyFilter>(NO_FILTER);
  useEffect(() => { actions.conquestParty(); }, []);

  const lead = party?.commanders.find(c => c.selected);
  const commander = chosen?.walker ? undefined : party?.commanders.find(c => c.name === chosen?.name) ?? lead;
  const walker = chosen?.walker ? party?.planeswalkers.find(w => w.name === chosen.name) : undefined;
  // The panel's curve and list come from the deck's details. They are requested again when the shown commander or its deck size changes
  useEffect(() => { if (commander) actions.conquestViewDeck(commander.name); }, [commander?.name, commander?.deckSize]);
  if (!party) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;

  const words = typed.trim().toLowerCase();
  const shown = (name: string, colors: string, origin?: string): boolean => name.toLowerCase().includes(words)
    && (!filter.colours.length || filter.colours.some(c => colors.includes(c)))
    && (!filter.plane || origin === undefined || planeOf(origin) === filter.plane);
  const planes = [...new Set(party.commanders.map(c => planeOf(c.origin)).filter(Boolean))].sort();
  const kinds: FilterKind<PartyFilter>[] = [
    {
      id: 'identity', group: t('lblCommanders'), label: t('lblColorIdentity'),
      chip: f => (f.colours.length ? f.colours.join(' ') : null),
      clear: f => ({ ...f, colours: [] }),
      panel: (f, set, done) => <AnyOf options={FIVE_COLOURS.map(([c, name]) => [c, t(name)] as const)} value={f.colours}
        apply={colours => { set({ ...f, colours }); done(); }} />,
    },
    {
      id: 'origin', group: t('lblCommanders'), label: t('lblOrigin'),
      chip: f => f.plane,
      clear: f => ({ ...f, plane: null }),
      panel: (f, set, done) => <OneOf options={planes.map(p => [p, p] as const)} value={f.plane} pick={plane => { set({ ...f, plane }); done(); }} />,
    },
  ];
  const details = commander && model.deckDetails?.key === `conquest:${commander.name}` ? model.deckDetails : null;
  const commanders = party.commanders.filter(c => shown(c.name, c.colors, c.origin));
  const walkers = party.planeswalkers.filter(w => shown(w.name, w.colors));
  return (
    <div class="cq-party">
      <div class="cq-party-list">
        <FilterBar kinds={kinds} filter={filter} set={setFilter} narrowed={!!typed || filter.colours.length > 0 || !!filter.plane}
          clearAll={() => { setTyped(''); setFilter(NO_FILTER); }}>
          <input class="find" type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
        </FilterBar>
        <section>
          <h4>{t('lblCommanders')}<span>{party.commanders.length}</span></h4>
          <div class="cq-cmd-grid">
            {commanders.map(c => <Plate key={c.name} name={c.name} image={c.image} colors={c.colors} selected={c.selected}
              pressed={c === commander} pick={() => setChosen({ walker: false, name: c.name })} choose={() => actions.conquestLead(c.name)}
              lines={[`${c.origin} (${c.wins}W / ${c.losses}L)`, <DeckLine commander={c} />]} />)}
          </div>
        </section>
        <section>
          <h4>{t('lblPlaneswalkers')}<span>{party.planeswalkers.length}</span></h4>
          <div class="cq-cmd-grid">
            {walkers.map(w => <Plate key={w.name} name={w.name} image={w.image} colors={w.colors} selected={w.selected}
              pressed={w === walker} pick={() => setChosen({ walker: true, name: w.name })} choose={() => actions.conquestWalker(w.name)} lines={[t('lblPlaneswalker')]} />)}
          </div>
        </section>
      </div>
      {walker ? <WalkerPanel walker={walker} actions={actions} />
        : commander && (
          <DeckPanel image={commander.image} title={commander.name} problem={commander.deckSize > 0 ? commander.problem : null}
            chips={<>
              {commander.origin && <span class="cq-chip">{commander.origin}</span>}
              <span class="cq-chip">{commander.wins}W / {commander.losses}L</span>
              <span class="cq-chip"><DeckLine commander={commander} /></span>
            </>}
            details={details && commander.deckSize > 0 && <>
              <Curve curve={details.stats.curve} spells={details.stats.spells} px={44} />
              <div class="dk-cards" data-list>{details.main.map(g => <CardGroup key={g.heading} heading={g.heading} cards={g.cards} />)}</div>
            </>}>
              <button onClick={() => actions.conquestEditDeck(commander.name)}>{t('btnEditDeck')}</button>
              <button class="primary cq-big" disabled={commander.selected} onClick={() => actions.conquestLead(commander.name)}>
                {commander.selected ? t('lblSelected') : t('lblSelectCommander')}
              </button>
          </DeckPanel>
        )}
    </div>
  );
}

function DeckLine({ commander }: { commander: ConquestCommanderRow }) {
  if (!commander.deckSize) return <>{t('lblNoDeck')}</>;
  return commander.problem
    ? <span class="cq-warn" title={commander.problem}>{t('lblInvalidDeck')} · {commander.deckSize}</span>
    : <>{t('lblWebNCards', commander.deckSize)}</>;
}

function WalkerPanel({ walker, actions }: { walker: ConquestWalkerRow; actions: Actions }) {
  return (
    <aside class="cq-side">
      <div class="cq-cmd-detail">
        <div class="cq-cmd-top">
          <img class="cq-card" alt={walker.name} src={imageUrl(walker.image)} data-image={walker.image} />
          <div class="cq-cmd-about">
            <h3>{walker.name}</h3>
            <div class="cq-chips"><span class="cq-chip">{t('lblPlaneswalker')}</span></div>
          </div>
        </div>
      </div>
      <div class="cq-foot">
        <button class="primary cq-big" disabled={walker.selected} onClick={() => actions.conquestWalker(walker.name)}>
          {walker.selected ? t('lblSelected') : t('lblWebConquestSelectWalker')}
        </button>
      </div>
    </aside>
  );
}
