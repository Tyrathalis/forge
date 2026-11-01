import { useEffect, useState } from 'preact/hooks';
import { Pick, StepForm, type Step, type Ticket } from '../stepform';
import { ColourToggles, toggled } from '../symbols';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { QuestOptions } from '../protocol';
import { t, formatNumber, type TextKey } from '../text';

/** How the starting pool's cards are chosen, matching mobile's four buttons. */
interface Distribution { poolType: string; colors: string; artifacts: boolean; completeSet: boolean; duplicates: boolean; boosters: number }

interface NewValue {
  difficulty?: number;
  fantasy?: boolean;
  commander?: boolean;
  world?: string;
  pool?: string;
  /** The pool's own choice: a format, a precon or a saved deck, by name. */
  choice?: string;
  distribution?: Distribution;
  /** "same" to match the starting pool, otherwise a pool type. */
  prizes?: string;
  prizeFormat?: string;
  allowUnlocks?: boolean;
}

/** Mobile's pool types, without its custom set chooser. */
const POOLS: [string, TextKey][] = [['Complete', 'lblUnrestricted'], ['Sanctioned', 'lblSanctionedFormat'], ['Casual', 'lblCasualOrArchivedFormat'],
  ['Precon', 'lblEventOrStartDeck'], ['SealedDeck', 'lblMySealedDeck'], ['DraftDeck', 'lblMyDraftDeck'], ['Cube', 'lblPredefinedCube']];
const PRIZES: [string, TextKey][] = [['same', 'lblSameAsStartingPool'], ['Complete', 'lblUnrestricted'], ['Sanctioned', 'lblSanctionedFormat'],
  ['Casual', 'lblCasualOrArchivedFormat']];
const DISTRIBUTIONS: [string, TextKey][] = [['BALANCED', 'lblBalanced'], ['RANDOM', 'lblTrueRandom'], ['RANDOM_BALANCED', 'lblSurpriseMe'],
  ['BOOSTERS', 'lblBoosters']];
const COMMANDER_WORLD = 'Random Commander';
const nameOf = (list: [string, TextKey][], id?: string) => { const found = list.find(([key]) => key === id); return found ? t(found[1]) : null; };

/** The names a pool type chooses from, or null for a pool type with no further choice. */
function choicesFor(options: QuestOptions, pool?: string): string[] | null {
  switch (pool) {
    case 'Sanctioned': return options.sanctioned;
    case 'Casual': return options.casual;
    case 'Precon': return options.precons;
    case 'SealedDeck': return options.sealedDecks;
    case 'DraftDeck': return options.draftDecks;
    case 'Cube': return options.cubes;
    default: return null;
  }
}

/** A starting pool built from a deck is that deck, so it has no distribution to choose. */
const fromDeck = (pool?: string) => pool === 'Precon' || pool === 'SealedDeck' || pool === 'DraftDeck' || pool === 'Cube';

/** A list of names that typing filters, for the long lists of formats and decks. An empty list says that nothing is saved. */
function Names({ names, pick }: { names: string[]; pick: (name: string) => void }) {
  if (!names.length) return <p class="hint">{t('lblWebQuestNoneSaved')}</p>;
  return <Pick items={names.map(n => [n, n])} placeholder={t('lblSearch')} pick={pick} />;
}

function DistributionStep({ set }: { set: (d: Distribution) => void }) {
  const [d, setD] = useState<Distribution>({ poolType: 'BALANCED', colors: '', artifacts: true, completeSet: false, duplicates: false, boosters: 10 });
  const colours = new Set(d.colors.split('').filter(Boolean));
  return (
    <div class="qu-dist">
      <div class="tiles">
        {DISTRIBUTIONS.map(([id, name]) => (
          <button key={id} class="tile-choice" aria-pressed={d.poolType === id} onClick={() => setD({ ...d, poolType: id })}><b>{t(name)}</b></button>
        ))}
      </div>
      {d.poolType === 'BALANCED' && (
        <div class="rows">
          <span>{t('lblStartingPoolColors')}</span>
          <ColourToggles label={t('lblStartingPoolColors')} colourless pressed={c => colours.has(c)}
            toggle={c => setD({ ...d, colors: [...toggled(colours, c)].join('') })} />
        </div>
      )}
      {d.poolType === 'BOOSTERS' && (
        <label class="rows">{t('lblBoosters')}
          <input type="number" min={1} max={99} value={d.boosters} onInput={e => setD({ ...d, boosters: Number(e.currentTarget.value) || 1 })} />
        </label>
      )}
      <div class="rows">
        {d.poolType !== 'RANDOM_BALANCED' && (
          <label class="qu-check"><input type="checkbox" checked={d.artifacts} onChange={e => setD({ ...d, artifacts: e.currentTarget.checked })} /> {t('lblIncludeArtifactsStartingPool')}</label>
        )}
        <label class="qu-check"><input type="checkbox" checked={d.completeSet} onChange={e => setD({ ...d, completeSet: e.currentTarget.checked })} /> {t('lblStartWithAllCards')}</label>
        <label class="qu-check"><input type="checkbox" checked={d.duplicates} onChange={e => setD({ ...d, duplicates: e.currentTarget.checked })} /> {t('lblAllowDuplicatesStartingPool')}</label>
        <button class="primary" onClick={() => set(d)}>{t('lblContinue')}</button>
      </div>
    </div>
  );
}

