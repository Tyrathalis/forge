import { expect, test, type Locator, type Page } from '@playwright/test';
import { act, board, lobby, passUntil, probe, setState, settled, PHONE, SMALL_PHONE, WIDE_TOUCH, type Probe, type Table } from './probe';
import { enterName, hold } from './steps';

// On a busy machine a dev-mode board can arrive late. A tap or hold made before it arrives has no effect
test.describe.configure({ retries: 2 });

/** A board set up from a state arrives in several updates. A card touched before the last update is about to be replaced. */
async function settledBoard(p: Probe, state: string, table?: Table): Promise<void> {
  await board(p, state, table);
  await p.page.waitForTimeout(1500);
  await expect(p.page.locator('#coin-toss')).toBeHidden();
}

/** Plays the first card in hand, or the first with the given name, as a tap does. It retries if a new turn's banner blocked the tap. */
async function castFromHand(p: Probe, name = ''): Promise<void> {
  for (let tries = 0; tries < 6; tries++) {
    const key = await p.page.evaluate(name => Number([...document.querySelectorAll<HTMLElement>('#hand .card')].find(c => c.textContent?.includes(name))?.dataset.key), name);
    await act(p.page, 'selectCard', key, false, 0, 0);
    const asked = await p.page.waitForFunction(() => {
      const prompt = window.forge.model.prompt;
      return !!prompt && (!prompt.priority || prompt.selectable.length > 0 || prompt.selectablePlayers.length > 0);
    }, undefined, { timeout: 1500 }).then(() => true, () => false);
    if (asked) return;
  }
}

/** The panel's box after its slide-in ends. The panel is visible from the first frame, so a visibility check is too early. */
async function settledBox(panel: Locator) {
  await panel.evaluate(el => Promise.all(el.getAnimations().map(a => a.finished.catch(() => {}))));
  return (await panel.boundingBox())!;
}

/** Taps a creature to declare it as an attacker. It retries if a new turn's banner blocked the tap. */
async function declareAttacker(card: Locator): Promise<void> {
  await expect(async () => {
    // A second tap on an attacker would remove it from the attack
    if (!await card.evaluate(el => el.classList.contains('attacking'))) await card.tap({ force: true });
    await expect(card).toHaveClass(/\battacking\b/, { timeout: 2000 });
  }).toPass({ timeout: 20_000 });
}

const form = (page: Page) => page.evaluate(() => document.documentElement.dataset.form ?? '');

probe('a phone held upright gets the portrait form', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  expect(await form(p.page)).toBe('portrait');
}, PHONE);

probe('a desktop window does not', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  expect(await form(p.page)).toBe('');
});


probe('a tap on a card opens no hover preview', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  // A tap on your own land acts on it, so only a hover could open a preview
  const card = p.page.locator('#me .battlefield .card').first();
  await card.tap();
  // A phone browser sends mouse events after a tap. Chromium's emulation does not, so the test sends one
  await card.dispatchEvent('mouseenter');
  await p.page.waitForTimeout(600);
  // The preview gets one of these classes the first time it shows a card, and keeps it after it closes
  await expect(p.page.locator('#zoom')).not.toHaveClass(/image-only|text-card/);
}, WIDE_TOUCH);

probe('a long-press asks for no ability menu', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  // A right-click asks the host for the card's abilities. A held finger must not do this on any browser
  await p.page.evaluate(() => {
    const seen = window as unknown as { menus: number; asked: number };
    seen.menus = seen.asked = 0;
    const send = window.forge.actions.selectCard;
    window.forge.actions.selectCard = (key, menu, x, y) => { if (menu) seen.menus++; send(key, menu, x, y); };
    addEventListener('contextmenu', e => { if ((e.target as Element).closest('#opponent .card')) seen.asked++; }, true);
  });
  // The press must hit the card, so wait for the coin toss over the board to close
  await expect(p.page.locator('#coin-toss')).toBeHidden();
  const card = p.page.locator('#opponent .card').first();
  await expect(card).toBeVisible();
  await settled(p.page);
  const at = (await card.boundingBox())!;
  const x = Math.round(at.x + at.width / 2);
  const y = Math.round(at.y + at.height / 2);
  // A real long-press sends the browser's contextmenu event while the finger is down, like a right-click
  const cdp = await p.page.context().newCDPSession(p.page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await p.page.waitForTimeout(200);
  await p.page.mouse.click(x, y, { button: 'right' });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
  const seen = await p.page.evaluate(() => {
    const { menus, asked } = window as unknown as { menus: number; asked: number };
    return { menus, asked };
  });
  expect(seen.asked).toBeGreaterThan(0);
  expect(seen.menus).toBe(0);
}, WIDE_TOUCH);

const box = async (page: Page, sel: string) => (await page.locator(sel).boundingBox())!;

probe('the prompt is a dock with OK and Cancel in opposite corners', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const dock = await box(p.page, '#prompt');
  const ok = await box(p.page, '#prompt .ok');
  const cancel = await box(p.page, '#prompt .cancel');
  expect(dock.y + dock.height).toBeLessThanOrEqual(664);
  expect(dock.width).toBe(390);
  expect(ok.x).toBeLessThan(8);
  expect(cancel.x + cancel.width).toBeGreaterThan(382);
  expect(ok.height).toBeGreaterThanOrEqual(44);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}, PHONE);

