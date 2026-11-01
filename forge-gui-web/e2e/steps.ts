// Player actions, named with the page's own words. The specs read as the steps a person would take.

import { expect, type Locator, type Page } from '@playwright/test';

/** Answers the name prompt. The first browser to answer becomes the host, because the host seat option starts ticked. */
export async function enterName(page: Page, name: string): Promise<void> {
  await page.fill('#player-name', name);
  await page.keyboard.press('Enter');
}

/** Opens a table from the host's menu. The table is against the computer, or one that others can join by link. */
export async function hostTable(page: Page, invite: boolean): Promise<void> {
  await page.click(invite ? '[data-mode=multiplayer]' : '[data-mode=play]');
  await page.click('.chooser [data-kind=constructed]');
  await expect(page.locator('#seats .plate').first()).toBeVisible();
}

/** Chooses the first legal deck for a seat in the deck finder. The finder shows only legal decks when it opens. */
export async function chooseDeck(page: Page, plate: Locator): Promise<void> {
  await plate.locator('.sleeve').click();
  await page.locator('.dk-hit:not(.generated)').first().dblclick();
  await expect(page.locator('.finder')).toHaveCount(0);
  await expect(plate.locator('.deck-name')).not.toHaveText('');
}

/** Reads the guest address from the header's Invite menu and points it at this machine. */
export async function inviteLink(page: Page, serverUrl: string): Promise<string> {
  // On a phone the button is an icon in the phone header, named by its label
  await page.locator(':is(.page-head, .ph)').getByRole('button', { name: 'Invite' }).click();
  await expect(page.locator('.share-url').first()).toBeVisible();
  const shared = await page.locator('.share-url').first().textContent();
  await page.keyboard.press('Escape');
  return (shared ?? '').replace(/^https?:\/\/[^/]+/, new URL(serverUrl).origin);
}

/** Closes cards that are only revealed, then answers each dialog with its first choice until none is open. */
export async function answerDialogs(page: Page): Promise<void> {
  const dialog = page.locator('#dialog-layer .dialog');
  const reveal = page.locator('#dialog-layer .reveal-panel');
  for (let i = 0; i < 10 && await reveal.count(); i++) {
    await reveal.locator('.zone-answer.ok').click();
    await page.waitForTimeout(300);
  }
  for (let i = 0; i < 10 && await dialog.count(); i++) {
    const choice = dialog.locator('.options .card, .options .text-option').first();
    if (await choice.count()) await choice.click();
    await dialog.locator('.actions button').last().click();
    await page.waitForTimeout(300);
  }
}

/** Waits for the game's first question. It closes the list of cards the AI plays poorly, because the game waits for it. */
export async function gameStarted(page: Page): Promise<void> {
  await expect(async () => {
    const reveal = page.locator('#dialog-layer .reveal-panel .zone-answer.ok');
    if (await reveal.count()) await reveal.click();
    await expect(page.locator('#prompt .message')).not.toBeEmpty({ timeout: 1000 });
  }).toPass({ timeout: 60_000 });
}

/** Concedes from the game menu and confirms the question it asks. */
export async function concede(page: Page): Promise<void> {
  await answerDialogs(page);
  await page.click('#prompt .more');
  await page.locator('.game-menu .concede').click();
  await page.locator('[role=alertdialog] .danger').click();
  await expect(page.locator('#game-over')).toBeVisible();
}

export async function say(page: Page, input: string, text: string): Promise<void> {
  await page.fill(input, text);
  await page.press(input, 'Enter');
}

/** Builds the open limited deck to forty cards. It adds basic lands to reach forty, because a short deck is refused when legality is enforced. */
export async function buildLimitedDeck(page: Page): Promise<void> {
  const sizes = page.locator('.deck-head .sizes');
  const tiles = page.locator('.cat-grid .slot .tile');
  for (let i = 0; i < 23; i++) {
    await tiles.nth(i).click();
    await expect(sizes).toContainText(`${i + 1} cards`);
  }
  await page.click('.land-row button:has-text("Suggest")');
  const size = async () => Number(/^(\d+) cards/.exec(await sizes.innerText())?.[1] ?? 0);
  await expect.poll(size).toBeGreaterThan(23);
  while (await size() < 40) {
    const before = await size();
    await page.locator('.land-row .land button[aria-label^="One more"]').first().click();
    await expect.poll(size).toBeGreaterThan(before);
  }
}

/** Holds a finger on an element, which Playwright's tap cannot do. Chromium only. */
export async function hold(page: Page, target: Locator, ms = 650): Promise<void> {
  const box = await target.boundingBox();
  if (!box) throw new Error('Nothing to hold: the element is not on the page.');
  const x = Math.round(box.x + box.width / 2);
  const y = Math.round(box.y + box.height / 2);
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
  await page.waitForTimeout(ms);
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await cdp.detach();
}
