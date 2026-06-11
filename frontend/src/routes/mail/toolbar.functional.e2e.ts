import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-01',
	moveStableId: 'msg-02',
	replyPrefill: {
		to: 'Jana Novak <jana@example.com>',
		subject: 'Re: Projektové podklady'
	}
};

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Mail toolbar', () => {
	test('reply akce v toolbaru otevře compose s prefillem vybrané zprávy', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.click();

		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar).toBeVisible();

		const replyButton = toolbar.getByRole('button', { name: 'Odpovědět', exact: true });
		await expect(replyButton).toHaveAttribute('aria-keyshortcuts', 'Control+R');
		await replyButton.click();

		await page.waitForURL(new RegExp(`/compose\\?reply=${fixture.stableId}`));
		await expect(page.getByText(fixture.replyPrefill.to)).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue(fixture.replyPrefill.subject);
	});

	test('po flag PATCH se znovu otevřený detail nenačte ze staré cache', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`);
		await expect(row).toBeVisible();
		await row.click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar.getByRole('button', { name: 'Zrušit hvězdičku' })).toBeVisible();
		await toolbar.getByRole('button', { name: 'Zrušit hvězdičku' }).click();
		await expect(toolbar.getByRole('button', { name: 'Označit hvězdičkou' })).toBeVisible();

		await page.getByRole('button', { name: 'Zpět' }).click();
		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);
		await expect(toolbar.getByRole('button', { name: 'Označit hvězdičkou' })).toBeVisible();
	});

	test('přesun zprávy pošle MoveRequest a refreshne zdrojový i cílový seznam', async ({ page }) => {
		const moveBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-02\/move$/.test(request.url())) {
				moveBodies.push(request.postDataJSON());
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Přesunout', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Archiv', exact: true }).click();

		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await expect(
			page.getByRole('status').filter({ hasText: 'Zpráva přesunuta do složky Archiv.' })
		).toBeVisible();
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toHaveCount(0);
		expect(moveBodies).toEqual([{ folderRef: 'ARCHIVE' }]);

		await page.getByRole('button', { name: 'Archiv' }).click();
		await page.waitForURL(`**/mail/${fixture.accountId}/ARCHIVE`);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toBeVisible();
		await expect(page.getByText('Testovací zpráva 2')).toBeVisible();
	});

	test('přesun do spamu přes nabídku Přesunout pošle JUNK na move endpoint', async ({ page }) => {
		const junkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-07\/move$/.test(request.url())) {
				junkBodies.push(request.postDataJSON());
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		await page.locator('[role="row"][data-stable-id="msg-07"]').click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/msg-07`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Přesunout', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Spam', exact: true }).click();

		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await expect(
			page.getByRole('status').filter({ hasText: 'Zpráva přesunuta do složky Spam.' })
		).toBeVisible();
		expect(junkBodies).toEqual([{ folderRef: 'JUNK' }]);
		await expect(page.locator('[role="row"][data-stable-id="msg-07"]')).toHaveCount(0);
	});

	test('multiselect zpráv podporuje hromadné přečtení, přesun a smazání', async ({ page }) => {
		const flagRequests: string[] = [];
		const moveBodies: unknown[] = [];
		const deleteRequests: string[] = [];

		page.on('request', (request) => {
			const url = request.url();
			if (request.method() === 'PATCH' && /\/api\/v1\/messages\/msg-0[12]\/flags/.test(url)) {
				flagRequests.push(url);
			}
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-0[34]\/move$/.test(url)) {
				moveBodies.push(request.postDataJSON());
			}
			if (request.method() === 'DELETE' && /\/api\/v1\/messages\/msg-0[56]$/.test(url)) {
				deleteRequests.push(url);
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 2', exact: true })
			.check();
		await expect(page.getByText('2 vybrané zprávy')).toBeVisible();

		await page.getByRole('button', { name: 'Označit jako…', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Přečtené', exact: true }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Označeno jako přečtené: 2, selhalo: 0.' })
		).toBeVisible();
		expect(flagRequests).toHaveLength(2);
		expect(
			flagRequests.every((url) => url.includes('type=seen') && url.includes('value=true'))
		).toBe(true);

		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 3', exact: true })
			.check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 4', exact: true })
			.check();
		await page.getByRole('button', { name: 'Přesunout vybrané' }).click();
		await page.getByRole('menuitem', { name: 'Archiv', exact: true }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Přesunuto do složky Archiv: 2, selhalo: 0.' })
		).toBeVisible();
		expect(moveBodies).toEqual([{ folderRef: 'ARCHIVE' }, { folderRef: 'ARCHIVE' }]);
		await expect(page.locator('[role="row"][data-stable-id="msg-03"]')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="msg-04"]')).toHaveCount(0);

		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 5', exact: true })
			.check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 6', exact: true })
			.check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Smazáno: 2, selhalo: 0.' })
		).toBeVisible();
		expect(deleteRequests).toHaveLength(2);
		await expect(page.locator('[role="row"][data-stable-id="msg-05"]')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="msg-06"]')).toHaveCount(0);
	});

	test('výběr zprávy oznámí dostupné hromadné akce a panel má roli toolbar', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const bulkToolbar = page.getByRole('toolbar', { name: 'Hromadné akce', exact: true });
		await expect(bulkToolbar).toBeVisible();
		// Action buttons are absent until something is selected.
		await expect(bulkToolbar.getByRole('button', { name: 'Smazat vybrané' })).toHaveCount(0);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();

		// First selection announces the available actions to screen readers.
		await expect(page.locator('#live-region')).toContainText('Hromadné akce nad seznamem');

		// The action buttons now live inside the labelled bulk toolbar.
		await expect(
			bulkToolbar.getByRole('button', { name: 'Označit jako…', exact: true })
		).toBeVisible();
		await expect(bulkToolbar.getByRole('button', { name: 'Smazat vybrané' })).toBeVisible();
	});

	test('hromadné označení nepřečtené přes dropdown Označit jako… pošle value=false', async ({
		page
	}) => {
		const flagRequests: string[] = [];
		page.on('request', (request) => {
			const url = request.url();
			if (request.method() === 'PATCH' && /\/api\/v1\/messages\/msg-0[12]\/flags/.test(url)) {
				flagRequests.push(url);
			}
		});

		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 2', exact: true })
			.check();

		await page.getByRole('button', { name: 'Označit jako…', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Nepřečtené', exact: true }).click();

		await expect(
			page.getByRole('status').filter({ hasText: 'Označeno jako nepřečtené: 2, selhalo: 0.' })
		).toBeVisible();
		expect(flagRequests).toHaveLength(2);
		expect(
			flagRequests.every((url) => url.includes('type=seen') && url.includes('value=false'))
		).toBe(true);
	});
});
