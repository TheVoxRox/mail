import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-01',
	sender: 'Jana Novak <jana@example.com>',
	replySubject: 'Re: Projektové podklady'
};

/*
 * Reading pane off: an opened message fills the screen with no list alongside
 * it. The inline toolbar inside MessageDetail is the only action surface, so it
 * must expose reply / delete — otherwise an opened message has no reachable
 * actions.
 */
test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'off');
	});
});

test.describe('Akce v otevřené zprávě (off mód)', () => {
	test('inline toolbar nabízí akce, i když je horní lišta skrytá', async ({ page }) => {
		await page.goto(`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await waitForShell(page);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		// Off mode: a single click only selects the row (mirrors an Arrow key);
		// the double click is the deliberate open, like Enter.
		await row.dblclick();

		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);

		// Exactly one toolbar — the inline copy, never a duplicated top-bar one.
		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar).toHaveCount(1);
		await expect(toolbar).toBeVisible();

		await toolbar.getByRole('button', { name: 'Odpovědět', exact: true }).click();

		await page.waitForURL(new RegExp(`/compose\\?reply=${fixture.stableId}`));
		await expect(page.getByText(fixture.sender)).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue(fixture.replySubject);
	});

	test('titulek okna otevřené zprávy obsahuje předmět', async ({ page }) => {
		await page.goto(
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		await waitForShell(page);

		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		await expect(page).toHaveTitle('Pošta – Projektové podklady');
	});

	test('stažení přílohy potvrdí úspěch toastem', async ({ page }) => {
		await page.goto(
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		await waitForShell(page);

		await page.getByRole('button', { name: /brief\.pdf/ }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Příloha brief.pdf stažena.' })
		).toBeVisible();
	});
});
