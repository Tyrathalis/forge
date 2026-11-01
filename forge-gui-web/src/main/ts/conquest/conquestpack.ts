// A booster pack above its cards, animated in code with the Web Animations API. The server decides which cards it holds

import { skinIconUrl, imageUrl } from '../images';
import { artUrl } from '../sleeves';
import { CHAOS_MARK } from './conquestwheel';
import type { PackCard } from '../protocol';
import { reducedMotion } from '../dom';

interface PackSpec {
  name: string;
  /** The line under the name. It gives the kind of pack, or for a chaos pack the world its cards come from. */
  sub: string;
  /** The art's image key, or null for a chaos pack, which shows the chaos symbol instead. */
  art: string | null;
  chaos: boolean;
  cards: PackCard[];
  hint: string;
}

interface PackHooks {
  /** A card has turned face up at this position on the screen. */
  flipped(card: PackCard, at: DOMRect): void;
  /** The pack has been clicked and is opening. */
  opening(): void;
  /** Every card is face up. */
  shown(): void;
}

export interface Pack {
  /** Opens the pack. The promise resolves when every card is face up. */
  open(): Promise<void>;
  /** Shows every card face up at once, and ends any animation in progress. */
  finish(): void;
}

/** The pack's width. The peel calculation and the stylesheet both use it. */
const PACK_W = 196;
const SLICES = 16;
/** The seal's height, as a percentage of the pack's height. */
const SEAL = 9;
const SOFT = 'cubic-bezier(.22,.8,.26,1)';
/** The delay before a card of each rarity flips, counted from the flip of the card before it. */
const GAP: Record<string, number> = { Common: 70, BasicLand: 70, Uncommon: 200, Rare: 500, Special: 500, MythicRare: 900 };
export const isRare = (rarity: string): boolean => rarity === 'Rare' || rarity === 'Special' || rarity === 'MythicRare';

// A crimped edge as polygon points: n teeth between two heights
const teeth = (n: number, y0: string, y1: string): string[] => Array.from({ length: n + 1 }, (_, k) => `${(100 * k / n).toFixed(1)}% ${k % 2 ? y0 : y1}`);
const TOP = `polygon(${teeth(40, '0%', '34%').join(', ')}, 100% 100%, 0 100%)`;
const BODY = `polygon(0 0, 100% 0, ${teeth(40, '100%', '96.6%').reverse().join(', ')})`;
// The outline of the whole pack, used as the shape of its glow
const OUTLINE = `polygon(${teeth(40, '0%', `${(SEAL * .34).toFixed(2)}%`).join(', ')}, ${teeth(40, '100%', `${(SEAL + (100 - SEAL) * .966).toFixed(2)}%`).reverse().join(', ')})`;

function el(spec: string, style: Partial<CSSStyleDeclaration> = {}, ...kids: (Node | string | null)[]): HTMLElement {
  const [tag, ...cls] = spec.split('.');
  const e = document.createElement(tag || 'div');
  if (cls.length) e.className = cls.join(' ');
  Object.assign(e.style, style);
  e.append(...kids.filter((k): k is Node | string => k !== null));
  return e;
}

