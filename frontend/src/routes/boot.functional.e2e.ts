import { expect, test } from '@playwright/test';
import { openApp, rowsOf, setMockFlags, setPrefs, waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('MSW bootstrap', () => {
	test('běžný režim nezobrazuje diagnostickou lištu backendu', async ({ page }) => {
		await openApp(page, '/');

		await expect(page.locator('header')).toHaveCount(0);
	});

	test('O aplikaci v běžném režimu nezobrazuje technickou diagnostiku', async ({ page }) => {
		await openApp(page, '/settings/about');

		await expect(page.getByRole('heading', { name: 'Verze' })).toBeVisible();
		await expect(page.getByText('Verze frontendu:')).toBeVisible();
		await expect(page.getByText('0.1.0')).toBeVisible();
		await expect(page.getByText('Verze backendu:')).toBeVisible();
		await expect(page.getByText('0.0.1-SNAPSHOT')).toBeVisible();
		await expect(page.getByRole('button', { name: 'Zkontrolovat aktualizace' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Stav aplikace' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Technická diagnostika' })).toHaveCount(0);
	});

	test('ruční kontrola aktualizací v netauri režimu ukáže dostupnost jen v desktopu', async ({
		page
	}) => {
		await openApp(page, '/settings/about');

		await page.getByRole('button', { name: 'Zkontrolovat aktualizace' }).click();

		await expect(
			page.getByText('Kontrola aktualizací je dostupná v desktopové aplikaci.')
		).toBeVisible();
	});

	test('přepínač podokna čtení používá nativní select', async ({ page }) => {
		await openApp(page, '/settings/appearance');

		await expect(page.getByRole('heading', { name: 'Rozložení podokna čtení' })).toBeVisible();
		const select = page.getByRole('combobox', { name: 'Rozložení podokna čtení' });
		await expect(select).toBeVisible();
		await expect(select).toHaveAttribute('id', 'reading-pane-select');
		await expect(select).toHaveValue('right');
		await expect(select.locator('option')).toHaveText(['Vpravo', 'Dole', 'Skryté']);

		await select.selectOption('bottom');
		await expect(select).toHaveValue('bottom');
		await expect
			.poll(() => page.evaluate(() => window.localStorage.getItem('mail.readingPane')))
			.toBe('bottom');
	});

	test('rail otevře Poštu i bez účtu a nezůstane v Nastavení', async ({ page }) => {
		await setMockFlags(page, { noAccounts: true });

		await openApp(page, '/settings/appearance');

		await page.getByRole('link', { name: 'Pošta (Ctrl+1)' }).click();
		await page.waitForURL('**/');

		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Pošta (Ctrl+1)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('heading', { level: 1, name: 'Pošta' })).toBeVisible();
		await expect(page.getByText('Začněte přidáním e-mailového účtu.')).toBeVisible();
		await expect(page.getByRole('button', { name: 'Přidat účet' })).toBeFocused();
	});

	test('klávesové zkratky Ctrl+1, Ctrl+2 a Ctrl+3 přepínají workspace módy', async ({ page }) => {
		await openApp(page, '/settings/appearance');

		await page.keyboard.press('Alt+2');
		await expect(page).toHaveURL(/\/settings\/appearance$/);

		await page.locator('body').dispatchEvent('keydown', {
			key: 'ě',
			code: 'Digit2',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/contacts');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('main', { name: 'Kontakty' })).toBeFocused();

		await page.keyboard.press('Control+3');
		await page.waitForURL('**/settings/appearance');

		await page.keyboard.press('Control+2');
		await page.waitForURL('**/contacts');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('region', { name: 'Podokno kontaktů' })).toBeVisible();
		await expect(page.getByRole('main', { name: 'Kontakty' })).toBeFocused();

		await page.keyboard.press('Control+3');
		await page.waitForURL('**/settings/appearance');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Nastavení (Ctrl+3)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('region', { name: 'Podokno nastavení' })).toBeVisible();
		await expect(page.getByRole('main', { name: 'Nastavení' })).toBeFocused();

		await page.keyboard.press('Control+1');
		await page.waitForURL('**/mail/1/INBOX');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Pošta (Ctrl+1)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();
		/*
		 * Switching workspaces must land focus on <main> (afterNavigate in
		 * +layout.svelte), never on <body> — otherwise a keyboard user tabs
		 * through the whole rail and sidebar before reaching the content.
		 */
		await expect(page.getByRole('main', { name: 'Pošta' })).toBeFocused();
	});

	test('Ctrl+2 přepne prostředí i s kurzorem v hledacím poli', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		/*
		 * The editable-target bail used to swallow Ctrl+1/2/3 and Ctrl+N: with the
		 * cursor in search (or a compose / settings field) nothing happened at all,
		 * and focus stayed in the field.
		 */
		const search = page.getByRole('searchbox').first();
		await search.focus();
		await expect(search).toBeFocused();

		await page.keyboard.press('Control+2');
		await page.waitForURL('**/contacts');
		await expect(page.getByRole('main', { name: 'Kontakty' })).toBeFocused();
	});

	test('nastartuje aplikaci bez backendu a přesměruje na aktivní inbox', async ({ page }) => {
		await openApp(page, '/');
		await page.waitForURL('**/mail/1/INBOX');

		await expect(page.getByRole('navigation', { name: 'Přepínač prostředí' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.locator('[role="row"][data-stable-id="msg-01"]')).toBeVisible();
		await expect(page.getByRole('button', { name: /Projektové podklady/ })).toBeVisible();
	});

	test('auth chyba při načtení složek se zobrazí bez neobslouženého promise rejection', async ({
		page
	}) => {
		const browserErrors: string[] = [];
		page.on('pageerror', (error) => browserErrors.push(error.message));
		page.on('console', (message) => {
			if (message.type() === 'error' && message.text().includes('Unhandled rejection')) {
				browserErrors.push(message.text());
			}
		});
		await setMockFlags(page, { folderAuthFailure: true });

		await openApp(page, '/');

		await expect(
			page.getByText('Autorizace u Google vypršela nebo byla zrušena.').first()
		).toBeVisible();
		await page.waitForTimeout(100);
		expect(browserErrors).toEqual([]);
	});

	test('při pomalé readiness ukazuje konkrétní fázi a potom dokončí start', async ({ page }) => {
		await setMockFlags(page, { readinessDelayMs: 1200 });

		await page.goto('/');

		await expect(page.getByText('Ověřuji připravenost služby…')).toBeVisible();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('při pomalé session ukazuje čekání na bezpečné připojení', async ({ page }) => {
		await setMockFlags(page, { sessionDelayMs: 900 });

		await page.goto('/');

		await expect(page.getByText('Čekám na bezpečné připojení k backendu…')).toBeVisible();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('dočasně nedostupná readiness se retryne bez pádu bootu', async ({ page }) => {
		await setMockFlags(page, { readinessFailures: 2 });

		await openApp(page, '/');
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Nelze nastartovat aplikaci' })).toHaveCount(0);
	});

	test('velmi pomalý start nabídne retry, restart služby a diagnostiku', async ({ page }) => {
		await setMockFlags(page, {
			e2e: true,
			readinessDelayMs: 900,
			bootSlowMs: 25,
			bootVerySlowMs: 50
		});

		await page.goto('/');

		await expect(page.getByText('Stále se spouští.')).toBeVisible();
		await expect(page.getByRole('button', { name: 'Zkusit znovu' })).toBeVisible();
		await expect(page.getByRole('button', { name: 'Restartovat službu' })).toBeVisible();
		await expect(page.getByRole('button', { name: 'Stáhnout diagnostiku' })).toBeEnabled();

		await page.getByRole('button', { name: 'Restartovat službu' }).click();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('selhání sidecaru nabídne retry a další pokus dokončí start', async ({ page }) => {
		await setMockFlags(page, { e2e: true, sidecarFailure: 'once' });

		await page.goto('/');

		await expect(page.getByRole('heading', { name: 'Nelze nastartovat aplikaci' })).toBeVisible();
		await expect(page.getByText('E2E sidecar failed to start')).toBeVisible();

		await page.getByRole('button', { name: 'Zkusit znovu' }).click();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('client error reporter pošle bezpečný payload na interní endpoint', async ({ page }) => {
		await setMockFlags(page, { e2e: true });

		await openApp(page, '/settings/about');
		await page.waitForFunction(() => typeof window.__MAIL_E2E__?.reportClientError === 'function');
		await page.evaluate(() => {
			window.__MAIL_TEST_SESSION__ = {
				appName: 'mail',
				appVersion: '0.0.1-SNAPSHOT',
				apiVersion: '1.0.0',
				minClientVersion: '0.0.1',
				dbSchemaVersion: '1',
				port: Number(window.location.port || 4173),
				apiKey: 'e2e-test-key',
				baseUrl: `${window.location.origin}/api`
			};
			const originalFetch = window.fetch.bind(window);
			const captured: Array<{ url: string; body: string | null; headers: string[][] }> = [];
			Object.defineProperty(window, '__MAIL_CAPTURED_ERROR_REPORTS__', {
				value: captured,
				configurable: true
			});
			window.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
				const url =
					typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
				if (url.includes('/api/internal/client-errors')) {
					captured.push({
						url,
						body: typeof init?.body === 'string' ? init.body : null,
						headers: Array.from(new Headers(init?.headers).entries())
					});
					return new Response(null, { status: 202 });
				}
				return originalFetch(input, init);
			}) as typeof window.fetch;
		});

		const result = await page.evaluate(() => {
			window.__MAIL_E2E__?.resetClientErrorReportingForTests();
			return window.__MAIL_E2E__?.reportClientError({
				kind: 'window_error',
				error: new Error('E2E captured client error'),
				message: 'E2E captured client error',
				source: window.location.href,
				line: 12,
				column: 34
			});
		});
		const failure = await page.evaluate(() => window.__MAIL_LAST_CLIENT_ERROR_REPORT_FAILURE__);
		expect(result, failure).toBe('sent');

		await expect
			.poll(() => page.evaluate(() => window.__MAIL_CAPTURED_ERROR_REPORTS__?.length ?? 0))
			.toBe(1);
		const report = await page.evaluate(() =>
			JSON.parse(window.__MAIL_CAPTURED_ERROR_REPORTS__?.[0]?.body ?? '{}')
		);
		expect(report).toMatchObject({
			kind: 'window_error',
			message: 'E2E captured client error',
			source: expect.stringContaining('/settings/about'),
			line: 12,
			column: 34,
			route: '/settings/about',
			backend: {
				appName: 'mail',
				appVersion: '0.0.1-SNAPSHOT',
				apiVersion: '1.0.0',
				minClientVersion: '0.0.1',
				dbSchemaVersion: '1'
			}
		});
		expect(JSON.stringify(report)).not.toContain('e2e-test-key');
	});

	test('paginace výpisu používá mockovaná data', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		await expect(page.getByText('Strana 1 z 1')).toBeVisible();
		await expect(rowsOf(page)).toHaveCount(25);
	});

	/** Opens the mocked update prompt and returns its locator. */
	async function openMockUpdatePrompt(page: import('@playwright/test').Page, version = '9.9.9') {
		await setMockFlags(page, { e2e: true });
		await openApp(page, '/settings/about');
		await page.waitForFunction(
			() => typeof window.__MAIL_E2E__?.showMockUpdateForTests === 'function'
		);
		await page.evaluate((v) => {
			window.__MAIL_E2E__?.resetUpdateStateForTests();
			window.__MAIL_E2E__?.showMockUpdateForTests(v);
		}, version);

		const dialog = page.getByRole('dialog', { name: `Nová verze ${version} je k dispozici` });
		await expect(dialog).toBeVisible();
		return dialog;
	}

	function dismissedVersion(page: import('@playwright/test').Page) {
		return page.evaluate(() => window.localStorage.getItem('mail.update.dismissedVersion'));
	}

	test('update prompt funguje s mock response a Později verzi nezahodí', async ({ page }) => {
		const dialog = await openMockUpdatePrompt(page);
		await expect(dialog).toContainText('Používáte verzi 0.1.0.');

		await dialog.getByRole('button', { name: 'Později' }).click();
		await expect(dialog).toBeHidden();

		// "Later" used to mean "never": it persisted the version and the startup
		// check skipped it from then on. Closing the dialog decides nothing now.
		await expect.poll(() => dismissedVersion(page)).toBeNull();
	});

	test('Esc zavře update prompt, ale verzi taky nezahodí', async ({ page }) => {
		const dialog = await openMockUpdatePrompt(page);

		await page.keyboard.press('Escape');
		await expect(dialog).toBeHidden();

		// Escape and a click outside reach the same handler as "Later", so they
		// used to skip the version with no label saying so at all.
		await expect.poll(() => dismissedVersion(page)).toBeNull();
	});

	test('Přeskočit tuto verzi je jediné, co verzi zapíše', async ({ page }) => {
		const dialog = await openMockUpdatePrompt(page);

		await dialog.getByRole('button', { name: 'Přeskočit tuto verzi' }).click();
		await expect(dialog).toBeHidden();
		await expect.poll(() => dismissedVersion(page)).toBe('9.9.9');
	});

	test('probíhající update ukáže progressbar, fázi a ohlásí ji do live region', async ({
		page
	}) => {
		await setMockFlags(page, { e2e: true });

		await openApp(page, '/settings/about');
		await page.waitForFunction(
			() => typeof window.__MAIL_E2E__?.showMockUpdateForTests === 'function'
		);

		await page.evaluate(() => {
			window.__MAIL_E2E__?.resetUpdateStateForTests();
			window.__MAIL_E2E__?.showMockUpdateForTests('9.9.9', {
				holdDownloadAt: { downloaded: 30, total: 100 }
			});
		});

		const prompt = page.getByRole('dialog', { name: 'Nová verze 9.9.9 je k dispozici' });
		await expect(prompt).toBeVisible();
		// The description has to say the app closes; the install ends the
		// process, so a user who does not expect that reads it as a crash.
		await expect(prompt).toContainText('aplikace ukončí');

		await prompt.getByRole('button', { name: 'Aktualizovat teď' }).click();

		// Asserted first, and deliberately: the polite region drops a message
		// after ANNOUNCEMENT_CLEAR_MS (1.5 s), so a check queued behind slower
		// ones could look for text that has already been cleared. The phase is
		// announced there rather than by making the bar itself live — a bar that
		// announces every percent buries the phase changes that matter.
		await expect(page.locator('#live-region')).toContainText('Stahuji aktualizaci…');

		const bar = prompt.getByRole('progressbar', { name: 'Průběh aktualizace' });
		await expect(bar).toHaveAttribute('aria-valuenow', '30');
		await expect(bar).toHaveAttribute('aria-valuetext', 'Staženo 30 %');
		await expect(prompt).toContainText('Stahuji aktualizaci…');
	});

	test('selhání updatu ukáže jediný dialog s fallback linkem, žádný toast navíc', async ({
		page
	}) => {
		await setMockFlags(page, { e2e: true });

		await openApp(page, '/settings/about');
		await page.waitForFunction(
			() => typeof window.__MAIL_E2E__?.showMockUpdateForTests === 'function'
		);

		await page.evaluate(() => {
			window.__MAIL_E2E__?.resetUpdateStateForTests();
			window.__MAIL_E2E__?.showMockUpdateForTests('9.9.9', { failInstall: true });
		});

		const prompt = page.getByRole('dialog', { name: 'Nová verze 9.9.9 je k dispozici' });
		await expect(prompt).toBeVisible();
		await prompt.getByRole('button', { name: 'Aktualizovat teď' }).click();

		const failure = page.getByRole('dialog', { name: 'Aktualizace se nezdařila' });
		await expect(failure).toBeVisible();
		await expect(failure).toContainText('Mock update install failed');
		await expect(failure.getByRole('link', { name: 'Otevřít GitHub Releases' })).toHaveAttribute(
			'href',
			'https://github.com/TheVoxRox/mail/releases/latest'
		);

		// One surface, not three. The dialog used to push a toast carrying its
		// own heading, over a prompt that reverted to 'available' underneath —
		// the same failure announced twice, two modals deep.
		await expect(prompt).toBeHidden();
		await expect(
			page.getByRole('alert').filter({ hasText: 'Aktualizace se nezdařila' })
		).toHaveCount(0);
		// Being the only surface only helps if it is the one that has focus.
		await expect(failure.locator(':focus')).toHaveCount(1);
	});

	test('ignoruje stale activeAccountId a použije existující účet', async ({ page }) => {
		await setPrefs(page, { activeAccountId: 999 });

		await openApp(page, '/');

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();
	});
});
