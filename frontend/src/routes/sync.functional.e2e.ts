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
		// truthful immediate feedback is "started" — completion with new mail
		// is announced later by the sync_completed toast above.
		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		await expect(page.locator('#live-region')).toContainText('Synchronizace zahájena.');
	});

	test('selhání synchronizace se ohlásí a nechá v podokně dosažitelné tlačítko', async ({
		page
	}) => {
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
		 * real button: an icon-only, non-focusable marker is the mouse-only trap
		 * the conversation expand toggle fell into (#221).
		 */
		const indicator = page.getByRole('button', {
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

		const indicator = page.getByRole('button', {
			name: 'Synchronizace selhává: tester@example.com'
		});
		await expect(indicator).toBeVisible();

		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncRecovered());
		await expect(indicator).toBeHidden();
	});
});
