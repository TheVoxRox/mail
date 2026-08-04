import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

const accountId = 1;
const folderName = 'INBOX';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		// Opt into the conversation-grouped folder view (Phase 2).
		window.localStorage.setItem('mail.messageGrouping', 'grouped');
	});
});

test.describe('Konverzační seskupení', () => {
	test('seskupený režim vykreslí seznam konverzací místo plochého seznamu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam konverzací' });
		await expect(grid).toBeVisible();
		// The flat message list must not be mounted at the same time.
		await expect(page.getByRole('grid', { name: 'Seznam zpráv' })).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id]').first()).toBeVisible();
	});

	test('dvojklik otevře konverzaci na reprezentativní zprávě', async ({ page }) => {
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		// Deliberate open = double click, mirroring the flat list (single click is
		// the mouse twin of an Arrow key).
		await firstRow.dblclick();

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
	});

	test('jednoklik bez podokna čtení jen přesune fokus, konverzaci neotevře', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'off');
		});
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
		await expect(firstRow).toBeVisible();
		await firstRow.click();

		// No pane to preview into, so the click only moves the roving focus onto
		// the subject cell — the conversation opens on Enter or a double click.
		await expect(firstRow.locator('[data-col="1"]')).toBeFocused();
		await expect(page).toHaveURL(new RegExp(`/mail/${accountId}/${folderName}$`));
		await expect(grid).toBeVisible();
	});

	test('jednoklik ve split režimu ukáže konverzaci, ale fokus nechá na řádku', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		await firstRow.click();

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
		// Selection followed the click into the reading pane, but the reading
		// cursor stays in the list so the next Arrow key keeps navigating.
		await expect(firstRow.locator('[data-col="1"]')).toBeFocused();
	});
});