// Different names, so the cards are shown one by one and not grouped into piles
const CROWD = 'humanbattlefield=' + ['Grizzly Bears', 'Runeclaw Bear', 'Hill Giant', 'Gray Ogre', 'Goblin Piker', 'Raging Goblin', 'Craw Wurm', 'Scathe Zombies',
  'Ironroot Treefolk', 'Giant Spider', 'Wall of Wood', 'Elvish Warrior', 'Forest', 'Forest', 'Forest', 'Forest'].join(';')
  + '\naibattlefield=' + ['Hill Giant', 'Gray Ogre', 'Goblin Piker', 'Raging Goblin', 'Craw Wurm', 'Scathe Zombies', 'Giant Spider', 'Wall of Wood', 'Mountain', 'Mountain'].join(';');

for (const [name, phone] of [['390', PHONE], ['360', SMALL_PHONE]] as const) {
  probe(`a crowded board keeps its cards 40px wide at ${name}`, async p => {
    await board(p, CROWD);
    await p.page.waitForTimeout(1200);
    const narrow = await p.page.evaluate(() => Math.min(...[...document.querySelectorAll<HTMLElement>('#match .battlefield .card')]
      .filter(c => !c.classList.contains('tapped')).map(c => c.getBoundingClientRect().width)));
    expect(narrow).toBeGreaterThanOrEqual(39.5);
    expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(phone.viewport!.width);
    for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(88);
    const dock = await box(p.page, '#prompt');
    expect(dock.y + dock.height).toBeLessThanOrEqual(phone.viewport!.height);
  }, phone);
}

probe('a tap on the phase strip opens stops as a sheet above the dock, and Back closes it', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const strip = await box(p.page, '#phase-strip .pill');
  expect(strip.x + strip.width).toBeLessThanOrEqual(390);
  await p.page.locator('#phase-strip .pill').tap();
  const stops = p.page.locator('#phase-strip .stops');
  await expect(stops).toBeVisible();
  const sheet = await box(p.page, '#phase-strip .stops');
  const dock = await box(p.page, '#prompt');
  expect(sheet.width).toBe(390);
  expect(Math.round(sheet.y + sheet.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  for (const cell of await stops.locator('.cell').all()) {
    const b = (await cell.boundingBox())!;
    expect(Math.min(b.width, b.height)).toBeGreaterThanOrEqual(44);
  }
  await expect(stops.locator('.until-row button')).toHaveCount(5);
  await p.page.goBack();
  await expect(stops).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
}, PHONE);

probe('a long-press on a card opens its details as a sheet above the dock', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await hold(p.page, p.page.locator('#opponent .card').first());
  const zoom = p.page.locator('#zoom.sheet');
  await expect(zoom).toBeVisible();
  await expect(zoom.locator('.name')).toHaveText('Grizzly Bears');
  const sheet = await box(p.page, '#zoom');
  const dock = await box(p.page, '#prompt');
  expect(sheet.width).toBe(390);
  expect(Math.round(sheet.y + sheet.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  await zoom.locator('.sheet-close').tap();
  await expect(zoom).toBeHidden();
}, PHONE);

probe('a long-press inspects and does not play', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  const forest = p.page.locator('#me .battlefield .card').first();
  await hold(p.page, forest);
  await expect(p.page.locator('#zoom.sheet')).toBeVisible();
  await p.page.waitForTimeout(600);
  // A tap would have tapped the land for mana
  await expect(forest).not.toHaveClass(/tapped/);
}, PHONE);

probe('a tap on an opponent\'s card with nothing asked opens its details', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await expect(p.page.locator('#zoom.sheet .name')).toHaveText('Grizzly Bears');
}, PHONE);

probe('a prompt that picks cards closes the sheet', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await expect(p.page.locator('#zoom.sheet')).toBeVisible();
  await castFromHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#zoom.sheet')).toBeHidden();
  await expect(p.page.locator('#prompt .cancel')).toBeVisible();
}, PHONE);

probe('on a wide touch screen a long-press opens a panel that can be closed', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await hold(p.page, p.page.locator('#opponent .card').first());
  const zoom = p.page.locator('#zoom.sheet');
  await expect(zoom).toBeVisible();
  const at = await box(p.page, '#zoom');
  expect(at.x).toBeGreaterThan(0);
  expect(at.x + at.width).toBeLessThan(1280);
  await zoom.locator('.sheet-close').tap();
  await expect(zoom).toBeHidden();
}, WIDE_TOUCH);

