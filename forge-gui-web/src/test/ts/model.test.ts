import { describe, expect, it } from 'vitest';
import { applyState, concedingEndsGame, createModel } from '../../main/ts/model';
import type { StateMessage } from '../../main/ts/protocol';

const state = (patch: Partial<StateMessage>): StateMessage => ({
  t: 'state', full: false, seq: 1, root: 1, newObjects: {}, deltas: {}, visible: [], localPlayers: [], events: [], ...patch,
});

describe('applying state', () => {
  it('drops a property a delta sets to null, which is the property going back to its default', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Turn: 3, Players: [{ ref: 2 }] }, 2: { Tapped: true } } }));
    applyState(model, state({ deltas: { 2: { Tapped: null } } }));
    expect(model.objects.get(2)).toEqual({ $key: 2 });
  });

  it('treats a null in a new object as the property being left out', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Turn: null } as never } }));
    expect(model.objects.get(1)).toEqual({ $key: 1 });
  });

  it('forgets an object nothing reaches any more', () => {
    const model = createModel();
    applyState(model, state({ full: true, newObjects: { 1: { Players: [{ ref: 2 }] }, 2: {} } }));
    applyState(model, state({ deltas: { 1: { Players: [] } } }));
    expect([...model.objects.keys()]).toEqual([1]);
  });

  it('keeps what happened for the next frame, and forgets it when a whole new table arrives', () => {
    const model = createModel();
    const moved = { kind: 'cardMoved' as const, card: { ref: 5 } };
    applyState(model, state({ full: true, newObjects: { 1: {} }, events: [moved] }));
    applyState(model, state({ events: [moved] }));
    expect(model.events).toHaveLength(2);
    applyState(model, state({ full: true, newObjects: { 1: {} } }));
    expect(model.events).toEqual([]);
  });
});

describe('conceding', () => {
  // Players 2 to 5, with each player's opponents and whether they have lost. Player 2 is the local player
  const table = (players: Record<number, { foes: number[]; lost?: boolean }>) => {
    const model = createModel();
    const objects: Record<number, object> = { 1: { Players: Object.keys(players).map(k => ({ ref: Number(k) })) } };
    for (const [k, p] of Object.entries(players)) objects[Number(k)] = { Opponents: p.foes.map(ref => ({ ref })), ...(p.lost ? { HasLost: true } : {}) };
    applyState(model, state({ full: true, newObjects: objects as never, localPlayers: [2] }));
    return model;
  };

  // Fails if a two-player concession is not seen as the end of the game
  it('ends a game of two', () => {
    expect(concedingEndsGame(table({ 2: { foes: [3] }, 3: { foes: [2] } }))).toBe(true);
  });

  // Fails if a free-for-all is seen as over while two other players are still in it
  it('does not end a free-for-all with two others left', () => {
    expect(concedingEndsGame(table({ 2: { foes: [3, 4] }, 3: { foes: [2, 4] }, 4: { foes: [2, 3] } }))).toBe(false);
  });

  // Fails if a player who has lost still counts as being in the game
  it('ends a free-for-all once only one other is left', () => {
    expect(concedingEndsGame(table({ 2: { foes: [3, 4] }, 3: { foes: [2, 4] }, 4: { foes: [2, 3], lost: true } }))).toBe(true);
  });

  // Fails if a teammate still in the game is ignored
  it('does not end a team game while a teammate plays on', () => {
    expect(concedingEndsGame(table({ 2: { foes: [4, 5] }, 3: { foes: [4, 5] }, 4: { foes: [2, 3] }, 5: { foes: [2, 3] } }))).toBe(false);
  });

  // Fails if two opponents on the same team are taken for a free-for-all
  it('ends a team game when only the other team is left', () => {
    expect(concedingEndsGame(table({ 2: { foes: [4, 5] }, 3: { foes: [4, 5], lost: true }, 4: { foes: [2, 3] }, 5: { foes: [2, 3] } }))).toBe(true);
  });
});
