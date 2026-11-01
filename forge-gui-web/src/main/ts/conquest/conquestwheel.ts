// The Chaos Wheel, drawn as a dial and spun by hand. The server decides where it stops

import { skinIconUrl } from '../images';
import { reducedMotion } from '../dom';

/** The wheel's eight spots, clockwise from the needle, in the order of wheelSpots in ConquestEvent.ChaosWheelOutcome. */
const WHEEL_SPOTS = ['CHAOS', 'BOOSTER', 'SHARDS', 'DOUBLE_BOOSTER', 'PLANESWALK', 'BOOSTER', 'DOUBLE_SHARDS', 'BOOSTER'] as const;
export type WheelOutcome = typeof WHEEL_SPOTS[number];

const TONES: Record<WheelOutcome, string> = {
  CHAOS: '#2a1420', BOOSTER: '#121827', SHARDS: '#10232a', DOUBLE_BOOSTER: '#1c2744', PLANESWALK: '#232833', DOUBLE_SHARDS: '#163843',
};
const LINES = {
  chaos: '<svg class="ln" viewBox="0 0 40 40"><path d="M20 20c0-9 12-9 12 0s-12 9-12 18"/><path d="M20 20c0 9-12 9-12 0s12-9 12-18"/></svg>',
  pack: '<svg class="ln" viewBox="0 0 24 34"><path d="M4 2h16v30H4z"/><path d="M4 6.5h16M4 27.5h16"/><path d="M12 12.5l4 4.5-4 4.5-4-4.5z"/></svg>',
  shard: '<svg class="ln" viewBox="0 0 24 34"><path d="M12 2l7 10-7 20-7-20z"/><path d="M5 12h14M12 2l-3 10 3 20 3-20z"/></svg>',
};
const emblem = `<img alt="" src="${skinIconUrl('IMG_PW_BADGE_COMMON')}">`;
const ICONS: Record<WheelOutcome, string> = {
  CHAOS: LINES.chaos, BOOSTER: LINES.pack, SHARDS: LINES.shard, DOUBLE_BOOSTER: LINES.pack + LINES.pack, PLANESWALK: emblem, DOUBLE_SHARDS: LINES.shard + LINES.shard,
};
/** The chaos symbol, shown on the dial's hub and on the chaos pack. */
export const CHAOS_MARK = LINES.chaos;

const SPIN_MS = 4200;
/** How much of a wedge's 45 degrees its label may take, so labels in neighbouring wedges never meet. */
const LABEL_ARC = 38 * Math.PI / 180;

/** The dial's angle at x, from 0 to 1 through the spin. It pulls back a little, runs and slows, carries a few degrees past the spot and settles back, so it feels heavy. */
function turned(x: number, to: number): number {
  const WIND = .07, PULL = 7, PAST = 4, SETTLE = .1;
  if (x < WIND) return PULL * Math.sin(x / WIND * Math.PI / 2);
  const k = (x - WIND) / (1 - WIND);
  if (k < 1 - SETTLE) return PULL + (to - PAST - PULL) * (1 - Math.pow(1 - k / (1 - SETTLE), 2.5));
  const back = (k - (1 - SETTLE)) / SETTLE;
  return to - PAST + PAST * (1 - Math.pow(1 - back, 2));
}

/** A spot that holds the outcome. Three spots hold a booster, so one of them is picked at random. */
export function spotFor(outcome: string): number {
  const spots = WHEEL_SPOTS.map((o, i) => (o === outcome ? i : -1)).filter(i => i >= 0);
  return spots.length ? spots[Math.floor(Math.random() * spots.length)] : 0;
}

export interface Wheel {
  /** Turns the dial until the spot is under the needle. */
  spin(spot: number): Promise<void>;
  /** Shows the dial already stopped on the spot. */
  show(spot: number): void;
}

