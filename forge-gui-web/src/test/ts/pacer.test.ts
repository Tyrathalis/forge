import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../../main/ts/match/turnbanner', () => ({ announceComing: () => false }));
vi.mock('../../main/ts/settings', () => ({ setting: () => 1000 }));

import { createPacer } from '../../main/ts/match/pacer';
import { boardBehind } from '../../main/ts/match/pace';
import type { Model } from '../../main/ts/model';
import type { Prompt, ServerMessage } from '../../main/ts/protocol';

const ME = 1;
const THEM = 2;
// A state message carrying the given events. The pacer reads only these fields of it
const state = (n: number, events: unknown[] = []) => ({ t: 'state', full: false, n, events, localPlayers: [ME], deltas: {} }) as unknown as ServerMessage;
const hit = { kind: 'playerDamaged', player: { ref: THEM } };
const sound = { t: 'sound', name: 'damage' } as unknown as ServerMessage;
const prompt = (over: Partial<Prompt>): ServerMessage => ({ t: 'prompt', message: '', priority: false, ok: { label: 'OK', enabled: false },
  cancel: { label: 'Cancel', enabled: false }, focusOk: false, paying: false, endsTurn: false, selectable: [], selectablePlayers: [],
  selectableStack: [], selectableMin: 0, highlighted: [], ...over }) as ServerMessage;

describe('createPacer', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  const run = () => {
    const got: string[] = [];
    const pacer = createPacer({} as Model, m => got.push(m.t + ((m as { n?: number }).n ?? '')));
    return { got, pacer };
  };

  it('holds the next board while a hit is on screen, and shows it when the pause ends', () => {
    const { got, pacer } = run();
    pacer.receive(state(1, [hit]));
    pacer.receive(state(2));
    expect(got).toEqual(['state1']);
    expect(pacer.holding()).toBe(true);
    vi.advanceTimersByTime(1000);
    expect(got).toEqual(['state1', 'state2']);
    expect(pacer.holding()).toBe(false);
  });

  it('skip shows every held board at once, without a new pause for the hits among them, and drops their sounds', () => {
    const { got, pacer } = run();
    pacer.receive(state(1, [hit]));
    pacer.receive(state(2, [hit]));
    pacer.receive(sound);
    pacer.receive(state(3, [hit]));
    pacer.skip();
    expect(got).toEqual(['state1', 'state2', 'state3']);
    expect(pacer.holding()).toBe(false);
    expect(boardBehind()).toBe(false);
    // The pause the last hit would have started was skipped too, so the next board shows at once
    pacer.receive(state(4));
    expect(got).toEqual(['state1', 'state2', 'state3', 'state4']);
  });

  it('starts pausing again after a skip', () => {
    const { got, pacer } = run();
    pacer.receive(state(1, [hit]));
    pacer.receive(state(2));
    pacer.skip();
    pacer.receive(state(3, [hit]));
    pacer.receive(state(4));
    expect(got).toEqual(['state1', 'state2', 'state3']);
    expect(pacer.holding()).toBe(true);
  });

  it('shows a question for the player at once, with the board update held in front of it and its sound', () => {
    const { got, pacer } = run();
    pacer.receive(state(1, [{ kind: 'stackAdded', player: { ref: THEM } }]));
    pacer.receive(state(2));
    pacer.receive(sound);
    expect(got).toEqual(['state1']);
    pacer.receive(prompt({ priority: true, ok: { label: 'OK', enabled: true } }));
    expect(got).toEqual(['state1', 'state2', 'sound', 'prompt']);
    expect(pacer.holding()).toBe(false);
  });

  it('keeps holding for a prompt that asks nothing, such as waiting for another player', () => {
    const { got, pacer } = run();
    pacer.receive(state(1, [hit]));
    pacer.receive(state(2));
    pacer.receive(prompt({ message: 'Waiting for the opponent' }));
    expect(got).toEqual(['state1']);
    expect(pacer.holding()).toBe(true);
  });
});
