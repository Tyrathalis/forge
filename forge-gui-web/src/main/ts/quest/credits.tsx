import { skinIconUrl } from '../images';
import { formatNumber, t } from '../text';

export const CREDITS = 'ICO_QUEST_COINSTACK';

export function Price({ n }: { n: number }) {
  return <span class="qu-price"><img alt={t('lblCredits')} src={skinIconUrl(CREDITS)} />{formatNumber(n)}</span>;
}
