import { useState } from 'preact/hooks';
import { SidePanel } from '../campaign/campaign';
import { Brings, Difficulty, WorldButtons } from './questduels';
import { artUrl } from '../sleeves';
import type { Actions } from '../actions';
import type { QuestChallengeRow, QuestChallenges, QuestDuels } from '../protocol';
import { t } from '../text';
import { Price } from './credits';

function Tile({ challenge, chosen, choose }: { challenge: QuestChallengeRow; chosen: boolean; choose: () => void }) {
  return (
    <button class="qu-ev" aria-pressed={chosen} onClick={choose}>
      <span class="a" style={{ backgroundImage: challenge.face ? `url("${artUrl(challenge.face)}")` : undefined }} />
      <span class="t">
        <b>{challenge.title}</b>
        <span class="l">
          {challenge.difficulty > 0 && <Difficulty n={challenge.difficulty} />}
          {challenge.repeatable && <span class="cq-chip">{t('lblWebQuestRepeatable')}</span>}
        </span>
        <p>{challenge.description}</p>
        <span class="f"><Price n={challenge.credits} />{challenge.cardReward.length > 0 && <span>{t('lblWebNCards', challenge.cardReward.length)}</span>}</span>
      </span>
    </button>
  );
}

/** Names of cards a side starts with in play, as chips. */
function Starting({ title, cards }: { title: string; cards: string[] }) {
  if (!cards.length) return null;
  return (
    <div class="qu-extras">
      <span class="cq-kicker">{title}</span>
      <div class="cq-chips">{cards.map((c, i) => <span key={i} class="cq-chip">{c}</span>)}</div>
    </div>
  );
}

export function Challenges({ page, duels, actions }: { page: QuestChallenges; duels: QuestDuels; actions: Actions }) {
  const [chosen, setChosen] = useState<string | null>(null);
  const [zoom, setZoom] = useState<string | null>(null);
  const challenge = page.challenges.find(c => c.id === chosen) ?? page.challenges[0];
  return (
    <div class="cq-map">
      <div class="qu-list">
        <div class="qu-list-head">
          <h2>{t('lblChallenges')}</h2><p>{t('lblWhichChallenge')}</p>
          <span class="sp" />
          {page.zeppelin && <button disabled={page.zeppelinUsed} onClick={() => actions.questZeppelin()}>{t('cbLaunchZeppelin')}</button>}
          <WorldButtons page={duels} actions={actions} />
        </div>
        <div class="qu-status">
          <span class="cq-chip">{t('lblWebQuestOpenOf', page.open, page.max)}</span>
          {page.nextIn && <span>{page.nextIn}</span>}
        </div>
        <div class="qu-events">
          {page.challenges.map(c => <Tile key={c.id} challenge={c} chosen={c === challenge} choose={() => setChosen(c.id)} />)}
        </div>
      </div>
      {challenge && (
        <SidePanel kicker={t('lblWebQuestChallenge')} title={challenge.title} art={challenge.face} label={challenge.title} zoom={zoom} setZoom={setZoom}
          foot={<Brings page={duels} actions={actions} zoom={setZoom} start={() => actions.questChallenge(challenge.id)} label={t('lblWebQuestStartChallenge')}
            fixedDeck={challenge.fixedDeck} />}>
          {challenge.difficulty > 0 && <div class="cq-chips"><Difficulty n={challenge.difficulty} /></div>}
          <p class="cq-desc">{challenge.description}</p>
          <dl class="qu-facts">
            <dt>{t('lblWebQuestOpponentLife')}</dt><dd>{challenge.aiLife}</dd>
            {challenge.humanLife != null && <><dt>{t('lblWebQuestYourLife')}</dt><dd>{challenge.humanLife}</dd></>}
            <dt>{t('lblWebQuestBounty')}</dt><dd><Price n={challenge.credits} /></dd>
          </dl>
          <Starting title={t('lblWebQuestYouStartWith')} cards={challenge.humanCards} />
          <Starting title={t('lblWebQuestOpponentStartsWith')} cards={challenge.aiCards} />
        </SidePanel>
      )}
    </div>
  );
}
