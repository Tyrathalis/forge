import { registerCampaigns } from '../campaigns';
import { Kind } from '../menu';
import { ui } from '../ui';
import { t } from '../text';
import { Conquest } from '../conquest/conquest';
import { Quest } from '../quest/quest';
import { TournamentDrafted } from '../quest/tournamentdrafted';
import type { Actions } from '../actions';
import type { Model } from '../model';
import type {
  CampaignBar, CampaignPrefs, CampaignResult, CampaignStats, ConquestAetherState, ConquestOptions, ConquestParty, ConquestPlanes,
  ConquestSaves, ConquestState, QuestBazaar, QuestChallenges, QuestCreate, QuestDecks, QuestDuels, QuestOptions, QuestSaves, QuestTournaments,
  Reward, Trading,
} from '../protocol';

/** The modes, by the names the server uses for them. */
export type CampaignMode = 'conquest' | 'quest';
export type ConquestTab = 'map' | 'aether' | 'party' | 'collection' | 'planes' | 'stats';
export type QuestTab = 'duels' | 'challenges' | 'tournaments' | 'decks' | 'shop' | 'bazaar' | 'stats';

declare module '../model' {
  interface Model {
    conquestSaves: ConquestSaves | null;
    questSaves: QuestSaves | null;
    questDuels: QuestDuels | null;
    questDecks: QuestDecks | null;
    questChallenges: QuestChallenges | null;
    /** The bazaar with one stall open. It is set once the Bazaar tab has requested it. */
    questBazaar: QuestBazaar | null;
    /** The choices the new-quest form offers. It is set once the form has requested them. */
    questOptions: QuestOptions | null;
    questTournaments: QuestTournaments | null;
    campaignBar: CampaignBar | null;
    conquestState: ConquestState | null;
    /** The result of a battle's game while its match is open. reward holds what a won battle gave until it has been shown. */
    campaignResult: CampaignResult | null;
    reward: Reward | null;
    /** The commanders and planeswalkers found. It is set once the Commanders page has requested them. */
    conquestParty: ConquestParty | null;
    /** The sizes of the lists a campaign trades between. It is set once the trade page has requested them. */
    trading: Trading | null;
    /** The choices a new conquest can start with, as far as the form has requested them. */
    conquestOptions: ConquestOptions | null;
    /** The Aether, the planes, the statistics and the preferences. Each holds what its page last requested. */
    conquestAether: ConquestAetherState | null;
    conquestPlanes: ConquestPlanes | null;
    campaignStats: CampaignStats | null;
    campaignPrefs: CampaignPrefs | null;
  }
}

declare module '../ui' {
  interface UiState {
    /** The page of the open conquest that is shown. It is kept while the deck editor replaces the page for a while. */
    conquestTab: ConquestTab;
    questTab: QuestTab;
    conquestOpened: Record<string, boolean>;
    /** Dev mode: where the next Chaos Wheel stops. It is kept here because on a phone it is chosen in the header's menu, which closes after each choice. */
    conquestWheel: string;
  }
}

declare module '../actions' {
  interface Actions {
    /** Opens a mode's saved games. With resume, it opens the save played last. */
    campaignOpen(mode: CampaignMode, resume: boolean): void;
    campaignLoad(name: string): void;
    /** Goes back from an open save to the saved games, or from the saved games to the start page. */
    campaignLeave(): void;
    campaignRename(name: string, to: string): void;
    campaignDelete(name: string): void;
    /** Selects a place on the map. The player does not move. */
    conquestSelect(region: number, row: number, col: number): void;
    /** Walks to the selected place. */
    conquestMove(): void;
    /** Starts the battle for the event at the player's current place. */
    conquestBattle(): void;
    /** Tells the server that a won battle's reward has been shown. */
    rewardClaim(): void;
    /** Dev mode: sets where the next Chaos Wheel stops. */
    devConquestWheel(outcome: string): void;
    /** Requests the choices for a new conquest. These are the planes, a plane's commanders, or a commander's planeswalkers. */
    conquestOptions(plane?: string, commander?: string): void;
    conquestCreate(c: { name: string; plane: string; commander: string; planeswalker: string }): void;
    /** Requests the Aether's cards under these filters. With pull, it spends shards for a card. An empty type requests the filters a visit starts with. */
    conquestAether(q: { colors: string; type: string; rarity: string; cmc: string; pull: boolean }): void;
    conquestPlanes(): void;
    /** Travels to a plane. With unlock, it first spends emblems to unlock the plane. */
    conquestPlaneswalk(plane: string, unlock: boolean): void;
    /** Requests the campaign's statistics. In Conquest a scope is a plane, and no scope means every plane. */
    campaignStats(scope?: string): void;
    campaignPrefs(): void;
    campaignPref(key: string, value: string): void;
    campaignPrefsReset(): void;
    /** Requests the commanders and planeswalkers found. */
    conquestParty(): void;
    conquestLead(commander: string): void;
    conquestWalker(planeswalker: string): void;
    /** Requests a commander's deck. It arrives in the same message as other deck details. */
    conquestViewDeck(commander: string): void;
    conquestEditDeck(commander: string): void;
    /** Requests the sizes of the lists a campaign trades between. */
    trading(): void;
    /** Trades the picks out of the list for that source. In Conquest this exiles them or brings them back. */
    trade(source: string, picks: { key: string; count: number }[]): void;

