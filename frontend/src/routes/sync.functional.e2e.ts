import { expect, test } from '@playwright/test';
import { openApp, setMockFlags, setPrefs } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
	await setMockFlags(page, { e2e: true });
});

test.describe('Sync notifications', () => {
	test('zobrazí toast a aktualizuje unread badge po sync_completed eventu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		const folders = page.getByRole('region', { name: 'Podokno pošty' });
		const inbox = folders.getByRole('link', { name: /Doručené/ });
		await expect(inbox.getByText('3')).toBeVisible();

		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.pushSyncCompleted === 'function');
		await page.evaluate(() => {
			window.__MAIL_MSW__?.pushSyncCompleted({
				accountId: 1,
				folderName: 'INBOX',
				newMessagesCount: 2
			});
		});

		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(
			notifications.getByText('2 nové zprávy, tester@example.com, Doručené')
		).toBeVisible();
		await expect(inbox.getByText('5')).toBeVisible();

		await page.getByRole('link', { name: 'Nastavení (Ctrl+3)' }).click();
		await page.getByRole('link', { name: 'O aplikaci' }).click();

		await expect(page.getByRole('heading', { name: 'Technická diagnostika' })).toBeVisible();
		await expect(page.getByText('Synchronizace:', { exact: true })).toBeVisible();
		await expect(page.getByText('online')).toBeVisible();
		await expect(page.getByText('Doručené — 2 nové zprávy')).toBeVisible();
	});

	test('zpracuje sync_completed event s CRLF SSE framingem', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		const folders = page.getByRole('region', { name: 'Podokno pošty' });
		const inbox = folders.getByRole('link', { name: /Doručené/ });
		await expect(inbox.getByText('3')).toBeVisible();

		await page.waitForFunction(
			() => typeof window.__MAIL_MSW__?.pushSyncCompletedCrLf === 'function'
		);
		await page.evaluate(() => {
			window.__MAIL_MSW__?.pushSyncCompletedCrLf({
				accountId: 1,
				folderName: 'INBOX',
				newMessagesCount: 4
			});
		});

		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(
			notifications.getByText('4 nové zprávy, tester@example.com, Doručené')
		).toBeVisible();
		await expect(inbox.getByText('7')).toBeVisible();
	});

	test('tlačítko Synchronizovat ohlásí zahájení do live regionu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		// The endpoint replies 202 (sync continues in the background), so the
		// truthful immediate feedback is "started" — the end of the pass arrives
		// later as sync_cycle_completed, tested below.
		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		await expect(page.locator('#live-region')).toContainText('Synchronizace zahájena.');
	});

	test('sync_cycle_completed ohlásí konec i bez nové pošty a uvolní tlačítko', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		// The button waits for the pass, not for its own request: the 202 has
		// long returned by the time this assertion runs.
		await expect(page.getByRole('button', { name: 'Synchronizuji…' })).toBeVisible();

		// A pass that downloaded nothing emits no folder event at all — this is
		// the whole reason the cycle event exists.
		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 0));

		await expect(page.locator('#live-region')).toContainText(
			'Synchronizace dokončena, žádné nové zprávy.'
		);
		await expect(page.getByRole('button', { name: 'Synchronizovat' })).toBeEnabled();
	});

	test('selhání synchronizace se ohlásí a nechá v podokně dosažitelný odkaz', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		// The listing has to be hydrated before the synthetic event: the handler
		// refetches accounts, and a pre-hydration push would be dropped.
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		// Gate on the subscription, not on rendering: a push into an empty client
		// set is dropped, and the shell exists before the stream is subscribed.
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);
		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncFailed());

		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(
			notifications.getByText(/Synchronizace účtu tester@example.com selhala/)
		).toBeVisible();

		/*
		 * The standing indicator is the part a toast cannot cover — the toast is
		 * gone by the time the user wonders why no mail is arriving. It must be a
		 * real control, not an icon-only, non-focusable marker: that is the
		 * mouse-only trap the conversation expand toggle fell into (#221).
		 *
		 * A link, because it does nothing but open the accounts page, which exists
		 * regardless of any sync failing. Reporting a state alongside does not
		 * make it an action.
		 */
		const indicator = page.getByRole('link', {
			name: 'Synchronizace selhává: tester@example.com'
		});
		await expect(indicator).toBeVisible();
		await indicator.focus();
		await expect(indicator).toBeFocused();

		await indicator.click();
		await expect(page.getByRole('heading', { name: 'Účty' })).toBeVisible();
	});

	test('zotavení synchronizace indikátor zase schová', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		// Gate on the subscription, not on rendering: a push into an empty client
		// set is dropped, and the shell exists before the stream is subscribed.
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);
		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncFailed());

		const indicator = page.getByRole('link', {
			name: 'Synchronizace selhává: tester@example.com'
		});
		await expect(indicator).toBeVisible();

		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncRecovered());
		await expect(indicator).toBeHidden();
	});
});