probe('the hand opens as a drawer, a card is played from it, and it closes for the target', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt;Forest;Giant Growth\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  const strip = await box(p.page, '#hand');
  expect(strip.height).toBeLessThanOrEqual(66);
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  const drawer = await box(p.page, '#hand');
  const dock = await box(p.page, '#prompt');
  expect(Math.round(drawer.y + drawer.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  const bolt = p.page.locator('#hand .card', { hasText: 'Lightning Bolt' });
  expect((await bolt.boundingBox())!.width).toBeGreaterThanOrEqual(75);
  await bolt.tap();
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
  await expect(p.page.locator('#opponent .card.selectable')).toBeVisible();
  // Cancel leaves the card in hand, and the drawer opens again
  await p.page.locator('#prompt .cancel').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  await p.page.locator('#hand-head .close').tap();
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
}, PHONE);

probe('your bar and your hand do not overlap, and the strip floats no costs over its cards', async p => {
  await settledBoard(p, 'humanhand=Giant Growth;Lightning Bolt;Counterspell\nhumanbattlefield=Forest;Forest;Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  const bar = await box(p.page, '#me .player');
  const hand = await box(p.page, '#hand');
  expect(Math.round(bar.y + bar.height)).toBeLessThanOrEqual(Math.round(hand.y) + 1);
  // The card images show their own costs, so the strip is only as tall as the card tops it shows
  await expect(p.page.locator('#hand .card .cost-badge:visible')).toHaveCount(0);
  expect(hand.height).toBeLessThanOrEqual(52);
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  await expect(p.page.locator('#hand .card .cost-badge:visible')).toHaveCount(0);
}, PHONE);

probe('a bar keeps its zones as counts, and a tap opens them', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(48);
  await expect(p.page.locator('#me .zone-tile').first()).toBeHidden();
  const pill = p.page.locator('#me .zones-pill');
  await expect(pill.locator('[data-zone="Graveyard"] b')).toHaveText('2');
  // The counts sit inside each bar, to the right of the portrait
  for (const seat of ['#me', '#opponent']) {
    const counts = await box(p.page, `${seat} .zones-pill`);
    const bar = await box(p.page, `${seat} .player`);
    const face = await box(p.page, `${seat} .avatar`);
    expect(counts.x).toBeGreaterThan(face.x + face.width);
    expect(counts.y).toBeGreaterThanOrEqual(bar.y - 1);
    expect(counts.y + counts.height).toBeLessThanOrEqual(bar.y + bar.height + 1);
    expect(counts.height).toBeGreaterThanOrEqual(44);
  }
  await pill.tap();
  const tile = p.page.locator('#me .zone-tile[data-zone="Graveyard"]');
  await expect(tile).toBeVisible();
  const at = (await tile.boundingBox())!;
  expect(at.height / at.width).toBeGreaterThan(1.3);
  expect(at.x + at.width).toBeLessThanOrEqual(390);
  await tile.tap();
  await expect(p.page.locator('#zones .zone-panel')).toBeVisible();
  await expect(tile).toBeHidden();
}, PHONE);

probe('a spell on the stack shows as a chip under the strip, and its target wears its number', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await castFromHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await p.page.locator('#opponent .card.selectable').first().tap();
  await p.until('the bolt is paid for or on the stack', () => !!window.forge.model.prompt?.paying || document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0);
  if (await p.page.evaluate(() => !!window.forge.model.prompt?.paying)) await p.page.locator('#prompt .ok').tap();
  await p.until('the bolt is on the stack', () => document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0, undefined, p.page, 20_000);
  const head = await box(p.page, '#stack .head');
  expect(head.height).toBeGreaterThanOrEqual(44);
  expect(head.x + head.width).toBeLessThanOrEqual(390);
  await expect(p.page.locator('#stack .head')).toContainText('Lightning Bolt');
  await expect(p.page.locator('#opponent .card .target-tag.top')).toHaveText('1');
  const dock = await box(p.page, '#prompt');
  const stack = await box(p.page, '#stack');
  expect(stack.y + stack.height).toBeLessThanOrEqual(dock.y + 1);
}, PHONE);

const FOUR = { players: 4 };

/** Shows an opponent's seat as a player does. It taps the players button, then that opponent's row. */
async function showSeat(page: Page, n: number): Promise<void> {
  await page.locator('#seat-switch').tap();
  await page.locator('#seat-tabs .seat-tab').nth(n).tap();
  await expect(page.locator('#seat-tabs')).toBeHidden();
}

