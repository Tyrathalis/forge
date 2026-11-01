import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, test, type Page } from '@playwright/test';
import { PROBE_SEED, startServer, type Server } from './server';
import { answerDialogs, enterName } from './steps';

const fixture = join(dirname(fileURLToPath(import.meta.url)), '../src/test/resources/conquest/Fixture_conquest');
const deck = readdirSync(join(fixture, 'decks'))[0];
const saved = 'conquest/saves/Fixture_conquest';

let server: Server;
test.beforeEach(async () => {
  server = await startServer(undefined, {
    ...PROBE_SEED,
    files: {
      [`${saved}/data.xml`]: readFileSync(join(fixture, 'data.xml'), 'utf8'),
      [`${saved}/decks/${deck}`]: readFileSync(join(fixture, 'decks', deck), 'utf8'),
    },
  });
});
test.afterEach(async () => { await server.stop(); });

/** Opens the fixture's conquest from the shelf. */
async function playFixture(page: Page): Promise<void> {
  await page.locator('.cq-save', { hasText: 'Fixture conquest' }).getByRole('button', { name: 'Play' }).click();
}

/** Plays up to the player's first priority. It keeps the opening hand and gives any other question its first answer. */
async function toPriority(page: Page): Promise<void> {
  await expect(async () => {
    await answerDialogs(page);
    const prompt = await page.evaluate(() => (window as any).forge.model.prompt);
    if (prompt && !prompt.priority && prompt.ok?.enabled) await page.evaluate(() => (window as any).forge.actions.ok());
    expect(prompt?.priority).toBe(true);
  }).toPass({ timeout: 90_000, intervals: [500] });
}

// Fails if a battle cannot be reached from the map, its reward cannot be claimed to the end, or the win is not shown as conquered
test('a conquest battle is fought and comes back to the map', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await playFixture(page);

  // The fixture's first event is already won, so its two neighbours are open
  await expect(page.locator('.cq-tile.won')).toHaveCount(1);
  await expect(page.locator('.cq-tile.open')).toHaveCount(2);
  await page.locator('.cq-tile.open').first().click();
  await expect(page.locator('.cq-steps')).toHaveText('1 step away');
  // The opponent's picture previews on hover
  await page.locator('.cq-side .cq-art').hover();
  await expect(page.locator('#zoom')).toBeVisible();
  await page.mouse.move(0, 0);
  await expect(page.locator('#zoom')).toBeHidden();
  // The marker moves to the event before the battle starts. Part way through its one step it is between the two tiles
  const centre = async () => { const b = (await page.locator('.cq-you').boundingBox())!; return b.x + b.y; };
  const from = await centre();
  await page.getByRole('button', { name: 'Battle' }).click();
  await expect(async () => {
    const now = await centre();
    expect(now).toBeGreaterThan(from + 10);
    expect(now).toBeLessThan(from + 104);
  }).toPass({ timeout: 2000, intervals: [30] });

  await expect(page.locator('#match')).toBeVisible({ timeout: 60_000 });
  await page.evaluate(() => (window as any).forge.actions.devConquestWheel('BOOSTER'));
  await toPriority(page);
  await page.evaluate(() => (window as any).forge.actions.dev('winGame'));
  // The player in the battle is the seat's player, so the board shows the win as theirs
  await expect(page.locator('#game-over .word')).toHaveText('Victory');
  await page.locator('#game-over button', { hasText: 'Great' }).click();

  // The bar does not count the first conquest's emblem until the reveal shows it, together with the wheel
  const reveal = page.locator('.cq-reveal');
  await expect(reveal.locator('.cq-wheel-first')).toBeVisible();
  // Nothing behind the reveal can be used, so the player must go through the whole reward
  const back = page.locator('.cq-purse button', { hasText: 'Back' });
  // The page behind is locked one frame after the reveal opens, so this check is retried
  await expect(async () => {
    await back.focus();
    expect(await back.evaluate(el => el === document.activeElement)).toBe(false);
  }).toPass({ timeout: 2000 });
  // The balances stay visible above the reveal, and they now include the first conquest's emblem
  const emblems = page.locator('.cq-purse .cq-coin').nth(1);
  await expect(emblems.locator('b')).toHaveText('2');
  const layer = (selector: string) => page.locator(selector).evaluate(el => Number(getComputedStyle(el).zIndex));
  expect(await layer('.cq-purse')).toBeGreaterThan(await layer('.cq-reveal'));
  // The OK key spins the wheel, and its button shows that key
  await expect(reveal.getByRole('button', { name: 'Spin' }).locator('kbd')).toHaveText('Space');
  await page.keyboard.press(' ');
  await expect(reveal.locator('.cq-dl.done')).toBeVisible({ timeout: 15_000 });
  // The OK key also takes each step's default button after the spin
  await expect(reveal.getByRole('button', { name: 'Great Space', exact: true })).toBeVisible();
  await page.keyboard.press(' ');

  // The reward is a booster. The pack is opened and skipped, every card turns face up, and the reveal closes to the map
  await reveal.locator('.cq-pk').click({ force: true });
  await reveal.getByRole('button', { name: 'Skip' }).click();
  await expect(reveal.getByRole('button', { name: 'Great Space', exact: true })).toBeVisible({ timeout: 30_000 });
  const cards = await reveal.locator('.cq-rv-card').count();
  expect(cards).toBeGreaterThan(0);
  await expect(reveal.locator('.cq-rv-card.up')).toHaveCount(cards);
  // Fails if a card skipped into place keeps its finished flight, which blocks the hover preview
  await expect(page.locator('#zoom')).toBeHidden();
  await reveal.locator('.cq-rv-card.up').first().hover();
  await expect(page.locator('#zoom')).toBeVisible();
  await reveal.getByRole('button', { name: 'Great Space', exact: true }).click();
  await expect(reveal).toHaveCount(0);
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);

  // A reload shows the same map and does not offer the reward again
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('.cq-tile.won')).toHaveCount(2);
  // A pending reward is sent after the map, so wait long enough for it before checking it is absent
  await page.waitForTimeout(1500);
  await expect(page.locator('.cq-reveal')).toHaveCount(0);
});

