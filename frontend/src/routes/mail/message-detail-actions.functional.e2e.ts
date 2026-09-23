import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

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

/*
 * Opening a message marks it read by itself, through a request that is in
 * flight for as long as the backend takes. "Open it, then put it back as
 * unread" is pressed inside that window, and the press used to be dropped: the
 * shortcut asked the open message whether it was read, and the flag it reads
 * arrives with that request's answer.
 */
test.describe('Nepřečtené hned po otevření', () => {
	test('Ctrl+U během automatického označení zprávu nechá nepřečtenou', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		/*
		 * The window is held open rather than made long: the keystroke has to be
		 * inside it for this test to be about anything, and a timed window only
		 * makes that likely — under load it closed first and the run measured the
		 * case the test was not written for.
		 */
		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.holdFlagResponses === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.holdFlagResponses());

		// Opened from the list, as a reader opens it: that is the path that parks
		// focus in the message body, and the key travels from there.
		await page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar).toBeVisible();
		// The message is unread — which is why opening it marks it read at all.
		await expect(toolbar.getByRole('button', { name: 'Označit jako přečtené' })).toBeVisible();
		// The app moves focus into the body a frame after the open, and the frame
		// forwards keys to the global handler once it is there; pressing before
		// that is a race the suite has paid for before (see waitForFocus).
		const frame = bodyFrame(page);
		await expect(frame).toBeVisible();
		await waitForFocus(frame);

		await page.keyboard.press('Control+u');

		/*
		 * Then the window closes. The answer to the open's own request is what the
		 * app acts on last, so the assertions wait for it — without that they are
		 * satisfied by the state before the release, which is unread either way
		 * and says nothing.
		 */
		const autoMarkAnswered = page.waitForResponse(
			(response) => response.url().includes('/flags?') && response.url().includes('value=true')
		);
		await page.evaluate(() => window.__MAIL_MSW__?.releaseFlagResponses());
		await autoMarkAnswered;
		await page.waitForTimeout(300);

		// Still unread: the button offers to mark it read, not the other way round.
		await expect(toolbar.getByRole('button', { name: 'Označit jako přečtené' })).toBeVisible();
		await expect(toolbar.getByRole('button', { name: 'Označit jako nepřečtené' })).toHaveCount(0);
	});
});