export function mountPack(host: HTMLElement, spec: PackSpec, hooks: PackHooks): Pack {
  const cards = spec.cards;
  const up = new Set<number>();
  const slots = cards.map((c, i) => {
    const face = el('div.cq-rv-face');
    const img = document.createElement('img');
    img.alt = c.name;
    img.src = imageUrl(c.image);
    face.append(img);
    const plus = c.shards ? el('span.cq-rv-plus', {}, '+') : null;
    if (plus) {
      const shard = document.createElement('img');
      shard.alt = '';
      shard.src = skinIconUrl('IMG_AETHER_SHARD');
      plus.append(shard, String(c.shards));
    }
    const cls = ['cq-rv-card', c.shards && 'dup', isRare(c.rarity) && 'rare', c.rarity === 'MythicRare' && 'mythic'].filter(Boolean).join('.');
    const card = el(`div.${cls}`, {}, el('div.cq-rv-y', {}, el('i.rise'), el('div.cq-rv-in', {}, el('div.cq-rv-face.cq-rv-back'), face), plus));
    card.setAttribute('role', 'img');
    card.setAttribute('aria-label', c.name);
    return el('div.cq-rv-slot', {}, card);
  });
  const grid = el('div.cq-rv-grid', {}, ...slots);
  grid.style.setProperty('--per-row', String(Math.min(8, Math.max(1, cards.length))));

  // The seal is cut into strips, so it can roll up behind the tear
  const top = el('div.cq-pk-top', {}, ...Array.from({ length: SLICES }, (_, i) => el('div.cq-pk-slice', { left: `${i * PACK_W / SLICES}px`, width: `${PACK_W / SLICES + .6}px` },
    el('div.cq-pk-slice-in', { left: `${-i * PACK_W / SLICES}px`, width: `${PACK_W}px`, clipPath: TOP }, el('div.cq-pk-crimp')))));
  const art = el('div.cq-pk-art', spec.art ? { backgroundImage: `url("${artUrl(spec.art)}")` } : {});
  if (spec.chaos) art.innerHTML = CHAOS_MARK;
  const body = el('div.cq-pk-body', { clipPath: BODY }, art, el('div.cq-pk-crimp'), el('div.cq-pk-name', {}, spec.name), el('div.cq-pk-sub', {}, spec.sub),
    el('div.cq-pk-shade'), el('div.cq-pk-sheen'));
  const whole = el('div.cq-pk-whole', {}, body, top);
  const zip = el('div.cq-pk-zip'), mouth = el('div.cq-pk-mouth');
  const glow = [el('div.cq-pk-glow.far', { clipPath: OUTLINE }), el('div.cq-pk-glow.near', { clipPath: OUTLINE }),
    ...[20, 50, 78].map((x, i) => el('i.cq-pk-speck', { left: `${x}%`, animationDelay: `${-i * .9}s` }))];
  const pk = el(spec.chaos ? 'button.cq-pk.chaos' : 'button.cq-pk', {}, ...glow, mouth, el('div.cq-pk-lit', {}, whole), zip);
  pk.setAttribute('aria-label', spec.hint);
  const hint = el('p.cq-pk-hint', {}, spec.hint);
  const cell = el('div.cq-bp-cell', {}, pk, hint);
  const stage = el('div.cq-bp-stage', {}, grid, cell);
  host.replaceChildren(stage);

  let opening: Promise<void> | null = null;
  let settle: () => void = () => { };
  const timers: number[] = [];

  const turnUp = (i: number) => {
    if (up.has(i)) return;
    up.add(i);
    const card = slots[i].firstElementChild as HTMLElement;
    card.classList.add('up');
    // Once the card is face up and landed, it shows a preview on hover like any other card
    card.dataset.image = cards[i].image;
    hooks.flipped(cards[i], slots[i].getBoundingClientRect());
  };

  function finish(): void {
    timers.splice(0).forEach(clearTimeout);
    // The pack's idle animations repeat forever and cannot be finished, so they are cancelled
    stage.getAnimations({ subtree: true }).forEach(a => { try { a.finish(); } catch { a.cancel(); } });
    cell.remove();
    slots.forEach(slot => slot.classList.add('shown'));
    cards.forEach((_, i) => turnUp(i));
    opening ??= Promise.resolve();
    settle();
    hooks.shown();
  }

  function open(): Promise<void> {
    if (opening) return opening;
    opening = new Promise<void>(done => { settle = done; });
    hooks.opening();
    if (reducedMotion()) {
      finish();
      return opening;
    }
    hint.remove();
    pk.style.animation = 'none';
    pk.classList.add('open');
    glow.forEach(g => g.animate([{ opacity: getComputedStyle(g).opacity }, { opacity: 0 }], { duration: 380, fill: 'forwards' }));
    const box = pk.getBoundingClientRect();
    const n = cards.length, mid = (n - 1) / 2;
    const px = box.left + box.width / 2;

    // The seal peels from one corner. The tear runs across, and the freed seal is pulled up and back in an arc behind it
    const W = box.width, R = 84, D = 640, S = 30, w = W / SLICES;
    const prog = (u: number) => { const v = Math.max(0, (u - .14) / .86); return v < .5 ? 2 * v * v : 1 - (-2 * v + 2) ** 2 / 2; };
    const sample = <T>(fn: (xk: number) => T): T[] => Array.from({ length: S + 1 }, (_, j) => fn(prog(j / S) * (W + 8)));
    [...top.children].forEach((slice, i) => {
      const x = (i + .5) * w;
      slice.animate(sample(xk => {
        const s = Math.max(0, xk - x), a = s / R;
        return { transform: `translate(${s ? xk - R * Math.sin(a) - x : 0}px, ${-R * (1 - Math.cos(a))}px) rotate(${a}rad)` };
      }), { duration: D, fill: 'forwards' });
    });
    top.animate([{ transform: 'none', opacity: 1 }, { transform: 'translate(60px, -90px) rotate(70deg)', opacity: 1, offset: .5 }, { transform: 'translate(150px, 40px) rotate(200deg)', opacity: 0 }],
      { duration: 520, delay: D + 30, easing: 'cubic-bezier(.3,.6,.6,1)', fill: 'forwards' });
    zip.animate(sample(xk => ({ left: `${xk}px`, opacity: 1 })), { duration: D, fill: 'forwards' });
    zip.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 140, delay: D, fill: 'forwards' });
    mouth.animate(sample(xk => ({ opacity: 1, clipPath: `inset(-70px ${Math.max(0, 100 - 100 * xk / W)}% -70px -70px)` })), { duration: D, fill: 'forwards' });
    pk.animate([{ transform: 'none' }, { transform: 'rotate(-3deg) translate(-5px, 2px)', offset: .13 }, { transform: 'rotate(2.5deg) translate(4px, -2px)', offset: .55 },
      { transform: 'rotate(-1.2deg) translate(-1px, 3px)', offset: .82 }, { transform: 'none' }], { duration: D + 260, easing: 'ease-in-out' });
    for (let k = 0; k < 14; k++) {
      const u = .2 + k * .055, side = (k * 37) % 17 - 8, rise = 26 + (k * 53) % 34;
      const fleck = el('i.cq-pk-fleck', { left: `${prog(u) * W}px`, top: `${SEAL}%` });
      pk.append(fleck);
      fleck.animate([{ transform: 'translate(0, 0) rotate(0deg)', opacity: 1 }, { transform: `translate(${side * 2.4}px, ${-rise}px) rotate(200deg)`, opacity: 1, offset: .4, easing: 'ease-in' },
        { transform: `translate(${side * 4.4}px, ${50 + rise}px) rotate(520deg)`, opacity: 0 }], { duration: 620, delay: u * D, easing: 'ease-out', fill: 'both' });
    }
    pk.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 460, delay: 700 + n * 78 + 120, easing: 'ease-in', fill: 'forwards' });

    // Each card moves sideways and vertically on separate curves, so its path from the mouth to its place bends
    const FLIGHT = 860;
    let flipAt = 0;
    const flips = slots.map((slot, i) => {
      const card = slot.firstElementChild as HTMLElement, y = card.firstElementChild as HTMLElement, inner = y.firstElementChild as HTMLElement;
      const r = slot.getBoundingClientRect();
      const dx = px - (r.left + r.width / 2), mouthDy = box.top + box.height * SEAL / 100 - (r.top + r.height / 2);
      const start = 700 + i * 78, lean = (i - mid) * 3.2;
      slot.classList.add('shown');
      // The flight ends where the card rests, so it does not fill forwards. A card with an animation left on it gets no hover preview
      card.animate([{ transform: `translateX(${dx}px) rotate(0deg) scale(.6)`, opacity: 0 }, { transform: `translateX(${dx}px) rotate(0deg) scale(.6)`, opacity: 1, offset: .06, easing: 'cubic-bezier(.3,.6,.5,1)' },
        { transform: `translateX(${dx * .86}px) rotate(${lean}deg) scale(.72)`, opacity: 1, offset: .34, easing: 'cubic-bezier(.45,0,.3,1)' }, { transform: 'translateX(0px) rotate(0deg) scale(1)', opacity: 1 }],
        { duration: FLIGHT, delay: start, fill: 'backwards' });
      y.animate([{ transform: `translateY(${mouthDy + 96}px)`, easing: 'cubic-bezier(.3,.7,.4,1)' }, { transform: `translateY(${mouthDy - 118}px)`, offset: .34, easing: 'cubic-bezier(.45,0,.3,1)' }, { transform: 'translateY(0px)' }],
        { duration: FLIGHT, delay: start, fill: 'both' });
      // A card flips as it lands, but no sooner after the previous card than its rarity's delay allows
      const rare = isRare(cards[i].rarity);
      flipAt = Math.max(start + FLIGHT * .6, i ? flipAt + (GAP[cards[i].rarity] ?? 70) : 0);
      const turn = rare ? 700 : 440;
      timers.push(window.setTimeout(() => turnUp(i), flipAt + turn / 2));
      return inner.animate([{ transform: 'rotateY(180deg)' }, { transform: 'rotateY(0deg)' }], { duration: turn, delay: flipAt, easing: rare ? SOFT : 'cubic-bezier(.4,0,.2,1)', fill: 'forwards' }).finished;
    });
    void Promise.all(flips).then(() => {
      if (!cell.isConnected) return;
      cell.remove();
      cards.forEach((_, i) => turnUp(i));
      settle();
      hooks.shown();
    }, () => { });
    return opening;
  }

  pk.addEventListener('click', e => { e.stopPropagation(); void open(); });
  return { open, finish };
}
