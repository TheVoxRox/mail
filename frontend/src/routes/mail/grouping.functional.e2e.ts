import { expect, test, type Page } from '@playwright/test';
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

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		await expect(grid).toBeVisible();
		// The flat message list must not be mounted at the same time.
		await expect(page.getByRole('grid', { name: 'Seznam zpráv' })).toHaveCount(0);
		await expect(grid.locator('[role="row"][data-stable-id]').first()).toBeVisible();
	});

	test('otevření konverzace přejde na reprezentativní zprávu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/${encodeURIComponent(folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('treegrid', { name: 'Seznam konverzací' });
		const firstRow = grid.locator('[role="row"][data-stable-id]').first();
		await expect(firstRow).toBeVisible();
		const stableId = await firstRow.getAttribute('data-stable-id');
		await firstRow.click();

		await page.waitForURL(
			`**/mail/${accountId}/${encodeURIComponent(folderName)}/${encodeURIComponent(stableId ?? '')}`
		);
	});
});

// The ARCHIVE fixture holds one 3-message thread (arch-03 newest = the
// representative; arch-01/arch-02 are the folder-scoped members revealed on
// expand). No other test asserts ARCHIVE, so it is a free multi-message seam.
const archiveRow = (page: Page, stableId: string) =>
	page
		.getByRole('treegrid', { name: 'Seznam konverzací' })
		.locator(`[role="row"][data-stable-id="${stableId}"]`);

test.describe('Rozbalení konverzace', () => {
	test('kliknutí na šipku rozbalí a sbalí členy vlákna', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(parent.getByText('konverzace, 3 zprávy')).toBeAttached();
		// Members are hidden while collapsed.
		await expect(archiveRow(page, 'arch-01')).toHaveCount(0);
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		const member = archiveRow(page, 'arch-02');
		await expect(member).toBeVisible();
		await expect(member).toHaveAttribute('aria-level', '2');
		await expect(archiveRow(page, 'arch-01')).toBeVisible();

		await parent.locator('[data-expand-toggle]').click();

		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
	});

	test('šipky na předmětu rozbalí a sbalí konverzaci', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		const subjectCell = parent.locator('[data-cell-target][data-col="2"]');
		await subjectCell.focus();

		await subjectCell.press('ArrowRight');
		await expect(parent).toHaveAttribute('aria-expanded', 'true');
		await expect(archiveRow(page, 'arch-02')).toBeVisible();

		await parent.locator('[data-cell-target][data-col="2"]').press('ArrowLeft');
		await expect(parent).toHaveAttribute('aria-expanded', 'false');
		await expect(archiveRow(page, 'arch-02')).toHaveCount(0);
	});

	test('otevření člena rozbaleného vlákna přejde na jeho zprávu', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('[data-expand-toggle]').click();

		const member = archiveRow(page, 'arch-02');
		await expect(member).toBeVisible();
		await member.click();

		await page.waitForURL(`**/mail/${accountId}/ARCHIVE/arch-02`);
	});
});

test.describe('Hromadné akce nad konverzacemi', () => {
	test('smazání vybrané konverzace zasáhne celé vlákno', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await expect(toolbar.getByText('1 vybraná konverzace')).toBeVisible();
		await toolbar.getByRole('button', { name: 'Smazat vybrané' }).click();

		// ARCHIVE is not the trash folder, so the delete moves the whole thread
		// (all three members) out — the folder empties and the treegrid unmounts.
		await expect(page.getByRole('treegrid', { name: 'Seznam konverzací' })).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
	});

	test('přesun vybrané konverzace přesune celé vlákno', async ({ page }) => {
		await page.goto(`/mail/${accountId}/ARCHIVE`);
		await waitForShell(page);

		const parent = archiveRow(page, 'arch-03');
		await expect(parent).toBeVisible();
		await parent.locator('input[type="checkbox"]').check();

		const toolbar = page.getByRole('toolbar', { name: 'Hromadné akce' });
		await toolbar.getByRole('button', { name: 'Přesunout vybrané' }).click();
		await page.getByRole('menuitem', { name: 'Spam', exact: true }).click();

		await expect(page.getByRole('treegrid', { name: 'Seznam konverzací' })).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="arch-03"]')).toHaveCount(0);
	});
});
