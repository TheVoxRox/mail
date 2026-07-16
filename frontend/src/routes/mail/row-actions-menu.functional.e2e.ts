import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/**
 * Row actions menu ("Akce pro zprávu …") in the message list. Delete removes
 * the row that owns the menu, which must not leave the menu itself behind.
 */
const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-04',
	subject: 'Testovací zpráva 4'
};

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Řádkové menu Akce', () => {
	test('Smazat z řádkového menu smaže zprávu a menu se zavře', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();

		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		// The message is gone from the list…
		await expect(row).toHaveCount(0);
		// …and the menu closed with it instead of lingering as an orphan.
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu klávesnicí smaže zprávu a menu se zavře', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		// The keyboard path: open the menu with Enter on the trigger and walk to
		// Delete with the menu's own arrow-key navigation (bits-ui highlight),
		// exactly like a keyboard user — no programmatic focus of the item.
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();
		await page.keyboard.press('Enter');
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();

		// `loop` wraps ArrowUp from the top straight to the last item = Smazat.
		await page.keyboard.press('ArrowUp');
		await expect(menu.getByRole('menuitem', { name: 'Smazat' })).toBeFocused();
		await page.keyboard.press('Enter');

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu v koši: potvrzovací dialog, smazání a menu zavřené', async ({
		page
	}) => {
		await page.goto(`/mail/${fixture.accountId}/TRASH`);
		await waitForShell(page);

		const row = page.locator('[role="row"][data-stable-id="trash-01"]');
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: 'Akce pro zprávu Smazaný e-mail 1' }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Trvalé smazání' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat trvale' }).click();

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu s pomalým backendem: menu se zavře hned, fokus skončí na sousedním řádku', async ({
		page
	}) => {
		// The real IMAP delete takes hundreds of ms; MSW answers instantly. Delay
		// the DELETE so the row outlives the menu-close sequence like in the app.
		// Patched via window.fetch: Playwright routing does not see requests the
		// MSW service worker answers, but the page's own fetch runs before the SW.
		await page.addInitScript(() => {
			const origFetch = window.fetch;
			window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
				const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
				const url =
					typeof input === 'string' ? input : input instanceof Request ? input.url : String(input);
				if (method === 'DELETE' && /\/api\/v1\/messages\/[^/]+$/.test(url)) {
					await new Promise((resolve) => setTimeout(resolve, 800));
				}
				return origFetch(input, init);
			};
		});

		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		// The menu must close on select, before the backend answers…
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(row).toBeVisible();
		// …then the row disappears once the delete lands, and focus moves on to a
		// neighbouring row instead of dying with the removed trigger.
		await expect(row).toHaveCount(0);
		await expect(page.locator('[role="row"] [data-cell-target]:focus')).toHaveCount(1);
	});

	test('po smazání z řádkového menu pokračují šipky v seznamu místo otevírání menu', async ({
		page
	}) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();
		await expect(row).toHaveCount(0);

		// Focus lands on the neighbouring row's subject cell — not on its Akce
		// trigger, where the next ArrowDown used to pop that row's menu open.
		const focused = page.locator('[data-cell-target]:focus');
		await expect(focused).toHaveAttribute('data-col', '2');

		await page.keyboard.press('ArrowDown');
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '2');
	});

	test('šipka dolů na tlačítku Akce neotevře menu, ale naviguje v gridu', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();

		// Grid semantics: ArrowDown moves to the next row's actions cell instead
		// of acting as a menubutton open key.
		await page.keyboard.press('ArrowDown');
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '5');

		// Enter still opens the (now focused) neighbour's menu.
		await page.keyboard.press('Enter');
		await expect(page.getByRole('menu')).toBeVisible();
	});

	test('Smazat z řádkového menu v režimu bez podokna čtení zavře menu', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'off');
		});
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});
});
