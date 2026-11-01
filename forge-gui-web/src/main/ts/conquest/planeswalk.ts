// The opening played when the player planeswalks: starting a conquest, or travelling to another plane. Light gathers into a vortex,
// a rift opens on the plane's art and its name is set above. The map loads behind it. A click ends it early

import { make, q, reducedMotion } from '../dom';
import { leave } from '../closing';
import { artUrl } from '../sleeves';
import { t } from '../text';

/** The animation's times are written in seconds of this story clock, which runs at this fraction of real time. */
const PACE = 0.75;
/** When the story ends and the overlay is removed. */
const END_S = 4.45;
const STREAK_COUNT = 900;

const clamp = (v: number, a = 0, b = 1) => Math.max(a, Math.min(b, v));
const ease = (x: number) => 1 - Math.pow(1 - clamp(x), 3);
const smooth = (a: number, b: number, x: number) => {
  const k = clamp((x - a) / (b - a));
  return k * k * (3 - 2 * k);
};

interface Streak { a: number; d: number; w: number; hue: number; light: number; len: number; ph: number }

/** Streaks of light circling the rift. A few are warm, the rest violet. d is the distance from the rift in rift radii. */
function streaks(): Streak[] {
  return Array.from({ length: STREAK_COUNT }, () => ({
    a: Math.random() * Math.PI * 2, d: 1.02 + Math.pow(Math.random(), 1.6) * 1.5, w: .5 + Math.random() * 1.6,
    hue: Math.random() < .18 ? 42 : 252 + Math.random() * 22, light: 70 + Math.random() * 25, len: .05 + Math.random() * .16, ph: Math.random() * 6.28,
  }));
}

/** The rift's edge at angle th: a circle that shifts a little over time. */
function riftPath(c: CanvasRenderingContext2D, x: number, y: number, r: number, time: number): void {
  c.beginPath();
  for (let i = 0; i <= 120; i++) {
    const th = i / 120 * Math.PI * 2;
    const rr = r * (1 + .014 * Math.sin(5 * th + time * 1.1) + .008 * Math.sin(9 * th - time * 1.6) + .004 * Math.sin(17 * th + time * 2.2));
    if (i) c.lineTo(x + Math.cos(th) * rr, y + Math.sin(th) * rr);
    else c.moveTo(x + Math.cos(th) * rr, y + Math.sin(th) * rr);
  }
  c.closePath();
}

let running: HTMLElement | null = null;
let stop: (() => void) | null = null;

/** Ends the opening early, as a click does. Nothing happens when none is playing. */
export function endPlaneswalk(): void {
  stop?.();
}

