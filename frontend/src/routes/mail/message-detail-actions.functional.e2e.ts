import { expect, test } from '@playwright/test';
import { openApp, setPrefs } from '../e2e-helpers';

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
	await setPrefs(page, { locale: 'cs', readingPane: 'off' });
});

test.describe('Akce v otevřené zprávě (off mód)', () => {
	test('inline toolbar nabízí akce, i když je horní lišta skrytá', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		// A click anywhere on the row opens it (the web-mail model).
		await row.click();

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
		await openApp(
			page,
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);

		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		await expect(page).toHaveTitle('Pošta – Projektové podklady');
	});

	test('stažení přílohy potvrdí úspěch toastem', async ({ page }) => {
		await openApp(
			page,
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);

		await page.getByRole('button', { name: /brief\.pdf/ }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Příloha brief.pdf stažena.' })
		).toBeVisible();
	});
});
