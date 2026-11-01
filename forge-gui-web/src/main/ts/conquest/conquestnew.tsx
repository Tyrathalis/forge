// Starting a conquest. The form requests the planes, then a plane's commanders, then a commander's planeswalkers, as each choice is made

import { useEffect, useRef, useState } from 'preact/hooks';
import { artUrl } from '../sleeves';
import { imageUrl, hideBroken } from '../images';
import { Pip, Pips } from '../symbols';
import { StepForm, type Step, type Ticket } from '../stepform';
import { Prefs } from '../campaign/campaign';
import { ICONS, Icon } from '../icons';
import { reducedMotion } from '../dom';
import { endPlaneswalk, planeswalk } from './planeswalk';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { ConquestCardOption, ConquestOptions, ConquestPlaneOption } from '../protocol';
import { t, formatNumber } from '../text';

interface NewValue { plane?: string; commander?: string; planeswalker?: string }

export function NewConquest({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<NewValue>({});
  // The name is the last step and is typed, not picked. Start uses it as typed, with no step to close first
  const [name, setName] = useState('');
  // Creating a conquest builds its first deck. Until the map or an error arrives, a second click would create another
  const [busy, setBusy] = useState(false);
  // An error from before the form opened belongs to the saves list, so this form does not show it
  const [sent, setSent] = useState(false);
  const [prefs, setPrefs] = useState(false);
  useEffect(() => { actions.conquestOptions(value.plane, value.commander); }, [value.plane, value.commander]);
  // A refused conquest has no map to open on, so its opening ends and the form shows why
  useEffect(() => {
    setBusy(false);
    if (model.error) endPlaneswalk();
  }, [model.error]);
  const options = model.conquestOptions;
  if (!options) return <p class="muted pools-wait">{t('lblWebConquestReading')}</p>;
  // Each list belongs to the plane or commander it was requested for. A list for an older choice is not shown for a newer one
  const commanders = options.plane === value.plane ? options.commanders ?? null : null;
  const walkers = options.plane === value.plane && options.commander === value.commander ? options.planeswalkers ?? null : null;
  const ready = !!value.planeswalker && name.trim().length > 0;
  const start = () => {
    if (!ready || busy) return;
    setBusy(true);
    setSent(true);
    const chosen = options.planes.find(p => p.name === value.plane);
    if (chosen) planeswalk(chosen.name, chosen.art, chosen.events);
    actions.conquestCreate({ name: name.trim(), plane: value.plane!, commander: value.commander!, planeswalker: value.planeswalker! });
  };
  const steps: Step<NewValue>[] = [
    {
      id: 'plane', label: t('lblWebConquestStartingPlane'), short: t('lblPlane'), hint: '', fields: ['plane'],
      answer: v => v.plane ?? null,
      render: (_, set) => <PlaneTiles planes={options.planes} pick={plane => set({ plane })} />,
    },
    {
      id: 'commander', label: t('lblSelectStartingCommander'), short: t('lblCommander'), hint: '', fields: ['commander'],
      answer: v => v.commander ?? null,
      render: (_, set) => <CardPick rows={commanders} pick={commander => set({ commander })} />,
    },
    {
      id: 'planeswalker', label: t('lblSelectStartingPlaneswalker'), short: t('lblPlaneswalker'), hint: '', fields: ['planeswalker'],
      answer: v => v.planeswalker ?? null,
      render: (_, set) => <CardPick rows={walkers} pick={planeswalker => set({ planeswalker })} />,
    },
    {
      id: 'name', label: t('lblHistoriiansWillRecallYourConquestAs'), short: t('lblConquestName'), hint: '', fields: [],
      answer: () => null,
      render: () => (
        <form class="rows" onSubmit={e => { e.preventDefault(); start(); }}>
          <input type="text" placeholder={t('lblConquestName')} maxLength={60} autoFocus value={name} onInput={e => setName(e.currentTarget.value)} />
        </form>
      ),
    },
  ];
  const ticket: Ticket<NewValue> = {
    kicker: t('lblWebConquestNew'),
    title: v => name.trim() || v.plane || t('lblPlanarConquest'),
    picture: v => <Picture value={v} options={options} />,
    rows: v => [[t('lblPlane'), v.plane ?? null], [t('lblCommander'), v.commander ?? null], [t('lblPlaneswalker'), v.planeswalker ?? null],
      [t('lblStartingShards'), formatNumber(options.startShards)]],
    note: '',
    extra: <button class="ticket-prefs" onClick={() => setPrefs(true)}><Icon d={ICONS.sliders} />{t('Preferences')}</button>,
  };
  return (
    <div class="setup cq-new">
      <StepForm steps={steps} value={value} onChange={setValue} ticket={ticket} action={t('lblStart')} busy={busy} ready={ready}
        problem={sent && model.error ? <span class="sentence taken">{model.error}</span> : null} submit={start} />
      {/* The starting shards are a preference, so the ticket asks for them again when the dialog closes */}
      {prefs && <Prefs model={model} actions={actions} close={() => { setPrefs(false); actions.conquestOptions(value.plane, value.commander); }} />}
    </div>
  );
}

function Picture({ value, options }: { value: NewValue; options: ConquestOptions }) {
  const commander = options.commanders?.find(c => c.name === value.commander);
  const walker = options.planeswalkers?.find(c => c.name === value.planeswalker);
  if (commander) {
    return (
      <div class="cq-ticket-cards">
        <TicketCard card={commander} />
        {walker && <TicketCard card={walker} />}
      </div>
    );
  }
  const plane = options.planes.find(p => p.name === value.plane);
  return plane ? <div class="cq-ticket-art" style={{ backgroundImage: `url("${artUrl(plane.art)}")` }} /> : null;
}

/** A chosen card on the ticket. Its name is shown in its place until its picture loads, or if it has no picture. */
function TicketCard({ card }: { card: ConquestCardOption }) {
  return (
    <span class="cq-pic" key={card.name} data-image={card.image}>
      <span class="nm">{card.name}</span>
      <img alt="" src={imageUrl(card.image)} onError={hideBroken} />
    </span>
  );
}

const anyOf = <T,>(shown: T[]) => shown[Math.floor(Math.random() * shown.length)];

/** Resolves once the picture has loaded or failed. A slow download stops holding the roll after ART_WAIT_MS. */
const loaded = (src: string) => new Promise<void>(done => {
  const img = new Image();
  img.onload = img.onerror = () => done();
  img.src = src;
  setTimeout(done, ART_WAIT_MS);
});
const ART_WAIT_MS = 1500;

/**
 * Random rolls through the shown choices in its own box, slowing down, then picks one. A second click stops on the pick at once.
 * face is the choice the box shows, and under is the one before it, which the new face fades in over.
 * Each face waits for its art, so a name never shows before its picture.
 */
function useRoll<T extends { name: string }>(shown: T[], pick: (name: string) => void, art: (face: T) => string) {
  const [faces, setFaces] = useState<{ face: T | null; under: T | null }>({ face: null, under: null });
  // A roll stops when this number moves on: on a second click, at its end, or when the box closes
  const run = useRef(0);
  const stop = useRef<(() => void) | null>(null);
  useEffect(() => () => { run.current++; }, []);
  const show = (face: T) => setFaces(f => ({ face, under: f.face }));
  const roll = () => {
    if (stop.current) {
      stop.current();
      return;
    }
    const choice = anyOf(shown);
    if (reducedMotion() || shown.length < 2) {
      pick(choice.name);
      return;
    }
    const id = ++run.current;
    stop.current = () => {
      run.current++;
      stop.current = null;
      show(choice);
      pick(choice.name);
    };
    const from = shown.indexOf(choice) + 1;
    const sequence = Array.from({ length: ROLLS }, (_, i) => (i === ROLLS - 1 ? choice : shown[(from + i) % shown.length]));
    const ready = sequence.map(face => loaded(art(face)));
    void (async () => {
      for (let i = 0; i < ROLLS; i++) {
        await ready[i];
        if (run.current !== id) return;
        show(sequence[i]);
        await new Promise(next => setTimeout(next, 40 * 1.15 ** i));
        if (run.current !== id) return;
      }
      stop.current?.();
    })();
  };
  return { ...faces, roll };
}
const ROLLS = 12;

function PlaneTiles({ planes, pick }: { planes: ConquestPlaneOption[]; pick: (plane: string) => void }) {
  const [looking, setLooking] = useState<ConquestPlaneOption | null>(null);
  const [typed, setTyped] = useState('');
  const words = typed.trim().toLowerCase();
  const shown = planes.filter(p => p.name.toLowerCase().includes(words));
  const { face, under, roll } = useRoll(shown, pick, p => artUrl(p.art));
  return (
    <div class="cq-pick">
      <input type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
      <div class="cq-plane-grid">
        {shown.length > 0 && (
          <button class="cq-plane random" onPointerEnter={() => setLooking(null)} onFocus={() => setLooking(null)} onClick={roll}>
            <div class="a" style={under ? { backgroundImage: `url("${artUrl(under.art)}")` } : undefined}>
              {face ? <span key={face.name} class="cq-roll" style={{ backgroundImage: `url("${artUrl(face.art)}")` }} /> : '?'}
            </div>
            <b>{face?.name ?? t('lblRandom')}</b>
            <span>{face ? t('lblWebConquestEvents', face.events) : t('lblWebConquestRandomPlane')}</span>
          </button>
        )}
        {shown.map(p => (
          <button key={p.name} class="cq-plane" onPointerEnter={() => setLooking(p)} onFocus={() => setLooking(p)} onClick={() => pick(p.name)}>
            <div class="a" style={{ backgroundImage: `url("${artUrl(p.art)}")` }} />
            <b>{p.name}</b>
            <span>{t('lblWebConquestEvents', p.events)}</span>
          </button>
        ))}
        {shown.length === 0 && <p class="hint">{t('lblWebSetupNothingMatches')}</p>}
      </div>
      <p class="cq-plane-desc">{looking?.description}</p>
    </div>
  );
}

/** Commanders or planeswalkers in one column, filtered by a search. Pointing at one shows its card. rows is null until the server has answered. */
function CardPick({ rows, pick }: { rows: ConquestCardOption[] | null; pick: (name: string) => void }) {
  const [typed, setTyped] = useState('');
  const words = typed.trim().toLowerCase();
  const shown = (rows ?? []).filter(r => r.name.toLowerCase().includes(words));
  const { face, under, roll } = useRoll(shown, pick, r => artUrl(r.image));
  if (!rows) return <p class="hint">{t('lblWebConquestReading')}</p>;
  return (
    <div class="cq-pick">
      <input type="search" placeholder={t('lblSearch')} autocomplete="off" value={typed} onInput={e => setTyped(e.currentTarget.value)} />
      <div class="cq-pick-list" data-list>
        {shown.length > 0 && (
          <button class="cq-pick-row random" onClick={roll}>
            <i style={under ? { backgroundImage: `url("${artUrl(under.image)}")` } : undefined}>
              {face ? <span key={face.name} class="cq-roll" style={{ backgroundImage: `url("${artUrl(face.image)}")` }} /> : '?'}
            </i>
            <b>{face?.name ?? t('lblRandom')}</b>
          </button>
        )}
        {shown.map(r => (
          <button key={r.name} class="cq-pick-row" data-image={r.image} data-act={t('lblChoose')} onClick={() => pick(r.name)}>
            <i style={{ backgroundImage: `url("${artUrl(r.image)}")` }} />
            <b>{r.name}</b>
            <span class="pips">{r.colors ? <Pips colors={r.colors} /> : <Pip letter="C" />}</span>
            {r.region && <span class="r">{r.region}</span>}
          </button>
        ))}
        {shown.length === 0 && <p class="hint">{t('lblWebSetupNothingMatches')}</p>}
      </div>
    </div>
  );
}
