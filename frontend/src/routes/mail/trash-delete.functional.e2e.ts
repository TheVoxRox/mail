import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/**
 * Delete in the trash folder is a permanent delete (server-side expunge) and
 * must be confirmed — a trash→trash move used to be a server no-op that
 * resurrected the messages on the next sync.
 */
const fixture = {
	accountId: 1,
	trashFolder: 'TRASH',
	inboxFolder: 'INBOX',
	trashIds: ['trash-01', 'trash-02'],
	inboxId: 'msg-03'
};

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Trvalé mazání v koši', () => {
	test('vybrat vše + smazat v koši vyžádá potvrzení; zrušení nic nesmaže a dialog jde otevřít znovu', async ({
		page
	}) => {
		const deleteRequests: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && /\/api\/v1\/messages\/[^/]+$/.test(request.url())) {
				deleteRequests.push(request.url());
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${fixture.trashFolder}`);
		await waitForShell(page);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.trashIds[0]}"]`)
		).toBeVisible();

		await page.getByRole('checkbox', { name: 'Vybrat vše' }).check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		const dialog = page.getByRole('dialog', { name: 'Trvalé smazání' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Trvale smazat 2 zprávy z koše?');
		await dialog.getByRole('button', { name: 'Zrušit' }).click();
		await expect(dialog).not.toBeVisible();

		// Nothing was deleted and the UI stays operable after the cancel.
		expect(deleteRequests).toEqual([]);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.trashIds[0]}"]`)
		).toBeVisible();
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.trashIds[1]}"]`)
		).toBeVisible();

		// Re-open the dialog and confirm this time.
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat trvale' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Smazáno: 2, selhalo: 0.')
		).toBeVisible();
		await expect(page.locator(`[role="row"][data-stable-id="${fixture.trashIds[0]}"]`)).toHaveCount(
			0
		);
		await expect(page.locator(`[role="row"][data-stable-id="${fixture.trashIds[1]}"]`)).toHaveCount(
			0
		);
		expect(deleteRequests).toHaveLength(2);
	});

	test('smazání otevřené zprávy z toolbaru detailu v koši vyžádá potvrzení', async ({ page }) => {
		const deleteRequests: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && /\/api\/v1\/messages\/[^/]+$/.test(request.url())) {
				deleteRequests.push(request.url());
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${fixture.trashFolder}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.trashIds[0]}"]`);
		await expect(row).toBeVisible();
		await row.click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${fixture.trashFolder}/${fixture.trashIds[0]}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Smazat', exact: true }).click();

		const dialog = page.getByRole('dialog', { name: 'Trvalé smazání' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat trvale' }).click();

		await expect(row).toHaveCount(0);
		expect(deleteRequests).toHaveLength(1);
	});

	test('smazání v doručené poště se nepotvrzuje — rovnou přesun do koše', async ({ page }) => {
		const deleteRequests: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && /\/api\/v1\/messages\/[^/]+$/.test(request.url())) {
				deleteRequests.push(request.url());
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${fixture.inboxFolder}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.inboxId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('checkbox').check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		// No confirmation dialog outside the trash; the delete fires directly.
		// A single-item bulk action reports via the named single-message toast.
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Zpráva smazána: Testovací zpráva 3')
		).toBeVisible();
		await expect(page.getByRole('dialog', { name: 'Trvalé smazání' })).toHaveCount(0);
		await expect(row).toHaveCount(0);
		expect(deleteRequests).toHaveLength(1);
	});
});