    /** Starts a duel from the Duels page, by its index in the list. */
    questDuel(index: number): void;
    /** Chooses the pet for a slot. The plant uses slot 0. null chooses no pet. */
    questPet(slot: number, name: string | null): void;
    questMatchLength(games: number): void;
    /** Creates an empty deck with the given name and opens it in the editor. */
    questDeckNew(name: string): void;
    questDeckRename(deck: string, to: string): void;
    /** Makes a deck the one used for duels. */
    questDeckCurrent(deck: string): void;
    /** Requests a deck's list. It arrives in the same message as other deck details. */
    questDeckView(deck: string): void;
    questDeckEdit(deck: string): void;
    questDeckDelete(deck: string): void;
    /** Requests the choices the new-quest form offers. */
    questOptions(): void;
    questChallenge(id: string): void;
    /** Flies the zeppelin. It redraws the challenges, and can be used once between two matches. */
    questZeppelin(): void;
    /** Opens a stall of the bazaar. null opens the first stall. */
    questStall(name: string | null): void;
    questBuy(stall: string, item: string): void;
    /** Enters a tournament on offer. This pays its fee and opens its draft. */
    questEnter(title: string): void;
    /** Spends a draft token. The host then asks for the format. */
    questToken(): void;
    questTournamentStart(): void;
    questTournamentNext(): void;
    /** Leaves the tournament. If it has started, its prizes are collected. */
    questTournamentLeave(): void;
    /** Opens the tournament's deck in the editor. */
    questTournamentDeck(): void;
    /** Travels to another world. The host asks which one. */
    questTravel(): void;
    /** Unlocks a set. The host asks which one. */
    questUnlock(): void;
    questCreate(c: Omit<QuestCreate, 't'>): void;
  }
}

/** An open campaign's page fields when no campaign is open. hello resets them to this when the campaign is left. */
const NO_CAMPAIGN = { campaignBar: null, reward: null, trading: null, campaignStats: null } satisfies Partial<Model>;
/** The same for an open conquest's own pages. */
const NO_CONQUEST = { conquestState: null, conquestParty: null, conquestAether: null, conquestPlanes: null } satisfies Partial<Model>;
/** The same for an open quest's own pages. */
const NO_QUEST = {
  questSaves: null, questDuels: null, questDecks: null, questChallenges: null, questBazaar: null, questTournaments: null,
} satisfies Partial<Model>;

/** The messages that are only stored in the campaign field of the same name. */
const STORED = new Set(['conquestSaves', 'questSaves', 'questDuels', 'questDecks', 'questOptions', 'questChallenges', 'questBazaar', 'questTournaments',
  'campaignBar', 'conquestState', 'campaignResult', 'reward', 'conquestParty', 'trading', 'conquestOptions', 'conquestAether', 'conquestPlanes',
  'campaignStats', 'campaignPrefs']);

// Conquest's icon is a ringed planet. Quest's icon is a pennant on a staff
const CONQUEST_ICON = <><circle cx="24" cy="24" r="11" /><ellipse cx="24" cy="24" rx="20" ry="6.5" transform="rotate(-18 24 24)" /></>;
const QUEST_ICON = <><path d="M13 42V7" /><path d="M13 9h22l-6 7 6 7H13" /></>;

