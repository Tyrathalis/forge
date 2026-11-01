import { expect, test } from '@playwright/test';
import { startServer, type Server } from './server';
import { chooseDeck, concede, enterName, hostTable } from './steps';

let server: Server;
test.beforeEach(async () => { server = await startServer(); });
test.afterEach(async () => { await server.stop(); });

test('a new player names themselves, hosts a game against the computer, and plays it', async ({ page }) => {
  await page.goto(server.url);
  await expect(page.getByText('What should we call you?')).toBeVisible();
  await enterName(page, 'Alice');

  await hostTable(page, false);
  const seats = page.locator('#seats .plate');
  await expect(seats).toHaveCount(2);
  // Escape closes the deck finder from its search box without choosing anything
  await seats.nth(0).locator('.sleeve').click();
  await expect(page.locator('.finder .find')).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(page.locator('.finder')).toHaveCount(0);

  await chooseDeck(page, seats.nth(0));
  await chooseDeck(page, seats.nth(1));
  await expect(page.locator('#play')).toBeEnabled();
  const decks = await seats.locator('.deck-name').allInnerTexts();

  // As the lobby says, Enter starts the match
  await page.keyboard.press('Enter');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me')).toContainText('Alice');
  await expect(page.locator('#prompt .message')).not.toBeEmpty();

  // A reload returns to the same match under the same name, without asking for the name again
  await page.reload({ waitUntil: 'domcontentloaded' });
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#player-name')).toHaveCount(0);
  await expect(page.locator('#me')).toContainText('Alice');

  // Quitting a finished game opens a new table with the last table's settings. A second match starts without choosing anything again
  await concede(page);
  await page.locator('#game-over button', { hasText: 'Quit match' }).click();
  await expect(page.locator('#lobby')).toBeVisible();
  await expect(seats.locator('.deck-name')).toHaveText(decks);
  await expect(page.locator('#play')).toBeEnabled();
  await page.keyboard.press('Enter');
  await expect(page.locator('#match')).toBeVisible();
  await expect(page.locator('#me')).toContainText('Alice');
});
