// The positions of a plane's regions and places on the Conquest map. Sizes are in pixels

import type { ConquestState } from '../protocol';

export const TW = 100, TH = 150, G = 14, BH = 92, GAP = 32, SPINE = 62, X0 = 4, Y0 = 8;
interface Layout {
  width: number;
  height: number;
  regions: { x: number; w: number; folded: boolean }[];
  at(region: number, row: number, col: number): { x: number; y: number };
}

/** A region's steps run left to right along the road and its lanes are stacked top to bottom. So a row gives x and a column gives y. */
export function layout(state: Pick<ConquestState, 'rows' | 'cols' | 'regions'>, folded: (region: number) => boolean): Layout {
  const w = state.rows * TW + (state.rows - 1) * G;
  const h = state.cols * TH + (state.cols - 1) * G;
  const top = Y0 + BH + GAP;
  const regions: Layout['regions'] = [];
  let x = X0;
  state.regions.forEach((_, r) => {
    const f = folded(r);
    regions.push({ x, w: f ? SPINE : w, folded: f });
    const last = r === state.regions.length - 1;
    x += (f ? SPINE : w) + (last ? 0 : f && folded(r + 1) ? 10 : f || folded(r + 1) ? 30 : 46);
  });
  return {
    width: x + X0, height: top + h + 20, regions,
    at: (r, row, col) => ({ x: regions[r].x + row * (TW + G), y: top + col * (TH + G) }),
  };
}

/** A region still all in fog, or a finished one, is folded by default. The player's choice in opened overrides that. A region in keepOpen is never folded. */
export function isFolded(state: Pick<ConquestState, 'regions' | 'cells'>, region: number, opened: Readonly<Record<number, boolean>>,
    keepOpen: ReadonlySet<number>): boolean {
  if (region >= state.regions.length || keepOpen.has(region)) return false;
  if (region in opened) return !opened[region];
  const r = state.regions[region];
  return r.conquered === r.total || state.cells.filter(c => c.region === region).every(c => c.state === 'fog');
}