probe('three opponents are behind a players button, and a change marks a hidden seat', async p => {
  await settledBoard(p, 'p0battlefield=Forest\np1battlefield=Mountain\np2battlefield=Island\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  const tabs = p.page.locator('#seat-tabs .seat-tab');
  await expect(p.page.locator('#seat-tabs')).toBeHidden();
  await expect(p.page.locator('#opponent .seat:not([hidden])')).toHaveCount(1);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  const dock = await box(p.page, '#prompt');
  expect(dock.y + dock.height).toBeLessThanOrEqual(664);
  await p.page.locator('#seat-switch').tap();
  await expect(tabs).toHaveCount(3);
  await expect(tabs.first()).toBeVisible();
  for (const tab of await tabs.all()) expect((await tab.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await tabs.nth(1).tap();
  // Show each seat once, so each seat's current cards count as seen
  for (const n of [2, 0]) await showSeat(p.page, n);
  await expect(tabs.nth(0)).toHaveClass(/\bon\b/);
  await setState(p, 'p0battlefield=Forest\np1battlefield=Mountain\np2battlefield=Island;Grizzly Bears\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  await expect(p.page.locator('#seat-switch')).toHaveClass(/\bchanged\b/);
  await expect(tabs.nth(1)).toHaveClass(/\bchanged\b/);
  await showSeat(p.page, 1);
  await expect(tabs.nth(1)).not.toHaveClass(/\bchanged\b/);
  await expect(p.page.locator('#opponent .seat:not([hidden]) .card', { hasText: 'Grizzly Bears' })).toBeVisible();
}, PHONE);

probe('a hidden opponent can be chosen from the dock', async p => {
  await settledBoard(p, 'p0hand=Lightning Bolt\np0battlefield=Mountain\np1battlefield=Mountain\np2battlefield=Island\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  await castFromHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectablePlayers.length ?? 0) > 0);
  // Two of the three opponents are hidden, and each of them has a button in the dock
  await expect(p.page.locator('#prompt .choose-players button')).toHaveCount(2);
  await expect(p.page.locator('#seat-tabs .seat-tab.asked')).toHaveCount(2);
  await expect(p.page.locator('#seat-switch')).toHaveClass(/\basked\b/);
}, PHONE);

probe('a question about a hidden opponent\'s cards marks the players button and leaves the view where it is', async p => {
  await settledBoard(p, 'p0hand=Murder\np0battlefield=Swamp;Swamp;Swamp\np1battlefield=Mountain\np2battlefield=Island;Hill Giant\np3battlefield=Swamp\nactiveplayer=p0', FOUR);
  const tabs = p.page.locator('#seat-tabs .seat-tab');
  // Show a seat other than the only player with a creature
  await showSeat(p.page, 0);
  await castFromHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(tabs.nth(0)).toHaveClass(/\bon\b/);
  await expect(p.page.locator('#seat-switch')).toHaveClass(/\basked\b/);
  await showSeat(p.page, 1);
  await expect(p.page.locator('#opponent .seat:not([hidden]) .card.selectable')).toBeVisible();
}, PHONE);

probe('the menu sheet holds the log, and conceding asks first', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.locator('#menu-button').tap();
  const sheet = p.page.locator('.menu-sheet');
  await expect(sheet).toBeVisible();
  await expect(sheet.locator('#log')).toBeVisible();
  const at = await box(p.page, '.menu-sheet');
  const dock = await box(p.page, '#prompt');
  expect(at.width).toBe(390);
  expect(Math.round(at.y + at.height)).toBeLessThanOrEqual(Math.round(dock.y) + 1);
  // The sheet is only as tall as the content of its tab
  await sheet.locator('.menu-tabs button').nth(1).tap();
  await settled(p.page);
  expect((await box(p.page, '.menu-sheet')).height).toBeLessThan(300);
  await sheet.locator('.concede').tap();
  const ask = p.page.locator('[role=alertdialog]');
  await expect(ask).toBeVisible();
  await expect(p.page.locator('#game-over')).toBeHidden();
  await ask.locator('.danger').tap();
  await expect(p.page.locator('#game-over')).toBeVisible();
}, PHONE);

probe('a graveyard opens as a sheet above the dock, and Back closes it', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  await p.page.locator('#me .zones-pill').tap();
  await p.page.locator('#me .zone-tile[data-zone="Graveyard"]').tap();
  const panel = p.page.locator('#zones .zone-panel');
  await expect(panel).toBeVisible();
  const at = await settledBox(panel);
  const dock = await box(p.page, '#prompt');
  expect(at.width).toBe(390);
  expect(at.y + at.height).toBeLessThanOrEqual(dock.y + 2);
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
  await p.page.goBack();
  await expect(panel).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
}, PHONE);

probe('Back with nothing open asks before leaving the match', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  await p.page.locator('#leave-ask .stay').tap();
  await expect(p.page.locator('#leave-ask')).toBeHidden();
  await expect(p.page.locator('#match')).toBeVisible();
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
}, PHONE);

probe('a phone on its side is asked to turn upright', async p => {
  await board(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await expect(p.page.locator('#turn-upright')).toBeVisible();
}, { viewport: { width: 664, height: 390 }, hasTouch: true, isMobile: true });

probe('the page can be installed to the home screen', async p => {
  await p.page.goto(p.server.url);
  const href = await p.page.locator('link[rel=manifest]').getAttribute('href');
  const answer = await p.page.request.get(new URL(href!, p.server.url).href);
  expect(answer.headers()['content-type']).toContain('application/manifest+json');
  const manifest = await answer.json();
  expect(manifest.display).toBe('standalone');
  expect(manifest.icons.map((i: { sizes: string }) => i.sizes)).toEqual(['192x192', '512x512']);
  const icon = await p.page.request.get(new URL(manifest.icons[1].src, p.server.url).href);
  expect(icon.status()).toBe(200);
  expect(icon.headers()['content-type']).toBe('image/png');
});

for (const [name, phone] of [['390', PHONE], ['360', SMALL_PHONE]] as const) {
  probe(`match setup fits a phone at ${name}`, async p => {
    await lobby(p);
    await settled(p.page);
    const { width, height } = phone.viewport!;
    expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    const play = await box(p.page, '#play');
    expect(play.y + play.height).toBeLessThanOrEqual(height);
    expect(play.height).toBeGreaterThanOrEqual(44);
    // The folded chat is a chip in the header row, so it covers none of the seats and Play spans the screen
    const chat = await box(p.page, '.ph-chat .dock.folded');
    expect(chat.y + chat.height).toBeLessThanOrEqual(52);
    expect(play.width).toBeGreaterThan(width * 0.85);
    for (const plate of await p.page.locator('#seats .plate').all()) expect((await plate.boundingBox())!.width).toBeGreaterThan(width * 0.85);
    for (const b of await p.page.locator('.match-bar button:visible').all()) expect((await b.boundingBox())!.height).toBeGreaterThanOrEqual(40);
  }, phone);
}

probe('the start page and the deck editor do not scroll sideways on a phone', async p => {
  await p.page.goto(p.server.url);
  await enterName(p.page, 'Alice');
  await expect(p.page.locator('[data-mode=play]')).toBeVisible();
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
}, PHONE);

probe('a turn is played by touch: a land from the drawer, then an attack', async p => {
  await settledBoard(p, 'humanhand=Forest;Giant Growth\nhumanbattlefield=Forest;Grizzly Bears\naibattlefield=Mountain');
  for (const button of ['#prompt .ok', '#prompt .cancel']) {
    const at = await box(p.page, button);
    expect(at.y).toBeGreaterThanOrEqual(0);
    expect(at.y + at.height).toBeLessThanOrEqual(664);
  }
  await p.page.locator('#hand').tap();
  await p.page.locator('#hand.sheet .card', { hasText: 'Forest' }).tap();
  await p.until('the land is on the battlefield', () => document.querySelectorAll('#hand .card').length === 1);
  if (await p.page.locator('#hand.sheet').count()) await p.page.locator('#hand-head .close').tap();
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  await declareAttacker(p.page.locator('#me .battlefield .card', { hasText: 'Grizzly Bears' }));
  await p.page.locator('#prompt .ok').tap();
  await passUntil(p, 'the attack lands', () => [...window.forge.model.objects.values()].some(o => (o as { IsAI?: boolean }).IsAI && (o as { Life?: number }).Life === 18));
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await expect(p.page.locator('#zoom.placed')).toHaveCount(0);
}, PHONE);

probe('closing a sheet by its own button does not ask to leave the match', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Grizzly Bears');
  await p.page.locator('#opponent .card').first().tap();
  await p.page.locator('#zoom.sheet .sheet-close').tap();
  await expect(p.page.locator('#zoom.sheet')).toHaveCount(0);
  await p.page.waitForTimeout(400);
  await expect(p.page.locator('#leave-ask')).toBeHidden();
}, PHONE);

probe('a card in an open graveyard can be read, and the graveyard stays open under it', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\nhumangraveyard=Grizzly Bears;Hill Giant\naibattlefield=Mountain');
  await p.page.locator('#me .zones-pill').tap();
  await p.page.locator('#me .zone-tile[data-zone="Graveyard"]').tap();
  const card = p.page.locator('#zones .zone-panel .card', { hasText: 'Hill Giant' });
  await expect(card).toBeVisible();
  await hold(p.page, card);
  await expect(p.page.locator('#zoom.sheet .name')).toHaveText('Hill Giant');
  await p.page.locator('#zoom.sheet .sheet-close').tap();
  await expect(p.page.locator('#zones .zone-panel')).toBeVisible();
}, PHONE);

probe('a swipe down from a sheet\'s handle closes it', async p => {
  await settledBoard(p, 'humanhand=Forest;Giant Growth\nhumanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  const head = await box(p.page, '#hand-head');
  const x = Math.round(head.x + 60);
  const y = Math.round(head.y + 12);
  const cdp = await p.page.context().newCDPSession(p.page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  for (const dy of [30, 70, 110]) await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x, y: y + dy }] });
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
}, PHONE);