/** Draws the dial into host. labels names each outcome, in the player's language. */
export function mountWheel(host: HTMLElement, labels: Record<WheelOutcome, string>): Wheel {
  const stops = WHEEL_SPOTS.map((o, i) => `${TONES[o]} ${i * 45}deg ${(i + 1) * 45}deg`).join(', ');
  host.innerHTML = `<div class="cq-dl">
    <div class="cq-dl-glow"></div><div class="cq-dl-rim"></div>
    <div class="cq-dl-pegs">${WHEEL_SPOTS.map((_, i) => `<i style="rotate: ${45 * i + 22.5}deg"></i>`).join('')}</div>
    <div class="cq-dl-spin"><div class="cq-dl-disc" style="background: conic-gradient(from -22.5deg, ${stops})"></div>${
      WHEEL_SPOTS.map((o, i) => `<div class="cq-dl-wedge" style="transform: rotate(${45 * i}deg)"><div class="cq-dl-face"><div class="cq-dl-ic">${ICONS[o]}</div></div><div class="cq-dl-word"></div></div>`).join('')
    }</div>
    <div class="cq-dl-flare"></div><div class="cq-dl-hub">${LINES.chaos}</div><div class="cq-dl-wave"></div><div class="cq-dl-needle"></div>
  </div>`;
  const root = host.firstElementChild as HTMLElement;
  const spin = root.querySelector<HTMLElement>('.cq-dl-spin')!;
  const needle = root.querySelector<HTMLElement>('.cq-dl-needle')!;
  // The labels are laid out again when the dial changes size, because they are placed in pixels
  const curve = () => root.querySelectorAll<HTMLElement>('.cq-dl-wedge').forEach((wedge, i) => curveLabel(wedge, labels[WHEEL_SPOTS[i]]));
  // The observer also lays the labels out once when it starts
  new ResizeObserver(curve).observe(root);
  const show = (spot: number) => {
    spin.style.transform = `rotate(${-45 * spot}deg)`;
    root.querySelectorAll('.cq-dl-wedge').forEach((wedge, i) => wedge.classList.toggle('hit', i === spot));
    root.classList.add('done');
  };
  return {
    show,
    async spin(spot) {
      if (reducedMotion()) {
        show(spot);
        return;
      }
      root.classList.remove('done');
      root.classList.add('spinning');
      const to = -45 * spot - 360 * 4;
      const start = performance.now();
      let peg = 0;
      await new Promise<void>(stopped => {
        const frame = (now: number) => {
          const x = Math.min(1, (now - start) / SPIN_MS);
          const angle = x < 1 ? turned(x, to) : to;
          spin.style.transform = `rotate(${angle}deg)`;
          // The needle drags on each peg that passes under it
          const at = Math.floor((angle + 22.5) / 45);
          if (at !== peg) {
            peg = at;
            needle.classList.remove('drag');
            void needle.offsetWidth;
            needle.classList.add('drag');
          }
          if (x < 1) requestAnimationFrame(frame);
          else stopped();
        };
        requestAnimationFrame(frame);
      });
      root.classList.remove('spinning');
      show(spot);
    },
  };
}

let measurer: CanvasRenderingContext2D | null = null;

/**
 * Lays a label's letters along an arc just inside the wedge's icon, reading clockwise. A label too long for its wedge breaks between
 * words onto a second arc further in, and a word that still does not fit is drawn smaller.
 */
function curveLabel(wedge: HTMLElement, text: string): void {
  const word = wedge.querySelector<HTMLElement>('.cq-dl-word')!;
  const face = wedge.querySelector<HTMLElement>('.cq-dl-face')!;
  const across = wedge.offsetWidth, centre = wedge.offsetHeight;
  if (!centre) return;
  const style = getComputedStyle(wedge);
  const size = parseFloat(style.fontSize);
  const spacing = parseFloat(style.letterSpacing) || 0;
  const line = size * 1.15;
  measurer ??= document.createElement('canvas').getContext('2d');
  const ctx = measurer!;
  ctx.font = `${style.fontWeight} ${size}px ${style.fontFamily}`;
  const widthOf = (ch: string) => ctx.measureText(ch).width + spacing;
  const lengthOf = (s: string) => [...s].reduce((n, ch) => n + widthOf(ch), 0) - spacing;
  // The first line's middle sits a little inside the icon
  const outer = centre - (face.offsetTop + face.offsetHeight) - size * .35 - line / 2;
  const room = outer * LABEL_ARC;
  const lines: string[] = [];
  for (const w of text.toUpperCase().split(/\s+/).filter(Boolean)) {
    const last = lines.length - 1;
    if (last >= 0 && lengthOf(`${lines[last]} ${w}`) <= room) lines[last] += ` ${w}`;
    else lines.push(w);
  }
  const scale = Math.min(1, room / Math.max(...lines.map(lengthOf)));
  const letters: HTMLElement[] = [];
  lines.forEach((l, k) => {
    const r = outer - k * line * scale;
    const total = lengthOf(l) * scale;
    const h = line * scale;
    let along = 0;
    for (const ch of l) {
      const w = widthOf(ch) * scale;
      const span = document.createElement('span');
      span.textContent = ch;
      span.style.cssText = `left: ${across / 2 - w / 2}px; top: ${centre - r - h / 2}px; width: ${w}px; height: ${h}px; font-size: ${size * scale}px;`
        + ` transform-origin: ${w / 2}px ${r + h / 2}px; transform: rotate(${(along + (w - spacing * scale) / 2 - total / 2) / r}rad);`;
      along += w;
      letters.push(span);
    }
  });
  word.replaceChildren(...letters);
}
