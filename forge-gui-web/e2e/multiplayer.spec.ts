import { expect, test, type Browser, type Page } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, enterName, hostTable, inviteLink, say } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

/** Opens a table others can join and seats a guest at it. Both pages end in match setup. */
async function hostAndGuest(page: Page, browser: Browser): Promise<Page> {
  await page.goto(server.url);
  await enterName(page, 'Alice');
  await hostTable(page, true);
  const guest = await (await browser.newContext()).newPage();
  await guest.goto(await inviteLink(page, server.url));
  // All browsers use the same server, and two players with the same name cannot join one game
  await enterName(guest, 'alice');
  await expect(guest.locator('.menu-note')).toContainText('already called');
  await enterName(guest, 'Bea');
  await expect(guest.locator('#lobby')).toBeVisible();
  await expect(page.locator('#seats')).toContainText('Bea');
  return guest;
}

async function startMatch(page: Page, guest: Page): Promise<void> {
  await chooseDeck(page, page.locator('.plate.mine'));
  await chooseDeck(guest, guest.locator('.plate.mine'));
  // With another player seated, a deck alone does not make a seat ready. Each player must press Ready
  await expect(page.locator('#play')).toBeDisabled();
  await page.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeDisabled();
  await guest.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeEnabled();
  // Choosing a new deck clears the player's Ready
  await chooseDeck(guest, guest.locator('.plate.mine'));
  await expect(page.locator('#play')).toBeDisabled();
  await guest.click('.plate.mine .ready-toggle');
  await expect(page.locator('#play')).toBeEnabled();
  await page.click('#play');
  await expect(page.locator('#match')).toBeVisible();
  await expect(guest.locator('#match')).toBeVisible();
}

test('a guest joins by link under a name of its own and follows the host into the match', async ({ page, browser }) => {
  const guest = await hostAndGuest(page, browser);
  // Outside a match the chat is collapsed to a bar at the bottom edge until it is opened
  for (const p of [page, guest]) {
    await p.click('#dock .dock.folded');
  }
  await say(guest, '#dock .dock-say input', 'hello from Bea');
  await expect(page.locator('#dock .dock-log')).toContainText('hello from Bea');

  // A reload in match setup returns to the same seat
  await guest.reload({ waitUntil: 'domcontentloaded' });
  await expect(guest.locator('.plate.mine')).toContainText('Bea');

  await startMatch(page, guest);
  await expect(page.locator('#me')).toContainText('Alice');
  await expect(guest.locator('#me')).toContainText('Bea');
  // In a match others can join, the chat dock is already open under the log
  await say(page, '#match-chat .dock-say input', 'good luck');
  await expect(guest.locator('#match-chat .dock-log')).toContainText('good luck');
});
