// The server's balances already include the whole reward. So the bar subtracts whatever has not been revealed yet

import type { RewardStep } from '../protocol';

/** The amounts paid by every step from index from onward, by balance icon. A pack pays through its cards, one by one. */
export function pendingAmounts(steps: RewardStep[], from: number): Record<string, number> {
  const owed: Record<string, number> = {};
  const add = (icon: string | undefined, n: number) => { if (icon && n) owed[icon] = (owed[icon] ?? 0) + n; };
  for (const step of steps.slice(from)) {
    // A step with no icon pays nothing itself. Conquest's closing total repeats what the earlier packs paid
    if (step.cards) for (const card of step.cards) add(step.icon, card.shards);
    else add(step.icon, step.amount);
  }
  return owed;
}