probe('after a reload in a match, Leave still leaves', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.reload({ waitUntil: 'domcontentloaded' });
  await expect(p.page.locator('#prompt .ok')).toBeVisible();
  const depth = await p.page.evaluate(() => history.length);
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  // The question adds no history entries. Staying and asking again does not add more
  await p.page.locator('#leave-ask .stay').tap();
  await p.page.goBack();
  await expect(p.page.locator('#leave-ask')).toBeVisible();
  expect(await p.page.evaluate(() => history.length)).toBeLessThanOrEqual(depth);
  await p.page.locator('#leave-ask .leave').tap();
  await expect.poll(() => p.page.url()).not.toContain(new URL(p.server.url).host);
}, PHONE);

probe('a deck is chosen in the finder by taps, with nothing off the screen', async p => {
  await lobby(p);
  const plate = p.page.locator('.plate.mine');
  await plate.locator('.sleeve').tap();
  const finder = p.page.locator('.finder');
  await expect(finder).toBeVisible();
  // The finder slides up into place, so wait for the animation
  await settled(p.page);
  expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  for (const part of ['.finder', '.finder .results', '.finder .dk-hits', '.finder-foot .use']) {
    const at = await box(p.page, part);
    expect(at.x).toBeGreaterThanOrEqual(0);
    expect(at.x + at.width).toBeLessThanOrEqual(390);
    expect(at.y + at.height).toBeLessThanOrEqual(664);
  }
  await p.page.locator('.dk-hit:not(.generated)').first().tap();
  const use = p.page.locator('.finder-foot .use');
  expect((await use.boundingBox())!.height).toBeGreaterThanOrEqual(44);
  await use.tap();
  await expect(finder).toHaveCount(0);
  await expect(plate.locator('.deck-name')).not.toHaveText('');
}, PHONE);

probe('a choice of cards from your hand opens the hand by itself', async p => {
  await settledBoard(p, 'humanhand=Faithless Looting;Giant Growth;Shock\nhumanbattlefield=Mountain\naibattlefield=Mountain');
  // Pick by name because the hand is sorted
  await castFromHand(p, 'Faithless');
  await p.until('a card from hand is asked for', () => {
    const prompt = window.forge.model.prompt;
    if (prompt?.paying && prompt.ok?.enabled) window.forge.actions.ok();
    // In some runs you get priority again with the spell on the stack. Pass once so it resolves
    const once = window as { passedLooting?: boolean };
    if (prompt?.priority && prompt.ok?.enabled && !document.getElementById('stack')?.hidden && !once.passedLooting) {
      once.passedLooting = true;
      window.forge.actions.ok();
    }
    return !!prompt && !prompt.priority && !prompt.paying && prompt.selectable.length > 0;
  }, undefined, p.page, 30_000);
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  // The stack's list would cover the board that the choice is about, so it is collapsed
  await expect(p.page.locator('#stack.chip:not(.collapsed) .pile')).toHaveCount(0);
  const pick = p.page.locator('#hand.sheet .card.selectable').first();
  await expect(pick).toBeVisible();
  await pick.tap();
  await p.until('the choice is taken', () => (window.forge.model.prompt?.selectable.length ?? 0) < 4 || !!window.forge.model.prompt?.priority);
}, PHONE);

probe('the result of a game fits the screen', async p => {
  await settledBoard(p, 'humanbattlefield=Grizzly Bears\nailife=1\naibattlefield=Mountain');
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  await declareAttacker(p.page.locator('#me .battlefield .card').first());
  await passUntil(p, 'the game ends', () => window.forge.model.gameOver);
  const word = p.page.locator('#game-over .word');
  await expect(word).toBeVisible();
  await p.page.waitForTimeout(1500);
  for (const part of ['#game-over .word', '#game-over .actions']) {
    const at = await box(p.page, part);
    expect(at.x).toBeGreaterThanOrEqual(0);
    expect(at.x + at.width).toBeLessThanOrEqual(390);
  }
  for (const button of await p.page.locator('#game-over .actions button:visible').all()) {
    const at = (await button.boundingBox())!;
    expect(at.height).toBeGreaterThanOrEqual(44);
    expect(at.x).toBeGreaterThanOrEqual(8);
    expect(at.x + at.width).toBeLessThanOrEqual(382);
  }
}, PHONE);

