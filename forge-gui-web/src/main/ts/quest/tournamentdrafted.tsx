import { Backdrop } from '../dialog';
import { t } from '../text';
import type { Actions } from '../actions';
import type { DraftState } from '../protocol';

/** The end of a quest tournament's draft. Quest names the picks itself, and the player builds them into the tournament deck next. */
export function TournamentDrafted({ state, actions }: { state: DraftState; actions: Actions }) {
  return (
    <Backdrop>
      <div class="dialog draft-save">
        <h3>{t('lblWebDraftComplete')}</h3>
        <p class="hint">{t('lblWebDraftCardsDrafted', state.picks.length)}</p>
        <p class="hint">{t('lblWebQuestDraftBuild')}</p>
        <div class="actions">
          <button class="primary" onClick={() => actions.draftSave('', false)}>{t('btnContinue')}</button>
        </div>
      </div>
    </Backdrop>
  );
}
