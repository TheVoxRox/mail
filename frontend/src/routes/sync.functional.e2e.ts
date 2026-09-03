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

	test('tlačítko Synchronizovat zůstane zaostřené po celou dobu průchodu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.getByRole('button', { name: 'Synchronizovat' }).focus();
		await page.keyboard.press('Enter');

		/*
		 * The button disables itself on activation, and `disabled` would take the
		 * focused element out of the focus order — the browser then drops focus to
		 * <body> without firing an event, so the user is nowhere and cannot even
		 * Shift+Tab back to hear the state. Found by listening with NVDA on
		 * 2026-09-01; aria-disabled is what keeps the control reachable, and
		 * aria-busy is only worth setting on something reachable.
		 */
		const busy = page.getByRole('button', { name: 'Synchronizuji…' });
		await expect(busy).toBeFocused();
		await expect(busy).toHaveAttribute('aria-busy', 'true');
		await expect(busy).toHaveAttribute('aria-disabled', 'true');

		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 0));
		await expect(page.getByRole('button', { name: 'Synchronizovat' })).toBeFocused();
	});

	test('sync_cycle_completed neopakuje počet, který už řekl toast složky', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		await page.evaluate(() => {
			window.__MAIL_MSW__?.pushSyncCompleted({
				accountId: 1,
				folderName: 'INBOX',
				newMessagesCount: 2
			});
			window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 2);
		});

		const liveRegion = page.locator('#live-region');
		// The folder toast names the count and the folder; the completion only
		// closes the operation, so the screen reader hears the number once.
		await expect(liveRegion).toContainText('2 nové zprávy, tester@example.com, Doručené');
		await expect(liveRegion).toContainText('Synchronizace dokončena.');
		await expect(liveRegion).not.toContainText('Synchronizace dokončena, 2');
		await expect(page.getByRole('button', { name: 'Synchronizovat' })).toBeEnabled();
	});

	test('návrat sítě zkusí selhávající účet hned, ne až dalším cyklem', async ({ page }) => {
		const syncTriggers: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/messages/account/1/sync')) {
				syncTriggers.push(request.url());
			}
		});

		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);
		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncFailed(1));
		await expect(page.getByRole('link', { name: /Synchronizace selhává/ })).toBeVisible();

		const beforeReconnect = syncTriggers.length;
		/*
		 * Recovery is only noticed by a pass that finishes cleanly, and passes run
		 * every five minutes, so reconnecting used to be followed by nothing at
		 * all — measured on 2026-09-03, the recovery announcement came five
		 * minutes later, with every surface describing the outage until then.
		 */
		await page.evaluate(() => window.dispatchEvent(new Event('online')));

		await expect.poll(() => syncTriggers.length).toBeGreaterThan(beforeReconnect);
	});

	test('zavření toastu pojmenuje, co zavírá, a nenechá fokus spadnout', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		// Two persistent toasts, which is the state the stacking produced: a
		// dismiss button named only by its action gives no way to tell them apart.
		await page.evaluate(() => {
			window.__MAIL_MSW__?.pushSyncFailed(1);
			window.__MAIL_MSW__?.pushSyncFailed(2);
		});

		const first = page.getByRole('button', { name: /Zavřít oznámení.*tester@example\.com/ });
		const second = page.getByRole('button', { name: /Zavřít oznámení.*personal@another\.test/ });
		await expect(first).toBeVisible();
		await expect(second).toBeVisible();

		/*
		 * Dismissing removes the focused element, and nothing used to place focus
		 * afterwards — the browser dropped it to <body>, so a keyboard user
		 * clearing a stack had to walk the whole app again for the next one.
		 */
		await second.focus();
		await page.keyboard.press('Enter');
		await expect(first).toBeFocused();
	});

	test('chyba seznamu složek se sama nepřečte', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.evaluate(() => {
			window.__MAIL_MSW__?.setFolderAuthFailure(true);
			// A completed cycle refreshes the folder list, which is what puts the
			// panel into its error state without a reload.
			window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 0);
		});

		const error = page.getByText('Autorizace u Google vypršela nebo byla zrušena.');
		await expect(error).toBeVisible();

		/*
		 * It used to be role="alert", so every failed pass interrupted whatever
		 * was being read to repeat the same backend sentence in full, port number
		 * included. The event is announced once, by the toast that names the
		 * account; this is ambient panel state. Ancestors are checked too, so the
		 * announcement cannot be restored a level up, and role="status" counts as
		 * a live region here — polite still reads it.
		 */
		const liveAncestor = await error.evaluate((element) => {
			const live = element.closest(
				'[aria-live]:not([aria-live="off"]),[role="alert"],[role="status"]'
			);
			return live
				? `${live.tagName.toLowerCase()}[${live.getAttribute('role') ?? 'aria-live'}]`
				: null;
		});
		expect(liveAncestor).toBeNull();
	});

	test('zotavení vrátí seznam složek, který výpadek shodil', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.evaluate(() => {
			window.__MAIL_MSW__?.setFolderAuthFailure(true);
			window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 0);
		});
		await expect(page.getByText('Autorizace u Google vypršela nebo byla zrušena.')).toBeVisible();

		/*
		 * The navigation is gone from the keyboard at this point, and nothing used
		 * to bring it back: recovery refetched only accounts, and the folder list
		 * is refreshed by sync_cycle_completed, which a scheduled pass with nobody
		 * waiting never sends. Asserted through a folder link rather than the
		 * absence of the error, because "the error went away" would also pass if
		 * the panel rendered nothing at all.
		 */
		await page.evaluate(() => {
			window.__MAIL_MSW__?.setFolderAuthFailure(false);
			window.__MAIL_MSW__?.pushSyncRecovered();
		});

		await expect(page.getByRole('link', { name: /Doručené/ })).toBeVisible();
		await expect(page.getByText('Autorizace u Google vypršela nebo byla zrušena.')).toHaveCount(0);
	});

	test('neúplný průchod se neohlásí jako dokončený', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.waitForFunction(() => window.__MAIL_MSW__?.syncStreamConnected() === true);

		await page.getByRole('button', { name: 'Synchronizovat' }).click();
		/*
		 * allFoldersSynced=false means a folder was skipped or the pass failed
		 * outright, so the count is a floor and neither case may claim the sync
		 * is done. Asserted here and not only in the unit test because this is
		 * the sentence a screen reader gets: with the mail server unreachable it
		 * used to say "Synchronizace dokončena." two seconds before the failure
		 * toast, and on every later failing pass it is the only thing said about
		 * the outcome — sync_failed does not fire again for an unchanged code.
		 */
		await page.evaluate(() => window.__MAIL_MSW__?.pushSyncCycleCompleted(1, 0, false));

		const liveRegion = page.locator('#live-region');
		await expect(liveRegion).toContainText('Synchronizace neproběhla celá.');
		await expect(liveRegion).not.toContainText('Synchronizace dokončena');
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
