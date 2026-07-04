import { expect, test, type Page } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

type AccountMutationMethod = 'POST' | 'PUT';
type ProblemBody = { errorCode?: string };

async function accountApiRequest(
	page: Page,
	method: AccountMutationMethod,
	path: string,
	body: unknown
): Promise<{ status: number; body: ProblemBody | null }> {
	return page.evaluate(
		async ({ requestMethod, requestPath, requestBody }) => {
			const response = await fetch(requestPath, {
				method: requestMethod,
				headers: {
					Accept: 'application/json',
					'Content-Type': 'application/json',
					'X-API-KEY': 'e2e-test-key'
				},
				body: JSON.stringify(requestBody)
			});
			let responseBody: ProblemBody | null;
			try {
				responseBody = (await response.json()) as ProblemBody;
			} catch {
				responseBody = null;
			}
			return { status: response.status, body: responseBody };
		},
		{ requestMethod: method, requestPath: path, requestBody: body }
	);
}

test.describe('Accounts', () => {
	test('prázdný seznam účtů nabídne onboarding a otevře přidání prvního účtu', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.noAccounts', '1');
		});

		await page.goto('/settings/accounts');
		await waitForShell(page);

		await expect(
			page.getByRole('heading', { level: 2, name: 'Přidejte první poštovní účet' })
		).toBeVisible();
		await expect(
			page.getByText('Začněte e-mailovou adresou. Známé poskytovatele předvyplníme automaticky')
		).toBeVisible();

		await page.getByRole('button', { name: 'Přidat první účet' }).click();
		await page.waitForURL('**/settings/accounts/new');
		await expect(page.locator('#wizard-email')).toBeFocused();
	});

	test('Gmail e-mail otevře OAuth panel s Google CTA bez IMAP formuláře', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.locator('#wizard-email').fill('someone@gmail.com');
		await page.getByRole('button', { name: 'Pokračovat' }).click();

		await expect(
			page.getByRole('heading', {
				name: 'Přihlášení k účtu someone@gmail.com'
			})
		).toBeVisible();
		await expect(page.getByRole('button', { name: 'Přihlásit přes Google' })).toBeVisible();
		await expect(page.locator('#acc-imap-host')).toHaveCount(0);
	});

	test('Outlook e-mail otevře OAuth panel s Microsoft CTA bez IMAP formuláře', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.locator('#wizard-email').fill('user@outlook.com');
		await page.getByRole('button', { name: 'Pokračovat' }).click();

		await expect(
			page.getByRole('heading', { name: 'Přihlášení k účtu user@outlook.com' })
		).toBeVisible();
		await expect(page.getByRole('button', { name: 'Přihlásit přes Microsoft' })).toBeVisible();
		await expect(page.locator('#acc-imap-host')).toHaveCount(0);
	});

	test('Seznam e-mail otevře provider flow s app-password varováním a odkazem', async ({
		page
	}) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.locator('#wizard-email').fill('user@seznam.cz');
		await page.getByRole('button', { name: 'Pokračovat' }).click();

		await expect(page.getByRole('heading', { name: 'Detekováno: Seznam.cz' })).toBeVisible();
		await expect(page.getByText('Pro Seznam.cz potřebujete heslo aplikace')).toBeVisible();
		await expect(page.getByRole('link', { name: 'Otevřít návod' })).toHaveAttribute(
			'href',
			'https://napoveda.seznam.cz/cz/email/heslo-aplikace/'
		);
		await expect(page.locator('#acc-password')).toBeFocused();
	});

	test('auto-resolve providera reaguje i na změnu e-mailové domény', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		// Email-first wizard: manual setup opens the full form; switch to provider
		// mode and test auto-resolve inside the form, not in the stepped wizard.
		await page.getByRole('button', { name: 'Nastavit ručně' }).click();
		await expect(page.locator('#acc-email')).toBeFocused();
		await page.getByRole('radio', { name: 'Vybrat poskytovatele' }).click();

		const email = page.locator('#acc-email');
		const provider = page.locator('#acc-provider');
		const accountName = page.locator('#acc-accountName');

		await email.fill('tester@example.com');
		await expect(provider).toHaveValue('1');
		// Account name is derived from the e-mail domain label, first letter capitalised.
		await expect(accountName).toHaveValue('Example');

		await email.fill('personal@another.test');
		await expect(provider).toHaveValue('2');
		await expect(accountName).toHaveValue('Another');
	});

	test('ruční výběr OAuth poskytovatele ve formuláři nabídne přihlášení místo hesla', async ({
		page
	}) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		// Unknown domain hosted at Microsoft → the wizard opens the form in "Vlastní nastavení".
		await page.locator('#wizard-email').fill('jan@firma.cz');
		await page.getByRole('button', { name: 'Pokračovat' }).click();
		await expect(page.getByRole('radio', { name: 'Vlastní nastavení' })).toBeChecked();

		// Switch to provider selection and pick Microsoft manually (Outlook OAuth flow).
		await page.getByRole('radio', { name: 'Vybrat poskytovatele' }).click();
		const providerSelect = page.locator('#acc-provider');
		// OAuth brands show up in the list as Google / Microsoft, not Gmail / Outlook.
		await expect(providerSelect.getByRole('option', { name: 'Google', exact: true })).toHaveCount(
			1
		);
		await expect(
			providerSelect.getByRole('option', { name: 'Microsoft', exact: true })
		).toHaveCount(1);
		await expect(providerSelect.getByRole('option', { name: 'Gmail', exact: true })).toHaveCount(0);
		await expect(providerSelect.getByRole('option', { name: 'Outlook', exact: true })).toHaveCount(
			0
		);
		await providerSelect.selectOption({ label: 'Microsoft' });

		// Instead of forcing a password, a Microsoft sign-in is offered.
		const oauthCta = page.getByRole('button', { name: 'Přihlásit přes Microsoft' });
		await expect(oauthCta).toBeVisible();
		await oauthCta.click();

		// Move on to the dedicated OAuth step with the entered e-mail, no IMAP/password.
		await expect(
			page.getByRole('heading', { name: 'Přihlášení k účtu jan@firma.cz' })
		).toBeVisible();
		await expect(page.getByRole('button', { name: 'Přihlásit přes Microsoft' })).toBeVisible();
		await expect(page.locator('#acc-imap-host')).toHaveCount(0);
		await expect(page.locator('#acc-password')).toHaveCount(0);
	});

	test('založení účtu s vlastním IMAP/SMTP pošle imap+smtp blok bez providerId', async ({
		page
	}) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		// Unknown domain → the wizard sends us straight to form mode with
		// "Vlastní nastavení" preselected (no template matches the firma.cz domain).
		await page.locator('#wizard-email').fill('majitel@firma.cz');
		await page.getByRole('button', { name: 'Pokračovat' }).click();

		// The custom-settings toggle must be active.
		await expect(page.getByRole('radio', { name: 'Vlastní nastavení' })).toBeChecked();
		// Domain label "firma" → "Firma" (works for custom servers too, no provider needed).
		await expect(page.locator('#acc-accountName')).toHaveValue('Firma');

		await page.locator('#acc-imap-host').fill('imap.firma.cz');
		await page.locator('#acc-smtp-host').fill('smtp.firma.cz');
		await page.locator('#acc-accountName').fill('Firemní pošta');
		await page.locator('#acc-username').fill('majitel@firma.cz');
		await page.locator('#acc-password').fill('secret123');

		// Watch the outgoing request — verify the XOR payload.
		const createRequest = page.waitForRequest(
			(request) => request.url().endsWith('/api/v1/accounts') && request.method() === 'POST'
		);
		const initialSyncRequest = page.waitForRequest(
			(request) =>
				/\/api\/v1\/messages\/account\/\d+\/sync$/.test(request.url()) &&
				request.method() === 'POST'
		);
		await page.getByRole('button', { name: 'Vytvořit účet' }).click();
		const request = await createRequest;
		const body = JSON.parse(request.postData() ?? '{}');

		expect(body.providerId).toBeUndefined();
		expect(body.imap).toMatchObject({ host: 'imap.firma.cz', port: 993, useSsl: true });
		expect(body.smtp).toMatchObject({ host: 'smtp.firma.cz', port: 465, useSsl: true });

		// After a successful create we return to the list; the new account carries the "Vlastní" label.
		await initialSyncRequest;
		await expect(page).toHaveURL(/\/settings\/accounts$/);
	});

	test('test připojení ověří formulář bez vytvoření účtu', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.locator('#wizard-email').fill('majitel@firma.cz');
		await page.getByRole('button', { name: 'Pokračovat' }).click();
		await page.locator('#acc-imap-host').fill('imap.firma.cz');
		await page.locator('#acc-smtp-host').fill('smtp.firma.cz');
		await page.locator('#acc-username').fill('majitel@firma.cz');
		await page.locator('#acc-password').fill('secret123');

		const testRequest = page.waitForRequest(
			(request) =>
				request.url().endsWith('/api/v1/accounts/test-connection') && request.method() === 'POST'
		);
		await page.getByRole('button', { name: 'Otestovat připojení' }).click();
		const request = await testRequest;
		const body = JSON.parse(request.postData() ?? '{}');

		expect(body).toMatchObject({
			accountId: null,
			email: 'majitel@firma.cz',
			username: 'majitel@firma.cz',
			password: 'secret123',
			imap: { host: 'imap.firma.cz', port: 993, useSsl: true },
			smtp: { host: 'smtp.firma.cz', port: 465, useSsl: true }
		});
		await expect(page.getByText('IMAP i SMTP připojení bylo úspěšně ověřeno.')).toBeVisible();
		await expect(page).toHaveURL(/\/settings\/accounts\/new$/);
	});

	test('Zadat jiný e-mail vrátí wizard na první krok', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.locator('#wizard-email').fill('majitel@firma.cz');
		await page.getByRole('button', { name: 'Pokračovat' }).click();
		await expect(page.locator('#acc-email')).toHaveValue('majitel@firma.cz');

		await page.getByRole('button', { name: 'Zadat jiný e-mail' }).click();

		await expect(page.locator('#wizard-email')).toBeVisible();
		await expect(page.locator('#wizard-email')).toBeFocused();
		await expect(page.locator('#acc-email')).toHaveCount(0);
	});

	test('toggle umožní přepnout z provider na vlastní a zpět bez ztráty zadaných údajů', async ({
		page
	}) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);
		await page.getByRole('button', { name: 'Nastavit ručně' }).click();
		await page.getByRole('radio', { name: 'Vybrat poskytovatele' }).click();

		// In provider mode fill the e-mail; auto-resolve matches a template.
		await page.locator('#acc-email').fill('tester@example.com');
		const providerSelect = page.locator('#acc-provider');
		await expect(providerSelect).toHaveValue('1');

		// Switching to "Vlastní nastavení" takes the host/ports over from the selected template.
		await page.getByRole('radio', { name: 'Vlastní nastavení' }).click();
		await expect(page.locator('#acc-imap-host')).toHaveValue('imap.example.com');
		await expect(page.locator('#acc-imap-port')).toHaveValue('993');
		await expect(page.locator('#acc-smtp-host')).toHaveValue('smtp.example.com');

		// Back to provider mode — the dropdown shows again.
		await page.getByRole('radio', { name: 'Vybrat poskytovatele' }).click();
		await expect(providerSelect).toBeVisible();
	});

	test('MSW odmítne neplatné account payloady stejně jako backend kontrakt', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);
		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.reset === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.reset());

		const validProviderPayload = {
			accountName: 'Nový účet',
			email: 'valid-provider@example.test',
			displayName: null,
			providerId: 1,
			username: 'valid-provider@example.test',
			password: 'secret123',
			active: true
		};
		const validCustomPayload = {
			accountName: 'Nový účet',
			email: 'valid-custom@example.test',
			displayName: null,
			imap: { host: 'imap.example.test', port: 993, useSsl: true },
			smtp: { host: 'smtp.example.test', port: 465, useSsl: true },
			username: 'valid-custom@example.test',
			password: 'secret123',
			active: true
		};

		const providerCustomConflict = {
			status: 400,
			errorCode: 'ACCOUNT_XOR',
			byMethod: {
				POST: { ...validProviderPayload, imap: validCustomPayload.imap },
				PUT: { ...validProviderPayload, imap: validCustomPayload.imap }
			}
		};
		const missingServerConfig = {
			status: 400,
			errorCode: 'ACCOUNT_CONFIG_REQUIRED',
			byMethod: {
				POST: {
					accountName: 'Bez serveru',
					email: 'missing-config@example.test',
					username: 'missing-config@example.test',
					password: 'secret123'
				},
				PUT: {
					accountName: 'Bez serveru',
					email: 'missing-config@example.test',
					username: 'missing-config@example.test',
					password: 'secret123',
					active: true
				}
			}
		};
		const incompleteCustomConfig = {
			status: 400,
			errorCode: 'ACCOUNT_CUSTOM_INCOMPLETE',
			byMethod: {
				POST: { ...validCustomPayload, smtp: undefined },
				PUT: { ...validCustomPayload, smtp: undefined }
			}
		};
		const missingProvider = {
			status: 404,
			errorCode: 'PROVIDER_NOT_FOUND',
			byMethod: {
				POST: { ...validProviderPayload, providerId: 999 },
				PUT: { ...validProviderPayload, providerId: 999 }
			}
		};
		const duplicateEmail = {
			status: 409,
			errorCode: 'ACCOUNT_EXISTS',
			byMethod: {
				POST: { ...validProviderPayload, email: 'tester@example.com' },
				PUT: { ...validProviderPayload, email: 'personal@another.test' }
			}
		};

		const cases: Array<{
			method: AccountMutationMethod;
			path: string;
			body: unknown;
			status: number;
			errorCode: string;
		}> = [
			providerCustomConflict,
			missingServerConfig,
			incompleteCustomConfig,
			missingProvider,
			duplicateEmail
		].flatMap(({ status, errorCode, byMethod }) =>
			(Object.entries(byMethod) as Array<[AccountMutationMethod, unknown]>).map(
				([method, body]) => ({
					method,
					path: method === 'POST' ? '/api/v1/accounts' : '/api/v1/accounts/1',
					body,
					status,
					errorCode
				})
			)
		);

		for (const item of cases) {
			const response = await accountApiRequest(page, item.method, item.path, item.body);
			expect(response.status).toBe(item.status);
			expect(response.body).toMatchObject({ errorCode: item.errorCode });
		}
	});

	test('seznam účtů zobrazí všechny existující účty s tlačítky Upravit a Smazat', async ({
		page
	}) => {
		await page.goto('/settings/accounts');
		await waitForShell(page);

		await expect(page.getByRole('heading', { level: 1, name: 'Účty' })).toBeVisible();

		const workRow = page.getByRole('listitem').filter({ hasText: 'Pracovní účet' });
		await expect(workRow.getByText('tester@example.com')).toBeVisible();
		await expect(workRow.getByRole('button', { name: 'Upravit' })).toBeVisible();
		await expect(workRow.getByRole('button', { name: 'Smazat' })).toBeVisible();

		const personalRow = page.getByRole('listitem').filter({ hasText: 'Osobní účet' });
		await expect(personalRow.getByText('personal@another.test')).toBeVisible();
		await expect(personalRow.getByRole('button', { name: 'Upravit' })).toBeVisible();

		// The edit affordance is now a button that navigates via goto() instead of
		// an <a href>, so assert it routes to the correct account on click.
		await workRow.getByRole('button', { name: 'Upravit' }).click();
		await page.waitForURL('**/settings/accounts/1');
	});

	test('smazání účtu z výpisu zavolá DELETE a odebere řádek ze seznamu', async ({ page }) => {
		const deleteUrls: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && /\/api\/v1\/accounts\/\d+$/.test(request.url())) {
				deleteUrls.push(request.url());
			}
		});

		await page.goto('/settings/accounts');
		await waitForShell(page);

		const personalRow = page.getByRole('listitem').filter({ hasText: 'Osobní účet' });
		await personalRow.getByRole('button', { name: 'Smazat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Smazat účet' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat' }).click();

		await expect(page.getByText('Osobní účet')).toHaveCount(0);
		await expect(page.getByText('Pracovní účet')).toBeVisible();
		expect(deleteUrls).toHaveLength(1);
		expect(deleteUrls[0]).toMatch(/\/api\/v1\/accounts\/2$/);
	});

	test('úprava existujícího účtu uloží změnu jména přes PUT a vrátí na výpis', async ({ page }) => {
		const putBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'PUT' && /\/api\/v1\/accounts\/2$/.test(request.url())) {
				putBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/settings/accounts/2');
		await waitForShell(page);

		await expect(
			page.getByRole('heading', { level: 1, name: /Upravit účet: Osobní účet/ })
		).toBeVisible();

		const accountNameInput = page.locator('#acc-accountName');
		await expect(accountNameInput).toHaveValue('Osobní účet');
		await accountNameInput.fill('Osobní – přejmenovaný');

		await page.getByRole('button', { name: 'Uložit změny' }).click();

		await page.waitForURL('**/settings/accounts');
		await expect(page.getByText('Osobní – přejmenovaný')).toBeVisible();

		expect(putBodies).toHaveLength(1);
		expect(putBodies[0]).toMatchObject({
			accountName: 'Osobní – přejmenovaný',
			email: 'personal@another.test'
		});
	});
});