probe('a card that asks how to play it puts the hand away for its menu', async p => {
  await settledBoard(p, 'humanhand=Fireblast;Forest\nhumanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain\naibattlefield=Grizzly Bears');
  await p.page.locator('#hand').tap();
  await p.page.locator('#hand.sheet .card', { hasText: 'Fireblast' }).tap();
  const item = p.page.locator('#dialog-layer .card-menu:not(.game-menu) .card-menu-item').first();
  await expect(item).toBeVisible();
  await expect(p.page.locator('#hand.sheet')).toHaveCount(0);
  const menu = await box(p.page, '#dialog-layer .card-menu:not(.game-menu)');
  const dock = await box(p.page, '#prompt');
  expect(menu.y + menu.height).toBeLessThanOrEqual(dock.y + 2);
  await item.tap();
  await expect(item).toHaveCount(0);
}, PHONE);

probe('after viewing the final battlefield, the way back to the result can be tapped', async p => {
  await settledBoard(p, 'humanbattlefield=Grizzly Bears\nailife=1\naibattlefield=Mountain');
  await passUntil(p, 'attackers are asked for', () => {
    const m = window.forge.model;
    return (m.objects.get(m.root) as { Phase?: string } | undefined)?.Phase === 'COMBAT_DECLARE_ATTACKERS' && !!m.prompt?.ok?.enabled;
  });
  // A long step name does not truncate the name of the turn's owner
  expect(await p.page.evaluate(() => { const b = document.querySelector<HTMLElement>('#phase-strip .owner b')!; return b.scrollWidth <= b.clientWidth; })).toBe(true);
  await declareAttacker(p.page.locator('#me .battlefield .card').first());
  await passUntil(p, 'the game ends', () => window.forge.model.gameOver);
  const view = p.page.locator('#game-over .actions button', { hasText: 'View battlefield' });
  await expect(view).toBeVisible();
  await p.page.waitForTimeout(1200);
  await view.tap();
  // Nothing is left to answer or pass, so the dock and the tools are hidden
  await expect(p.page.locator('#prompt')).toBeHidden();
  await expect(p.page.locator('#match > .tools')).toBeHidden();
  await p.page.locator('#game-over .to-result').tap({ timeout: 5000 });
  await expect(p.page.locator('#game-over .actions button', { hasText: 'Quit match' })).toBeVisible();
}, PHONE);

probe('an opponent\'s open zones do not cover their portrait, and their spell\'s list does not cover your cards', async p => {
  await settledBoard(p, 'humanbattlefield=Forest;Grizzly Bears\naihand=Hill Giant\naibattlefield=Mountain;Mountain;Mountain;Mountain\nactiveplayer=ai\nactivephase=UPKEEP');
  await p.page.locator('#opponent .zones-pill').tap();
  await expect(p.page.locator('#opponent .zone-tiles')).toBeVisible();
  const tiles = await box(p.page, '#opponent .zone-tiles');
  const face = await box(p.page, '#opponent .avatar');
  expect(tiles.y).toBeGreaterThanOrEqual(face.y + face.height - 1);
  await p.page.locator('#opponent .zones-pill').tap();
  await passUntil(p, 'the opponent casts', () => document.querySelectorAll('#stack .stack-item:not(.awaiting)').length > 0);
  await settled(p.page);
  const list = await box(p.page, '#stack');
  const mine = await box(p.page, '#me .battlefield .group.creatures .card');
  expect(list.y + list.height).toBeLessThanOrEqual(mine.y + 1);
  // The chip stays on the strip in whichever direction its list opens
  const head = await box(p.page, '#stack .head');
  const strip = await box(p.page, '#phase-strip');
  expect(Math.abs(head.y - strip.y)).toBeLessThan(8);
}, PHONE);

probe('the stack chip names a spell while its target is chosen', async p => {
  await settledBoard(p, 'humanhand=Lightning Bolt\nhumanbattlefield=Mountain\naibattlefield=Grizzly Bears');
  await castFromHand(p);
  await p.until('a target is asked for', () => (window.forge.model.prompt?.selectable.length ?? 0) > 0);
  await expect(p.page.locator('#stack .head b')).toContainText('Lightning Bolt');
}, PHONE);

probe('a block is declared by taps: the attacker, then your creature', async p => {
  await settledBoard(p, 'humanlife=5\nhumanbattlefield=Wall of Stone\naibattlefield=Craw Wurm;Hill Giant\nactiveplayer=ai\nactivephase=MAIN1');
  await passUntil(p, 'blockers are asked for', () => document.getElementById('match')!.classList.contains('declaring-blocks'), undefined, [], 90_000);
  const attackers = p.page.locator('#opponent .card.attacking');
  await expect(attackers).toHaveCount(2);
  // The game selects one attacker by default. A tap on the other attacker selects that one instead
  const other = p.page.locator('#opponent .card.attacking:not(.highlighted)');
  await expect(other).toHaveCount(1);
  const key = await other.getAttribute('data-key');
  await other.tap({ force: true });
  const picked = p.page.locator(`#opponent .card[data-key="${key}"]`);
  await expect(picked).toHaveClass(/highlighted/);
  await expect(picked.locator('.combat-tag')).toHaveText('Blocking this');
  await expect(p.page.locator('#zoom.sheet')).toHaveCount(0);
  await p.page.locator('#me .battlefield .card', { hasText: 'Wall of Stone' }).tap({ force: true });
  await expect(p.page.locator('#me .card.blocking')).toHaveCount(1);
}, PHONE);