// Fails if a commander's deck cannot be edited with the conquest's cards, or a collection card cannot be exiled for its price
test('a deck is edited and a card is exiled', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await playFixture(page);
  await page.locator('.cq-tab', { hasText: 'Commanders' }).click();
  await expect(page.locator('.cq-cmd[aria-pressed=true]')).toContainText('40 cards');
  await page.getByRole('button', { name: 'Edit Deck' }).click();

  await expect(page.locator('.editor-head .deck-owner')).toContainText('Fixture conquest');
  await expect(page.locator('.main-zone > h4 .count')).toHaveText('40');
  await page.locator('.catalogue .slot:not(.indeck) .tile').first().click();
  await expect(page.locator('.main-zone > h4 .count')).toHaveText('41');
  await page.getByRole('button', { name: 'Done' }).click();
  await expect(page.locator('.cq-cmd[aria-pressed=true]')).toContainText('41 cards');

  await page.locator('.cq-tab', { hasText: 'Collection' }).click();
  const free = page.locator('.cq-cc:not(.used)').first();
  const value = Number((await free.locator('.val').innerText()).replace(/\D/g, ''));
  expect(value).toBeGreaterThan(0);
  await free.click();
  await page.locator('.cq-sel-bar .primary').click();
  await page.locator('.cq-exile').getByRole('button', { name: 'OK' }).click();
  await expect(page.locator('.cq-coll-bar .seg button').nth(1)).toContainText('(1)');
  await expect(page.locator('.cq-purse')).toContainText((3000 + value).toLocaleString('en-GB'));
});

// Fails if a conquest cannot be started from the form and opened on its map, or cannot then be renamed and deleted
test('a conquest is started, renamed and deleted', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await page.locator('.ev.new').click();
  await page.locator('.cq-plane').first().click();
  await page.locator('.cq-pick-row').first().click();
  await expect(page.locator('.stp.done')).toHaveCount(2);
  await page.locator('.cq-pick-row').first().click();
  // Start is disabled until a name is entered, and the conquest gets the name as typed
  await expect(page.locator('.ticket-foot .primary')).toBeDisabled();
  await page.locator('.stp-open input[type=text]').fill('Probe conquest');
  await page.locator('.ticket-foot .primary').click();
  await expect(page.locator('.cq-id b')).toHaveText('Probe conquest', { timeout: 30_000 });
  await expect(page.locator('.cq-tile.open')).toHaveCount(1);

  await page.getByRole('button', { name: 'Back', exact: true }).click();
  const card = (name: string) => page.locator('.cq-save', { hasText: name });
  await card('Probe conquest').locator('.more').click();
  await page.getByRole('menuitem', { name: 'Rename' }).click();
  await card('Rename').locator('input').fill('Probe renamed');
  await card('Rename').getByRole('button', { name: 'Rename' }).click();
  await expect(card('Probe renamed')).toHaveCount(1);
  await card('Probe renamed').locator('.more').click();
  await page.getByRole('menuitem', { name: 'Delete' }).click();
  await card('Probe renamed').getByRole('button', { name: 'Delete' }).click();
  await expect(card('Probe renamed')).toHaveCount(0);
  await expect(card('Fixture conquest')).toHaveCount(1);
});

