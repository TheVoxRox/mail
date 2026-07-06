import { expect, test } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
		window.localStorage.setItem('mail.e2e', '1');
	});
});

test.describe('Sync notifications', () => {
	test('zobrazí toast a aktualizuje unread badge po sync_completed eventu', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

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
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

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
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// The endpoint replies 202 (sync continues in the background), so the
		// truthful immediate feedback is "started" — completion with new mail
		// is announced later by the sync_completed toast above.
		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		await expect(page.locator('#live-region')).toContainText('Synchronizace zahájena.');
	});
});
