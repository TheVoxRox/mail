import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Off-mode list keyboard model (SR audit findings 1+2): with the reading pane
 * off there is no pane that could follow the selection — a row change on
 * Arrow/Page keys must only move the roving focus, and only Enter/Space (or a
 * click) opens the message. Without this, arrowing through the list tears a
 * screen-reader user out of the list into the detail route. Delete must hand
 * focus to a neighbouring row instead of dropping it on <body>.
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'off');
	});
});

test.describe('Seznam zpráv v režimu bez podokna čtení', () => {
	test('šipka dolů jen přesune fokus, zprávu otevře až Enter', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		await page.keyboard.press('ArrowDown');

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(secondSubject).toBeFocused();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);
		await expect(page.getByRole('grid', { name: 'Seznam zpráv' })).toBeVisible();

		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-02');
	});

	test('PageDown a Home v seznamu neotevírají zprávy', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		await page.keyboard.press('PageDown');
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);

		await page.keyboard.press('Control+Home');
		await expect(firstSubject).toBeFocused();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);
	});

	test('Delete vrátí fokus na sousední řádek', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(secondSubject).toBeVisible();
		await secondSubject.focus();

		await page.keyboard.press('Delete');

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		const thirdSubject = page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]');
		await expect(thirdSubject).toBeFocused();
	});
});

test.describe('Přepnutí složky', () => {
	test('přepnutí složky ohlásí načtený seznam do live regionu', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);
		await expect(page.locator('[role="row"][data-stable-id="msg-01"]')).toBeVisible();

		// Focus stays on the sidebar folder link while the list swaps —
		// announce the loaded page (the initial folder load stays quiet).
		await page.getByRole('link', { name: 'Odeslané' }).click();
		await page.waitForURL('**/mail/1/SENT');
		await expect(page.locator('#live-region')).toContainText('Strana 1 z 1, 1 zpráva');
	});
});

test.describe('Seznam zpráv ve split režimu', () => {
	test('Delete na neotevřeném řádku neztratí fokus a nechá detail otevřený', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// Open msg-01 in the reading pane, then move focus back to another row.
		await page.locator('[role="row"][data-stable-id="msg-01"]').click();
		await page.waitForURL('**/mail/1/INBOX/msg-01');
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await secondSubject.focus();
		await page.keyboard.press('Delete');

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		const thirdSubject = page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]');
		await expect(thirdSubject).toBeFocused();
		// The open message was not the deleted one — the detail must stay.
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX\/msg-01$/);
	});
});