/** Plays the opening for a plane. art is the plane's art key, and left is how many of its events are still to conquer. */
export function planeswalk(plane: string, art: string, left: number): void {
  if (reducedMotion()) return;
  running?.remove();
  const root = make('div', '');
  root.id = 'cq-planeswalk';
  root.setAttribute('role', 'status');
  root.innerHTML = '<canvas></canvas><div class="cq-pw-title"><span class="cq-pw-kicker"></span><span class="cq-pw-name"></span></div><p class="cq-pw-sub"></p>';
  q(root, '.cq-pw-kicker').textContent = t('lblWebConquestPlaneswalkTo');
  q(root, '.cq-pw-name').textContent = plane;
  q(root, '.cq-pw-sub').textContent = t('lblWebConquestEventsLeft', left);
  document.body.append(root);
  running = root;

  const canvas = q<HTMLCanvasElement>(root, 'canvas');
  const ctx = canvas.getContext('2d')!;
  const picture = new Image();
  picture.src = artUrl(art);
  const lines = streaks();
  const off = document.createElement('canvas');
  const octx = off.getContext('2d')!;
  let w = 0, h = 0, r0 = 0, cx = 0, cy = 0;
  const size = () => {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    w = window.innerWidth;
    h = window.innerHeight;
    canvas.width = Math.round(w * dpr);
    canvas.height = Math.round(h * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    r0 = Math.min(h * .247, w * .38);
    cx = w / 2;
    cy = h * .445;
    off.width = off.height = Math.ceil(r0 * 2.9);
  };
  size();
  addEventListener('resize', size);

  const draw = (time: number) => {
    ctx.clearRect(0, 0, w, h);
    const close = smooth(3.0, 3.45, time);
    const r = r0 * ease((time - .55) / 1.1) * (1 - .15 * close);
    const gather = smooth(.25, 1.0, time) * (1 - close);
    if (gather > 0) {
      const g = ctx.createRadialGradient(cx, cy, 0, cx, cy, r0 * 2.35);
      g.addColorStop(0, `rgba(150,130,255,${.20 * gather})`);
      g.addColorStop(.45, `rgba(90,70,180,${.10 * gather})`);
      g.addColorStop(1, 'rgba(0,0,0,0)');
      ctx.fillStyle = g;
      ctx.fillRect(0, 0, w, h);
    }
    // The streaks circle slower further out, drift inward while the rift opens, then spiral away
    ctx.save();
    ctx.globalCompositeOperation = 'lighter';
    const base = Math.max(r, r0 * .27);
    for (const s of lines) {
      const alpha = gather * clamp(1.6 - (s.d - 1)) * (.22 + .16 * Math.sin(time * 2 + s.ph)) * (1 - close * .6);
      if (alpha <= .01) continue;
      const dist = base * (s.d - .25 * smooth(.2, 1.6, time) * (s.d - 1)) * (1 + 1.4 * close * close);
      const ang = s.a + time * (1.25 / (s.d * s.d)) + .5 * close;
      ctx.strokeStyle = `hsla(${s.hue}, ${s.hue < 100 ? 80 : 55}%, ${s.light}%, ${alpha})`;
      ctx.lineWidth = s.w;
      ctx.beginPath();
      ctx.arc(cx, cy, dist, ang - s.len / s.d, ang);
      ctx.stroke();
    }
    ctx.restore();
    if (r <= 2) return;
    const half = off.width / 2;
    if (picture.complete && picture.naturalWidth) {
      // The plane seen through the rift, coming into focus as it opens, darker towards the edge
      octx.clearRect(0, 0, off.width, off.height);
      const focus = smooth(1.0, 2.0, time);
      octx.filter = `blur(${(1 - focus) * 14}px) brightness(${.25 + .95 * focus + .35 * Math.sin(Math.PI * focus)}) saturate(${.3 + .7 * focus})`;
      const k = off.width / Math.min(picture.naturalWidth, picture.naturalHeight) * (1.12 - .1 * focus);
      octx.drawImage(picture, half - picture.naturalWidth * k / 2, half - picture.naturalHeight * k / 2, picture.naturalWidth * k, picture.naturalHeight * k);
      octx.filter = 'blur(9px)';
      octx.globalCompositeOperation = 'destination-in';
      octx.fillStyle = '#000';
      riftPath(octx, half, half, r * .97, time);
      octx.fill();
      octx.filter = 'none';
      const v = octx.createRadialGradient(half, half, 0, half, half, r * 1.02);
      v.addColorStop(0, 'rgba(10,6,24,0)');
      v.addColorStop(.62, 'rgba(10,6,24,0)');
      v.addColorStop(1, 'rgba(10,6,24,.7)');
      octx.globalCompositeOperation = 'source-atop';
      octx.fillStyle = v;
      octx.fillRect(0, 0, off.width, off.height);
      octx.globalCompositeOperation = 'source-over';
      ctx.globalAlpha = 1 - smooth(3.05, 3.6, time);
      ctx.drawImage(off, cx - half, cy - half);
      ctx.globalAlpha = 1;
    }
    // The rim: a soft glow that follows the edge
    ctx.globalCompositeOperation = 'lighter';
    for (const [width, a, blur] of [[22, .06, 60], [8, .12, 28], [2.5, .22, 10]]) {
      ctx.save();
      ctx.shadowColor = 'rgba(170,150,255,.9)';
      ctx.shadowBlur = blur;
      ctx.strokeStyle = `rgba(205,195,255,${a * (1 - close)})`;
      ctx.lineWidth = width;
      riftPath(ctx, cx, cy, r, time);
      ctx.stroke();
      ctx.restore();
    }
    ctx.globalCompositeOperation = 'source-over';
  };

  let start = 0;
  const finish = () => {
    removeEventListener('resize', size);
    if (running === root) {
      running = null;
      stop = null;
    }
  };
  const frame = (now: number) => {
    if (running !== root) return;
    start ||= now;
    const time = (now - start) / 1000 * PACE;
    if (time >= END_S) {
      root.remove();
      finish();
      return;
    }
    draw(time);
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
  stop = () => {
    finish();
    leave(root);
  };
  root.onclick = stop;
}