// Fails if a card cannot be pulled from the Aether at its price, or the planes, statistics or preferences are not shown
test('the Aether, the planes, the statistics and the preferences open', async ({ page }) => {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await playFixture(page);

  const collection = page.locator('.cq-tab[data-tab="collection"]');
  await page.locator('.cq-tab', { hasText: 'The Aether' }).click();
  await expect(collection.locator('.new-card')).toHaveCount(0);
  const pull = page.locator('.cq-ae-pull');
  const cost = Number((await pull.innerText()).replace(/\D/g, ''));
  expect(cost).toBeGreaterThan(0);
  await pull.click();
  await expect(page.locator('.cq-ae-card b')).not.toBeEmpty();
  await expect(page.locator('.cq-purse')).toContainText((3000 - cost).toLocaleString('en-GB'));
  await expect(page.locator('.cq-ae-recent img')).toHaveCount(1);
  // The pulled card previews on hover. A click sends it to the collection and shows the orb again
  await page.locator('.cq-ae-face img').hover();
  await expect(page.locator('#zoom')).toBeVisible();
  await page.locator('.cq-ae-face').click();
  await expect(page.locator('.cq-ae-core .cq-ae-orb')).toBeVisible();
  await expect(page.locator('.cq-ae-recent img')).toHaveCount(1);
  // The pulled card marks the Collection tab as new until the tab is opened
  await expect(collection.locator('.new-card')).toBeVisible();
  await collection.click();
  await expect(collection.locator('.new-card')).toHaveCount(0);
  await page.locator('.cq-tab', { hasText: 'The Aether' }).click();
  await expect(collection.locator('.new-card')).toHaveCount(0);

  // A price changed in the preferences is shown on the page behind them once they close
  await page.getByRole('button', { name: 'Preferences' }).click();
  await page.locator('.cq-pref', { hasText: 'Base Pull Cost' }).locator('input').fill(String(cost + 50));
  await page.locator('.cq-pref', { hasText: 'Base Pull Cost' }).locator('input').blur();
  await page.locator('.cq-prefs').getByRole('button', { name: 'OK' }).click();
  await expect(page.locator('.cq-ae-pull')).toContainText(String(cost + 50));

  await page.locator('.cq-tab', { hasText: 'Planeswalk' }).click();
  await expect(page.locator('.cq-plane-card:not(.locked)')).toHaveCount(1);
  await expect(page.locator('.cq-plane-card:not(.locked)')).toContainText('Zendikar');
  await page.locator('.cq-plane-card.locked').first().click();
  // One emblem is not enough for a plane, so the button shows the balance against the cost and is disabled
  await expect(page.locator('.cq-planes .cq-foot button')).toBeDisabled();

  await page.locator('.cq-tab', { hasText: 'Statistics' }).click();
  await expect(page.locator('.cq-figure')).toHaveCount(8);
  await expect(page.locator('.cq-tables table').first()).toContainText('1 / 9');

  await page.getByRole('button', { name: 'Preferences' }).click();
  await expect(page.locator('.cq-pref')).toHaveCount(20);
  await page.locator('.cq-prefs').getByRole('button', { name: 'OK' }).click();
  await expect(page.locator('.cq-prefs')).toHaveCount(0);
});

// Fails if the phone header loses the balances, the chat or the menu's Options, the map's event card does not open from its title, or Back does not leave the save
test('an open conquest on a phone has one header and a folding event card', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 664 });
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await page.click('[data-mode=play]');
  await page.click('.chooser [data-kind=conquest]');
  await playFixture(page);

  const head = page.locator('.ph');
  await expect(head).toHaveCount(1);
  await expect(head.locator('.ph-line .cq-coin')).toHaveCount(2);
  await expect(head.locator('.ph-chat .dock.folded')).toBeVisible();
  await head.getByRole('button', { name: 'More' }).click();
  await page.getByRole('menuitem', { name: 'Options' }).click();
  await expect(page.locator('.options-dialog')).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(page.locator('.options-dialog')).toHaveCount(0);

  await page.locator('.cq-tile.open').first().click();
  const art = page.locator('.cq-side .cq-art');
  await expect(art).toBeHidden();
  await page.locator('.cq-fold').click();
  await expect(art).toBeVisible();

  await head.getByRole('button', { name: 'Back' }).click();
  await expect(page.locator('.cq-save', { hasText: 'Fixture conquest' })).toBeVisible();
});