Object.assign(ui, { conquestTab: 'map', questTab: 'duels', conquestOpened: {}, conquestWheel: '' });

registerCampaigns({
  fields: () => ({ conquestSaves: null, questOptions: null, campaignResult: null, conquestOptions: null, campaignPrefs: null, ...NO_CAMPAIGN, ...NO_CONQUEST, ...NO_QUEST }),

  hello: (model, msg) => {
    // A match's result is cleared when its match ends. A campaign's pages are cleared when the campaign is left
    if (msg.screen !== 'playing') model.campaignResult = null;
    if (!msg.campaign) Object.assign(model, NO_CAMPAIGN);
    if (msg.campaign !== 'conquest') {
      Object.assign(model, NO_CONQUEST);
      ui.conquestTab = 'map';
      ui.conquestWheel = '';
    }
    if (msg.campaign !== 'quest') {
      Object.assign(model, NO_QUEST);
      // A match is played from the quest's page, so the player returns to the tab the match was started from
      if (msg.screen !== 'playing') ui.questTab = 'duels';
    }
  },

  message: (model, msg) => {
    if (!STORED.has(msg.t)) return false;
    (model as unknown as Record<string, unknown>)[msg.t] = msg;
    return true;
  },

  actions: (send, model, schedule) => ({
    campaignOpen: (mode, resume) => send({ t: 'campaignOpen', mode, resume }),
    // The server forgets a queued Chaos Wheel result when a save opens, and once a won battle has used it
    campaignLoad: name => {
      ui.conquestWheel = '';
      send({ t: 'campaignLoad', name });
    },
    campaignLeave: () => send({ t: 'campaignLeave' }),
    // The error from the last refused request no longer applies, so it is cleared
    campaignRename: (name, to) => {
      model.error = null;
      send({ t: 'campaignRename', name, to });
    },
    campaignDelete: name => {
      model.error = null;
      send({ t: 'campaignDelete', name });
    },
    conquestSelect: (region, row, col) => send({ t: 'conquestSelect', region, row, col }),
    conquestMove: () => send({ t: 'conquestMove' }),
    conquestBattle: () => send({ t: 'conquestBattle' }),
    // A shown reward is removed from the model at once, so its dialog closes without waiting for the server
    rewardClaim: () => {
      model.reward = null;
      ui.conquestWheel = '';
      send({ t: 'rewardClaim' });
      schedule();
    },
    devConquestWheel: outcome => send({ t: 'devConquestWheel', outcome }),
    questDuel: index => send({ t: 'questDuel', index }),
    questPet: (slot, name) => send({ t: 'questPet', slot, name: name ?? undefined }),
    questMatchLength: games => send({ t: 'questMatchLength', games }),
    questDeckNew: name => send({ t: 'questDeckNew', name }),
    questDeckRename: (deck, to) => send({ t: 'questDeckRename', deck, to }),
    questDeckCurrent: deck => send({ t: 'questDeckCurrent', deck }),
    questDeckView: deck => send({ t: 'questDeckView', deck }),
    questDeckEdit: deck => send({ t: 'questDeckEdit', deck }),
    questDeckDelete: deck => send({ t: 'questDeckDelete', deck }),
    questOptions: () => send({ t: 'questOptions' }),
    questChallenge: id => send({ t: 'questChallenge', id }),
    questZeppelin: () => send({ t: 'questZeppelin' }),
    questStall: name => send({ t: 'questStall', name: name ?? undefined }),
    questBuy: (stall, item) => send({ t: 'questBuy', stall, item }),
    questEnter: title => send({ t: 'questEnter', title }),
    questToken: () => send({ t: 'questToken' }),
    questTournamentStart: () => send({ t: 'questTournamentStart' }),
    questTournamentNext: () => send({ t: 'questTournamentNext' }),
    questTournamentLeave: () => send({ t: 'questTournamentLeave' }),
    questTournamentDeck: () => send({ t: 'questTournamentDeck' }),
    questTravel: () => send({ t: 'questTravel' }),
    questUnlock: () => send({ t: 'questUnlock' }),
    questCreate: c => {
      model.error = null;
      send({ t: 'questCreate', ...c });
    },
    conquestOptions: (plane, commander) => send({ t: 'conquestOptions', plane, commander }),
    conquestCreate: c => {
      model.error = null;
      send({ t: 'conquestCreate', ...c });
    },
    // A new visit to the Aether starts from its default filters. The card pulled on the last visit is cleared too
    conquestAether: q => {
      if (!q.type) model.conquestAether = null;
      send({ t: 'conquestAether', ...q });
    },
    conquestPlanes: () => send({ t: 'conquestPlanes' }),
    conquestPlaneswalk: (plane, unlock) => {
      model.error = null;
      send({ t: 'conquestPlaneswalk', plane, unlock });
    },
    campaignStats: scope => send({ t: 'campaignStats', scope }),
    campaignPrefs: () => send({ t: 'campaignPrefs' }),
    campaignPref: (key, value) => send({ t: 'campaignPref', key, value }),
    campaignPrefsReset: () => send({ t: 'campaignPrefsReset' }),
    conquestParty: () => send({ t: 'conquestParty' }),
    conquestLead: commander => send({ t: 'conquestLead', commander }),
    conquestWalker: planeswalker => send({ t: 'conquestWalker', planeswalker }),
    conquestViewDeck: commander => send({ t: 'conquestViewDeck', commander }),
    conquestEditDeck: commander => send({ t: 'conquestEditDeck', commander }),
    trading: () => send({ t: 'trading' }),
    trade: (source, picks) => send({ t: 'trade', source, picks }),
  }),

  Page: ({ model, actions }) => (model.campaign === 'quest' ? <Quest model={model} actions={actions} /> : <Conquest model={model} actions={actions} />),

  Menu: ({ model, actions }) => {
    const resume = (mode: CampaignMode) => model.campaignResume[mode];
    return (
      <>
        <h3 class="chooser-more">{t('lblWebConquestCampaigns')}</h3>
        <div class="chooser-kinds">
          <Kind id="conquest" icon={CONQUEST_ICON} name={t('lblPlanarConquest')} blurb={t('lblWebConquestBlurb')} onClick={() => actions.campaignOpen('conquest', false)}
            resume={resume('conquest') ? () => actions.campaignOpen('conquest', true) : undefined}
            resumeLabel={resume('conquest') ? t('lblWebConquestResume', resume('conquest')) : undefined} />
          <Kind id="quest" icon={QUEST_ICON} name={t('lblQuestMode')} blurb={t('lblWebQuestBlurb')}
            onClick={() => actions.campaignOpen('quest', false)}
            resume={resume('quest') ? () => actions.campaignOpen('quest', true) : undefined}
            resumeLabel={resume('quest') ? t('lblWebConquestResume', resume('quest')) : undefined} />
        </div>
      </>
    );
  },

  draft: model => (model.campaign === 'quest' ? { Done: TournamentDrafted, leaveText: 'lblLeaveDraftConfirm' } : null),

  ending: (model, actions: Actions) => {
    if (!model.campaignSave) return null;
    // The campaign's result sets which buttons the ending offers, so the panel waits for it
    const result = model.campaignResult;
    if (!result) return { key: null, line: null, buttons: () => {} };
    return {
      key: `${result.won}:${result.matchOver}`,
      line: result.line ?? null,
      buttons: panel => {
        const quit = () => { actions.quitMatch(); actions.leave(); };
        const draw = () => {
          panel.clear();
          const does: Record<string, () => void> = {
            nextGame: () => actions.nextGame(), leave: () => actions.leave(), restart: () => actions.restartGame(), quit,
            // A forfeit loses the entry fee, so the player must confirm it first, as on desktop
            forfeit: () => {
              panel.clear();
              panel.say(t('lblWebQuestForfeitAsk'));
              panel.add(t('lblCancel'), true, draw);
              panel.add(t('lblWebQuestForfeitTournament'), false, quit);
            },
          };
          result.buttons.forEach((b, i) => {
            panel.add(b.label, b.primary, does[b.action]);
            if (i === 0) panel.add(t('lblReconnectViewBattlefield'), false, panel.view);
          });
        };
        draw();
      },
    };
  },
});