export function NewQuest({ model, actions }: { model: Model; actions: Actions }) {
  const [value, setValue] = useState<NewValue>({});
  // The name is asked last and is typed, not picked. Embark uses it as typed
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  // An error from before the form opened belongs to the shelf. This form only shows errors after it sends
  const [sent, setSent] = useState(false);
  useEffect(() => { actions.questOptions(); }, []);
  useEffect(() => setBusy(false), [model.error]);
  const options = model.questOptions;
  if (!options) return <p class="muted pools-wait">{t('lblWebQuestReading')}</p>;
  const difficulty = value.difficulty === undefined ? null : options.difficulties[value.difficulty];
  const worldOf = (v: NewValue) => (v.commander ? COMMANDER_WORLD : v.world);
  // A world with its own sets builds the pool from them. Mobile's screen greys out the pool choices in this case
  const ownSets = (v: NewValue) => options.formatWorlds.includes(worldOf(v) ?? '');
  const prizesFrom = (v: NewValue) => (v.prizes === 'Sanctioned' ? options.sanctioned : v.prizes === 'Casual' ? options.casual : null);
  const complete = value.prizes !== undefined && (prizesFrom(value) === null || value.prizeFormat !== undefined);
  const ready = complete && name.trim().length > 0;
  const start = () => {
    if (!ready || busy) return;
    setBusy(true);
    setSent(true);
    const d = value.distribution;
    const pool = ownSets(value) ? 'Complete' : value.pool!;
    actions.questCreate({
      name: name.trim(), difficulty: value.difficulty!, fantasy: !!value.fantasy, commander: !!value.commander,
      world: worldOf(value)!, pool,
      format: pool === 'Sanctioned' || pool === 'Casual' ? value.choice : undefined, precon: pool === 'Precon' ? value.choice : undefined,
      savedDeck: fromDeck(pool) && pool !== 'Precon' ? value.choice : undefined,
      poolType: d?.poolType ?? 'BALANCED', colors: d?.colors ?? '', artifacts: d?.artifacts ?? true, completeSet: d?.completeSet ?? false,
      duplicates: d?.duplicates ?? false, boosters: d?.boosters ?? 0,
      prizes: value.prizes === 'same' ? undefined : value.prizes, prizeFormat: value.prizeFormat, allowUnlocks: value.allowUnlocks ?? true,
    });
  };
  const steps: Step<NewValue>[] = [
    {
      id: 'difficulty', label: t('lblDifficulty'), hint: '', fields: ['difficulty'],
      answer: v => (v.difficulty === undefined ? null : options.difficulties[v.difficulty].name),
      render: (_, set) => (
        <div class="tiles">
          {options.difficulties.map((d, i) => (
            <button key={d.name} class="tile-choice" onClick={() => set({ difficulty: i })}>
              <b>{d.name}</b><span>{t('lblWebQuestDifficultyLine', d.credits, d.commons, d.uncommons, d.rares)}</span>
            </button>
          ))}
        </div>
      ),
    },
    {
      id: 'mode', label: t('lblWebMatchBarMode'), hint: '', fields: ['fantasy', 'commander'],
      answer: v => (v.fantasy === undefined ? null : (v.fantasy ? t('rbFantasyMode') : t('lblClassicMode')) + (v.commander ? ` · ${t('rbCommanderSubformat')}` : '')),
      render: (v, set) => (
        <div class="qu-dist">
          <label class="qu-check"><input type="checkbox" checked={!!v.commander} onChange={e => setValue({ ...v, commander: e.currentTarget.checked })} /> {t('rbCommanderSubformat')}</label>
          <div class="tiles">
            <button class="tile-choice" onClick={() => set({ fantasy: true, commander: !!v.commander })}><b>{t('rbFantasyMode')}</b></button>
            <button class="tile-choice" onClick={() => set({ fantasy: false, commander: !!v.commander })}><b>{t('lblClassicMode')}</b></button>
          </div>
        </div>
      ),
    },
    {
      // A Commander quest starts in the commander decks world, as on mobile
      id: 'world', label: t('lblStartingWorld'), hint: '', fields: ['world'], applies: v => !v.commander,
      answer: v => v.world ?? null,
      render: (_, set) => <Names names={options.worlds} pick={world => set({ world })} />,
    },
    {
      id: 'pool', label: t('lblStartingPool'), hint: '', fields: ['pool'], applies: v => !ownSets(v),
      answer: v => nameOf(POOLS, v.pool),
      render: (_, set) => (
        <div class="tiles">{POOLS.map(([id, name]) => <button key={id} class="tile-choice" onClick={() => set({ pool: id })}><b>{t(name)}</b></button>)}</div>
      ),
    },
    {
      id: 'choice', label: t('lblWebQuestPoolChoice'), hint: '', fields: ['choice'], applies: v => !ownSets(v) && choicesFor(options, v.pool) !== null,
      answer: v => v.choice ?? null,
      render: (v, set) => <Names names={choicesFor(options, v.pool) ?? []} pick={choice => set({ choice })} />,
    },
    {
      id: 'distribution', label: t('lblStartingPoolDistribution'), hint: '', fields: ['distribution'], applies: v => ownSets(v) || !fromDeck(v.pool),
      answer: v => (v.distribution ? nameOf(DISTRIBUTIONS, v.distribution.poolType) + (v.distribution.colors ? ` · ${v.distribution.colors.split('').join(' ')}` : '') : null),
      render: (_, set) => <DistributionStep set={distribution => set({ distribution })} />,
    },
    {
      id: 'prizes', label: t('lblPrizedCards'), hint: t('lblSameAsStartingPool'), fields: ['prizes', 'allowUnlocks'],
      answer: v => nameOf(PRIZES, v.prizes),
      render: (v, set) => (
        <div class="qu-dist">
          <div class="tiles">
            {PRIZES.map(([id, name]) => <button key={id} class="tile-choice" onClick={() => set({ prizes: id, allowUnlocks: v.allowUnlocks ?? true })}><b>{t(name)}</b></button>)}
          </div>
          <label class="qu-check"><input type="checkbox" checked={v.allowUnlocks ?? true} onChange={e => setValue({ ...v, allowUnlocks: e.currentTarget.checked })} /> {t('lblAllowUnlockAdEd')}</label>
        </div>
      ),
    },
    {
      id: 'prizeFormat', label: t('lblDefinedFormat'), hint: '', fields: ['prizeFormat'], applies: v => prizesFrom(v) !== null,
      answer: v => v.prizeFormat ?? null,
      render: (v, set) => <Names names={prizesFrom(v) ?? []} pick={prizeFormat => set({ prizeFormat })} />,
    },
    {
      id: 'name', label: t('MsgQuestNewName'), short: t('lblQuestName'), hint: '', fields: [],
      answer: () => null,
      render: () => (
        <form class="rows" onSubmit={e => { e.preventDefault(); start(); }}>
          <input type="text" placeholder={t('lblQuestName')} aria-label={t('lblQuestName')} maxLength={60} autoFocus value={name} onInput={e => setName(e.currentTarget.value)} />
        </form>
      ),
    },
  ];
  const ticket: Ticket<NewValue> = {
    kicker: t('lblWebQuestNew'),
    title: () => name.trim() || t('lblQuestMode'),
    rows: v => [
      [t('lblDifficulty'), difficulty?.name ?? null],
      [t('lblWebMatchBarMode'), v.fantasy === undefined ? null : v.fantasy ? t('rbFantasyMode') : t('lblClassicMode')],
      [t('lblStartingWorld'), worldOf(v) ?? null],
      [t('lblStartingPool'), ownSets(v) ? worldOf(v) ?? null : v.choice ?? nameOf(POOLS, v.pool)],
      [t('lblPrizedCards'), v.prizeFormat ?? nameOf(PRIZES, v.prizes)],
      [t('lblStartingCredits'), difficulty ? formatNumber(difficulty.credits) : null],
    ],
    note: t('lblWebQuestTicketNote'),
  };
  return (
    <div class="setup qu-new-quest">
      <StepForm steps={steps} value={value} onChange={setValue} ticket={ticket} action={t('lblEmbark')} busy={busy} ready={ready}
        problem={sent && model.error ? <span class="sentence taken">{model.error}</span> : null} submit={start} />
    </div>
  );
}