probe('a zone the game opens for a choice, with a spell on the stack, lies on the dimmed board with cards large enough to tap', async p => {
  await settledBoard(p, 'humanhand=Diabolic Vision\nhumanbattlefield=Island;Swamp\nhumanlibrary=Forest;Mountain;Plains;Island;Swamp;Hill Giant;Grizzly Bears\naibattlefield=Mountain');
  await castFromHand(p);
  // Pay, then let the spell resolve. Send one answer at a time, because an early second answer would answer the choice
  const shown = () => p.page.evaluate(() => document.querySelectorAll('#zones .card.selectable').length > 0);
  for (let i = 0; i < 20 && !await shown(); i++) {
    if (await p.page.evaluate(() => { const q = window.forge.model.prompt; return !!q && (q.paying || q.priority) && !!q.ok?.enabled; })) await act(p.page, 'ok');
    await p.page.waitForTimeout(700);
  }
  expect(await shown()).toBe(true);
  await settled(p.page);
  // The dock's two buttons do nothing while the choice is open, so they are hidden to give the choice more room
  await expect(p.page.locator('#prompt .ok')).toBeHidden();
  await expect(p.page.locator('#prompt .cancel')).toBeHidden();
  await expect(p.page.locator('#match > .tools .more')).toBeVisible();
  await expect(p.page.locator('#prompt')).toBeHidden();
  // The dock row is hidden, so the dimmed board reaches the bottom of the screen. The question is inside it
  const dock = { y: 664 };
  const back = await box(p.page, '#zones');
  expect(back.width).toBe(390);
  expect(back.y + back.height).toBeGreaterThanOrEqual(dock.y - 2);
  const panel = await box(p.page, '#zones .zone-panel');
  expect(panel.x).toBeGreaterThanOrEqual(0);
  expect(panel.x + panel.width).toBeLessThanOrEqual(390);
  // At most two cards per row, so they are larger than the three per row of a sheet
  // The library's face-down cards are not rendered, so only the visible cards are measured
  const widths = await p.page.locator('#zones .cards .card:visible').evaluateAll(cards => cards.map(c => c.getBoundingClientRect().width));
  expect(Math.min(...widths)).toBeGreaterThan(125);
  // The stack's chip does not cover the question
  expect(await p.page.evaluate(() => { const r = document.querySelector('#zones .zone-panel header')!.getBoundingClientRect(); const hit = document.elementFromPoint(r.x + 20, r.y + 10); return !!hit?.closest('#zones'); })).toBe(true);
  for (const each of await p.page.locator('#zones .card.selectable').all()) await each.tap({ trial: true, timeout: 3000 });
  const card = p.page.locator('#zones .card.selectable').first();
  const at = (await card.boundingBox())!;
  expect(at.y + at.height).toBeLessThanOrEqual(dock.y + 2);
  await card.tap();
}, PHONE);

for (const [name, device] of [['a phone', PHONE], ['a desktop', undefined]] as const) {
  probe(`cards are put in order along a row on ${name}`, async p => {
    await settledBoard(p, 'humanhand=Diabolic Vision\nhumanbattlefield=Island;Swamp\nhumanlibrary=Forest;Mountain;Plains;Island;Swamp;Hill Giant;Grizzly Bears\naibattlefield=Mountain');
    await castFromHand(p);
    const asked = () => p.page.evaluate(() => document.querySelectorAll('#zones .card.selectable').length > 0);
    for (let i = 0; i < 20 && !await asked(); i++) {
      if (await p.page.evaluate(() => { const q = window.forge.model.prompt; return !!q && (q.paying || q.priority) && !!q.ok?.enabled; })) await act(p.page, 'ok');
      await p.page.waitForTimeout(700);
    }
    await p.page.locator('#zones .card.selectable').first().click();
    const items = p.page.locator('#dialog-layer .dialog .ordered-item');
    await expect(items).toHaveCount(4);
    await settled(p.page);
    // One row, in order from left to right
    const boxes = await Promise.all((await items.all()).map(async i => (await i.boundingBox())!));
    for (const b of boxes) expect(Math.abs(b.y - boxes[0].y)).toBeLessThan(2);
    expect(boxes[1].x).toBeGreaterThan(boxes[0].x);
    const width = p.page.viewportSize()!.width;
    expect(boxes[3].x + boxes[3].width).toBeLessThanOrEqual(width);
    // The third card is chosen and moved one place toward the front
    const third = await items.nth(2).locator('.card').getAttribute('data-key');
    await items.nth(2).locator('.card').click();
    await expect(items.nth(2)).toHaveClass(/selected/);
    await items.nth(2).locator('.order-nudge .earlier').click();
    await expect(items.nth(1).locator('.card')).toHaveAttribute('data-key', third!);
    await expect(items.nth(1).locator('.order-number')).toHaveText('2');
    // A card left in hover state by a tap still shows its number on top
    await items.nth(1).locator('.card').hover();
    await settled(p.page);
    const badge = (await items.nth(1).locator('.order-number').boundingBox())!;
    // The number ignores pointer events, so they are turned on briefly for the hit test
    expect(await items.nth(1).locator('.order-number').evaluate((n, [x, y]) => {
      (n as HTMLElement).style.pointerEvents = 'auto';
      const top = document.elementFromPoint(x, y);
      (n as HTMLElement).style.pointerEvents = '';
      return n.contains(top);
    }, [badge.x + badge.width / 2, badge.y + badge.height / 2])).toBe(true);
    await p.page.locator('#dialog-layer .dialog .actions button').last().click();
    await expect(p.page.locator('#dialog-layer .dialog')).toHaveCount(0);
  }, device);
}

