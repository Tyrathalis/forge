import { Shelf as Cards } from '../shelf';
import { ui } from '../ui';
import type { QuestTab } from '../campaign/register';
import { CampaignPages, CampaignShelf, CampaignShell, Purse, Stats, slideTab } from '../campaign/campaign';
import { Duels } from './questduels';
import { Decks } from './questdecks';
import { Shop } from './questshop';
import { Challenges } from './questchallenges';
import { Bazaar } from './questbazaar';
import { Tournaments } from './questtournaments';
import { NewQuest } from './questnew';
import { CREDITS } from './credits';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type { QuestSave } from '../protocol';
import { t, shortDay, type TextKey } from '../text';

const TABS: [QuestTab, TextKey][] = [['duels', 'lblDuels'], ['challenges', 'lblChallenges'], ['tournaments', 'lblTournaments'], ['decks', 'lblQuestDecks'],
  ['shop', 'lblSpellShop'], ['bazaar', 'lblBazaar'], ['stats', 'lblStatistics']];
/** Only Fantasy mode has the bazaar. A Classic quest is recognised by having no life balance on its bar. */
const FANTASY_ONLY: QuestTab[] = ['bazaar'];

export function Quest({ model, actions }: { model: Model; actions: Actions }) {
  const saves = model.questSaves;
  return (
    <CampaignShell model={model} actions={actions} icons={[CREDITS, 'ICO_QUEST_LIFE']}
      shelf={(creating, setCreating) => (
        <CampaignShelf model={model} actions={actions} creating={creating} setCreating={setCreating}
          words={{ mode: t('lblQuestMode'), new: t('lblWebQuestNew'), yours: t('lblWebQuestYours'), reading: t('lblWebQuestReading') }}
          form={<NewQuest model={model} actions={actions} />}
          saves={saves && <Saves saves={saves.saves} current={saves.current ?? null} actions={actions} create={() => setCreating(true)} />} />
      )}
      open={() => <Campaign model={model} actions={actions} />} />
  );
}

function Saves({ saves, current, actions, create }: { saves: QuestSave[]; current: string | null; actions: Actions; create: () => void }) {
  return (
    <Cards rows={saves} cls={s => (s.name === current ? 'ev qu-save current' : 'ev qu-save')}
      create={{ title: t('lblWebQuestNew'), line: t('lblWebQuestNewLine'), go: create }}
      rename={{ field: t('lblQuestName'), go: (s, to) => actions.campaignRename(s.name, to) }}
      remove={{ item: t('lblDelete'), ask: s => t('lblAreYouSuerDeleteConquest', s.name), keep: t('lblCancel'), go: s => actions.campaignDelete(s.name) }}
      sub={s => <span class="sub">{s.difficulty ? `${s.mode} · ${s.difficulty}` : s.mode}</span>}
      mid={s => <>
        <span class="ev-line">{s.rank}</span>
        <span class="ev-line dim">
          <b class="pct">{t('lblWebQuestRecord', s.wins, s.losses)}</b>
          <span class="sp" />
          {t('lblWebNCards', s.cards)}
          <Purse icon={CREDITS} n={s.credits} label={t('lblCredits')} />
        </span>
        <span class="ev-line dim">{s.world}</span>
      </>}
      foot={s => <>
        <button class="primary" onClick={() => actions.campaignLoad(s.name)}>{t('lblPlay')}</button>
        {s.saved && <span class="ev-ask">{t('lblWebLimitedSaved', shortDay(s.saved))}</span>}
      </>} />
  );
}

function Campaign({ model, actions }: { model: Model; actions: Actions }) {
  if (!model.campaignBar || !model.questDuels || !model.questDecks || !model.questChallenges || !model.questTournaments) return <p class="muted pools-wait">{t('lblWebQuestReading')}</p>;
  const fantasy = model.campaignBar.balances.some(b => b.icon === 'ICO_QUEST_LIFE');
  const tabs = fantasy ? TABS : TABS.filter(([id]) => !FANTASY_ONLY.includes(id));
  return (
    <CampaignPages model={model} actions={actions} tabs={tabs} tab={ui.questTab}
      setTab={tab => slideTab(tabs, ui.questTab, tab, u => { u.questTab = tab; })}
      page={key => <Page key={key} tab={ui.questTab} fantasy={fantasy} model={model} actions={actions} />} />
  );
}

function Page({ tab, fantasy, model, actions }: { tab: QuestTab; fantasy: boolean; model: Model; actions: Actions }) {
  switch (tab) {
    case 'decks': return <Decks page={model.questDecks!} model={model} actions={actions} />;
    case 'shop': return <Shop model={model} actions={actions} />;
    case 'stats': return <Stats model={model} actions={actions} />;
    case 'tournaments': return <Tournaments model={model} page={model.questTournaments!} actions={actions} />;
    case 'challenges': return <Challenges page={model.questChallenges!} duels={model.questDuels!} actions={actions} />;
    case 'bazaar': return fantasy ? <Bazaar model={model} actions={actions} /> : <Duels page={model.questDuels!} actions={actions} />;
    default: return <Duels page={model.questDuels!} actions={actions} />;
  }
}
