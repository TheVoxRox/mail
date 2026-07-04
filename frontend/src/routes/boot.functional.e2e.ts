import { expect, test } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('MSW bootstrap', () => {
	test('běžný režim nezobrazuje diagnostickou lištu backendu', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await expect(page.locator('header')).toHaveCount(0);
	});

	test('O aplikaci v běžném režimu nezobrazuje technickou diagnostiku', async ({ page }) => {
		await page.goto('/settings/about');
		await waitForShell(page);

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
		await page.goto('/settings/about');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Zkontrolovat aktualizace' }).click();

		await expect(
			page.getByText('Kontrola aktualizací je dostupná v desktopové aplikaci.')
		).toBeVisible();
	});

	test('přepínač podokna čtení používá nativní select', async ({ page }) => {
		await page.goto('/settings/appearance');
		await waitForShell(page);

		await expect(page.getByRole('heading', { name: 'Podokno čtení' })).toBeVisible();
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.noAccounts', '1');
		});

		await page.goto('/settings/appearance');
		await waitForShell(page);

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
		await page.goto('/settings/appearance');
		await waitForShell(page);

		await page.keyboard.press('Alt+2');
		await expect(page).toHaveURL(/\/settings\/appearance$/);

		await page.locator('body').dispatchEvent('keydown', {
			key: 'ě',
			code: 'Digit2',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/contacts/1');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');

		await page.keyboard.press('Control+3');
		await page.waitForURL('**/settings/appearance');

		await page.keyboard.press('Control+2');
		await page.waitForURL('**/contacts/1');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('region', { name: 'Podokno kontaktů' })).toBeVisible();

		await page.keyboard.press('Control+3');
		await page.waitForURL('**/settings/appearance');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Nastavení (Ctrl+3)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('navigation', { name: 'Podokno nastavení' })).toBeVisible();

		await page.keyboard.press('Control+1');
		await page.waitForURL('**/mail/1/INBOX');
		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Pošta (Ctrl+1)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('region', { name: 'Podokno složek' })).toBeVisible();
	});

	test('Gmail zkratka ? otevře přehled zkratek mimo textová pole', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		await page.keyboard.press('Shift+/');
		await page.waitForURL('**/settings/shortcuts');
		await expect(page.locator('main h1')).toHaveText('Klávesové zkratky');
		await expect(page.getByText('Otevřít přehled klávesových zkratek')).toBeVisible();

		await page.goto('/compose');
		await waitForShell(page);
		await page.locator('#compose-subject').focus();
		await page.keyboard.press('Shift+/');

		await expect(page).toHaveURL(/\/compose$/);
		await expect(page.locator('#compose-subject')).toHaveValue('/');
	});

	test('nastartuje aplikaci bez backendu a přesměruje na aktivní inbox', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');

		await expect(page.getByRole('navigation', { name: 'Přepínač prostředí' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno složek' })).toBeVisible();
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.folderAuthFailure', '1');
		});

		await page.goto('/');
		await waitForShell(page);

		await expect(
			page.getByText('Autorizace u Google vypršela nebo byla zrušena.').first()
		).toBeVisible();
		await page.waitForTimeout(100);
		expect(browserErrors).toEqual([]);
	});

	test('při pomalé readiness ukazuje konkrétní fázi a potom dokončí start', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.readinessDelayMs', '1200');
		});

		await page.goto('/');

		await expect(page.getByText('Ověřuji připravenost služby…')).toBeVisible();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('při pomalé session ukazuje čekání na bezpečné připojení', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.sessionDelayMs', '900');
		});

		await page.goto('/');

		await expect(page.getByText('Čekám na bezpečné připojení k backendu…')).toBeVisible();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('dočasně nedostupná readiness se retryne bez pádu bootu', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.readinessFailures', '2');
		});

		await page.goto('/');

		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.getByRole('heading', { name: 'Nelze nastartovat aplikaci' })).toHaveCount(0);
	});

	test('velmi pomalý start nabídne retry, restart služby a diagnostiku', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e', '1');
			window.localStorage.setItem('mail.e2e.readinessDelayMs', '900');
			window.localStorage.setItem('mail.e2e.bootSlowMs', '25');
			window.localStorage.setItem('mail.e2e.bootVerySlowMs', '50');
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
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e', '1');
			window.localStorage.setItem('mail.e2e.sidecarFailure', 'once');
		});

		await page.goto('/');

		await expect(page.getByRole('heading', { name: 'Nelze nastartovat aplikaci' })).toBeVisible();
		await expect(page.getByText('E2E sidecar failed to start')).toBeVisible();

		await page.getByRole('button', { name: 'Zkusit znovu' }).click();
		await waitForShell(page);
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('client error reporter pošle bezpečný payload na interní endpoint', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e', '1');
		});

		await page.goto('/settings/about');
		await waitForShell(page);
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
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		await expect(page.getByText('Strana 1 z 1')).toBeVisible();
		await expect(page.locator('[role="row"][data-stable-id]')).toHaveCount(25);
	});

	test('update prompt funguje s mock response a Později dismissne verzi', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e', '1');
		});

		await page.goto('/settings/about');
		await waitForShell(page);
		await page.waitForFunction(
			() => typeof window.__MAIL_E2E__?.showMockUpdateForTests === 'function'
		);

		await page.evaluate(() => {
			window.__MAIL_E2E__?.resetUpdateStateForTests();
			window.__MAIL_E2E__?.showMockUpdateForTests('9.9.9');
		});

		const dialog = page.getByRole('dialog', { name: 'Nová verze 9.9.9 je k dispozici' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Používáte verzi 0.1.0.');

		await dialog.getByRole('button', { name: 'Později' }).click();
		await expect(dialog).toBeHidden();
		await expect
			.poll(() => page.evaluate(() => window.localStorage.getItem('mail.update.dismissedVersion')))
			.toBe('9.9.9');
	});

	test('update failure ukáže toast a GitHub Releases fallback link', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e', '1');
		});

		await page.goto('/settings/about');
		await waitForShell(page);
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
		await expect(
			page.getByRole('alert').filter({ hasText: 'Aktualizace se nezdařila' })
		).toBeVisible();
	});

	test('ignoruje stale activeAccountId a použije existující účet', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.activeAccountId', '999');
		});

		await page.goto('/');
		await waitForShell(page);

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno složek' })).toBeVisible();
	});
});
