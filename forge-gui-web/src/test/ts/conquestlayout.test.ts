import { expect, test } from 'vitest';
import { isFolded, layout } from '../../main/ts/conquest/conquestlayout';
import type { ConquestCell } from '../../main/ts/protocol';

const region = (conquered = 0) => ({ name: '', art: '', colors: '', conquered, total: 9 });
const plane = (regions: number) => ({ rows: 3, cols: 3, regions: Array.from({ length: regions }, () => region()) });
const W = 3 * 100 + 2 * 14;

// Fails if the gap between regions is not 46 when both are open, 30 when one is folded, and 10 when both are folded
test('regions stand apart by what they are', () => {
  const l = layout(plane(4), r => r >= 2);
  expect(l.regions.map(r => r.x)).toEqual([4, 4 + W + 46, 4 + 2 * W + 46 + 30, 4 + 2 * W + 46 + 30 + 62 + 10]);
  expect(l.regions.map(r => r.folded)).toEqual([false, false, true, true]);
  // A folded finished region before the region the player is in
  expect(layout(plane(2), r => r === 0).regions[1].x).toBe(4 + 62 + 30);
});

// Fails if an event is not placed at its step along the road and at its lane across it
test('a place stands at its step and lane', () => {
  const l = layout(plane(2), () => false);
  expect(l.at(1, 2, 1)).toEqual({ x: 4 + W + 46 + 2 * 114, y: 8 + 92 + 32 + 164 });
});

// Fails if a region of six events in two steps is laid out as wide as a region of nine
test('a region of two steps is narrower', () => {
  const l = layout({ ...plane(2), rows: 2 }, () => false);
  expect(l.regions[1].x).toBe(4 + 2 * 100 + 14 + 46);
});

const cells = (states: string[]): ConquestCell[] => states.map((state, region) => ({ region, row: 0, col: 0, state, wins: 0, losses: 0 }));
const world = { regions: [region(9), region(2), region()], cells: cells(['won', 'open', 'fog']) };

// Fails if a finished or unreached region is open by default, or a region in play is folded
test('finished and unreached regions fold', () => {
  expect([0, 1, 2].map(r => isFolded(world, r, {}, new Set()))).toEqual([true, false, true]);
});

// Fails if the player's own choice to open or close a region is not kept
test('a region opened or closed by hand stays so', () => {
  expect(isFolded(world, 0, { 0: true }, new Set())).toBe(false);
  expect(isFolded(world, 1, { 1: false }, new Set())).toBe(true);
});

// Fails if a region the marker is in or moves through is folded. A folded region would draw its events over the next region's
test('a region the marker is in or passes through is open', () => {
  expect(isFolded(world, 0, {}, new Set([0]))).toBe(false);
  expect(isFolded(world, 0, { 0: false }, new Set([0]))).toBe(false);
});