probe('a drawer scrolled to its end leaves the strip showing the tops of the cards', async p => {
  await settledBoard(p, 'humanhand=Counterspell;Death Stroke;Giant Growth;Hill Giant;Shock;Island;Swamp;Forest;Mountain;Plains;Grizzly Bears;Lightning Bolt;Craw Wurm\nhumanbattlefield=Island\naibattlefield=Mountain');
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  await settled(p.page);
  // All cards of a large hand are visible at once or by scrolling
  expect((await p.page.locator('#hand.sheet .card').first().boundingBox())!.width).toBeLessThan(95);
  await p.page.evaluate(() => { document.getElementById('hand')!.scrollTop = 9999; });
  await p.page.locator('#hand-head .close').tap();
  await expect(p.page.locator('#hand.strip')).toBeVisible();
  await settled(p.page);
  const hand = await box(p.page, '#hand');
  const top = (await p.page.locator('#hand .card').first().boundingBox())!;
  expect(top.y).toBeGreaterThanOrEqual(hand.y);
  expect(top.y).toBeLessThan(hand.y + 30);
}, PHONE);

for (const [name, phone] of [['390', PHONE], ['360', SMALL_PHONE]] as const) {
  probe(`the fixed rows of a phone's board are one line each at ${name}`, async p => {
    await settledBoard(p, 'humanhand=Counterspell;Giant Growth;Shock\nhumanbattlefield=Island;Island;Grizzly Bears;Hill Giant\naibattlefield=Plains;Plains;Goblin Piker;Craw Wurm');
    for (const bar of ['#opponent .player', '#me .player']) expect((await box(p.page, bar)).height).toBeLessThanOrEqual(48);
    expect((await box(p.page, '#phase-strip .pill')).height).toBeLessThanOrEqual(34);
    expect((await box(p.page, '#prompt')).height).toBeLessThanOrEqual(46);
    // The tools are on your own bar, and each is still large enough to tap
    const bar = await box(p.page, '#me .player');
    for (const tool of ['.end-turn', '.auto-pass', '.undo', '.more']) {
      const at = await box(p.page, `#match > .tools ${tool}`);
      expect(at.y).toBeGreaterThanOrEqual(bar.y - 1);
      expect(at.y + at.height).toBeLessThanOrEqual(bar.y + bar.height + 1);
      expect(at.x + at.width).toBeLessThanOrEqual(phone.viewport!.width);
      expect(at.height).toBeGreaterThanOrEqual(44);
    }
    for (const tool of ['.auto-pass', '.more']) await p.page.locator(`#match > .tools ${tool}`).tap({ trial: true, timeout: 3000 });
    const counts = await box(p.page, '#me .zones-pill');
    expect(counts.x + counts.width).toBeLessThanOrEqual((await box(p.page, '#match > .tools')).x + 1);
    // The height the rows save goes to the cards
    const card = (await p.page.locator('#me .battlefield .card').first().boundingBox())!;
    expect(card.height).toBeGreaterThanOrEqual(78);
    expect(await p.page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(phone.viewport!.width);
  }, phone);
}

probe('the question of who goes first stops above the dock, which keeps the prompt\'s message', async p => {
  await settledBoard(p, 'humanbattlefield=Forest\naibattlefield=Mountain');
  await p.page.evaluate(() => {
    // The game's own toss may still be shown. Replace it with the asking form of the toss
    document.getElementById('coin-toss')?.remove();
    const ask = Object.assign(document.createElement('div'), { id: 'coin-toss', className: 'rested landed asking' });
    document.getElementById('match')!.append(ask);
  });
  const ask = await box(p.page, '#coin-toss');
  expect(ask.y + ask.height).toBeLessThanOrEqual((await box(p.page, '#prompt')).y + 1);
}, PHONE);

probe('a start tile\'s icon stands clear of its name', async p => {
  await p.page.goto(p.server.url);
  await p.page.locator('#player-name').fill('Alice');
  await p.page.keyboard.press('Enter');
  await expect(p.page.locator('.modes .mode.plaque').first()).toBeVisible();
  await settled(p.page);
  for (const tile of await p.page.locator('.modes .mode.plaque').all()) {
    const icon = (await tile.locator('.mode-art').boundingBox())!;
    const name = (await tile.locator('.mode-name').boundingBox())!;
    expect(icon.x + icon.width <= name.x || icon.y + icon.height <= name.y).toBe(true);
  }
}, PHONE);

probe('the board holds still behind the hand\'s drawer, and its cards keep to their half after it', async p => {
  await settledBoard(p, 'humanhand=Polluted Mire;Shock;Counterspell\nhumanbattlefield=Swamp;Swamp;Skittering Skirge\naibattlefield=Island;Island;Island;Grizzly Bears');
  const strip = await box(p.page, '#phase-strip');
  await p.page.locator('#hand').tap();
  await expect(p.page.locator('#hand.sheet')).toBeVisible();
  await settled(p.page);
  expect((await box(p.page, '#phase-strip')).y).toBe(strip.y);
  await p.page.locator('#hand .card').first().tap();
  await expect(p.page.locator('#dialog-layer .card-menu')).toBeVisible();
  await settled(p.page);
  const creature = await box(p.page, '#me .group.creatures .card');
  expect(creature.y).toBeGreaterThanOrEqual(strip.y + strip.height);
}, PHONE);
