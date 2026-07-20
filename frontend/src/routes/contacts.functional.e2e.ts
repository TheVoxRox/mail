import { expect, test, type Page } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

type ContactSeed = {
	name: string;
	surname?: string | null;
	note?: string | null;
	emails: Array<{ email: string; label?: 'WORK' | 'HOME' | 'OTHER' | null }>;
};

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
		window.localStorage.removeItem('mail.e2e.noAccounts');
	});
});

async function bulkCreateContacts(page: Page, contacts: ContactSeed[]) {
	await page.waitForFunction(() => typeof window.__MAIL_MSW__?.reset === 'function');
	const response = await page.evaluate(async (items) => {
		const res = await fetch('/api/v1/accounts/1/contacts/bulk', {
			method: 'POST',
			headers: {
				Accept: 'application/json',
				'Content-Type': 'application/json',
				'X-API-KEY': 'e2e-test-key'
			},
			body: JSON.stringify({ contacts: items })
		});
		return { status: res.status };
	}, contacts);
	expect(response.status).toBe(200);
}

test.describe('Contacts', () => {
	test('sidebar používá tlačítko Nový kontakt a Ctrl+N otevře vytvoření kontaktu', async ({
		page
	}) => {
		await page.goto('/contacts/1');
		await waitForShell(page);

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(sidebar.getByRole('link', { name: /Nový kontakt/ })).toHaveCount(0);

		await sidebar.getByRole('button', { name: 'Nový kontakt Ctrl+N' }).click();
		await page.waitForURL('**/contacts/1?create=1');
		await expect(page.getByRole('heading', { name: 'Nový kontakt' })).toBeVisible();

		await page.goto('/contacts/1');
		await waitForShell(page);
		await page.locator('body').dispatchEvent('keydown', {
			key: 'n',
			code: 'KeyN',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/contacts/1?create=1');
		await expect(page.getByRole('heading', { name: 'Nový kontakt' })).toBeVisible();
	});

	test('sidebar zobrazení jsou odkazy s počty a filtrují podle štítku', async ({ page }) => {
		await page.goto('/contacts/1');
		await waitForShell(page);

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		const nav = sidebar.getByRole('navigation', { name: 'Zobrazení kontaktů' });

		// Fixture: 1 kontakt (Jana Novak) se štítky Práce + Domov. Accessible
		// name odkazu zahrnuje aria-label badge s počtem.
		const allLink = nav.getByRole('link', { name: 'Kontakty 1 kontakt' });
		await expect(allLink).toHaveAttribute('aria-current', 'page');

		// Jiný nemá žádný kontakt — badge se nevykresluje vůbec (vzor pošty).
		await expect(nav.getByRole('link', { name: 'Jiný' })).toBeVisible();

		await nav.getByRole('link', { name: 'Práce 1 kontakt' }).click();
		await page.waitForURL('**/contacts/1?label=WORK');
		await expect(nav.getByRole('link', { name: 'Práce 1 kontakt' })).toHaveAttribute(
			'aria-current',
			'page'
		);
		await expect(page.getByText('Jana Novak')).toBeVisible();
		// Nadpis stránky i titulek okna nesou aktivní pohled.
		await expect(page.getByRole('heading', { level: 1, name: 'Práce' })).toBeVisible();
		await expect(page).toHaveTitle('Pošta – Kontakty – Práce');

		// Prázdný štítek jmenuje sám sebe místo obecné hlášky.
		await nav.getByRole('link', { name: 'Jiný' }).click();
		await page.waitForURL('**/contacts/1?label=OTHER');
		await expect(page.getByRole('heading', { level: 1, name: 'Jiný' })).toBeVisible();
		await expect(page.getByText(/Žádný kontakt se štítkem Jiný/)).toBeVisible();

		await allLink.click();
		await page.waitForURL('**/contacts/1');
		await expect(allLink).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();
	});

	test('řazení je persistovaná preference a přežije klik v sidebaru', async ({ page }) => {
		await page.goto('/contacts/1');
		await waitForShell(page);

		await page.getByRole('combobox', { name: 'Řadit podle' }).selectOption('recent');
		await page.getByRole('button', { name: 'Použít filtr' }).click();
		await page.waitForURL('**/contacts/1?sort=recent');

		// Sidebar odkaz vede na čistou URL bez sortu — řazení se doplní z
		// persistované preference, ne z URL.
		const nav = page.getByRole('navigation', { name: 'Zobrazení kontaktů' });
		await nav.getByRole('link', { name: 'Práce 1 kontakt' }).click();
		await page.waitForURL('**/contacts/1?label=WORK');
		await expect(page.getByRole('combobox', { name: 'Řadit podle' })).toHaveValue('recent');
	});

	test('rail otevře Kontakty i bez účtu a nezůstane v Nastavení', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.e2e.noAccounts', '1');
		});

		await page.goto('/settings/appearance');
		await waitForShell(page);

		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();
		await page.waitForURL('**/contacts');

		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');
		await expect(
			page.getByRole('heading', { level: 1, name: 'Žádný e-mailový účet' })
		).toBeVisible();
		await expect(page.getByText('Začněte přidáním e-mailového účtu.')).toBeVisible();
		await expect(page.getByRole('button', { name: 'Přidat účet' })).toBeFocused();
	});

	test('vytvoří kontakt přes MSW API a zobrazí ho v seznamu', async ({ page }) => {
		await page.goto('/contacts/1?create=1');
		await waitForShell(page);

		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toHaveCount(0);
		await expect(page.getByRole('heading', { level: 1, name: 'Nový kontakt' })).toBeVisible();
		await expect(
			page.getByText(
				'Vyberte účet v postranním panelu, hledejte kontakty nebo otevřete vytvoření nového kontaktu.'
			)
		).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Exportovat vCard' })).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Importovat vCard' })).toHaveCount(0);
		await expect(page.locator('#contact-name')).toBeFocused();

		await page.getByPlaceholder('Jméno').fill('Marie');
		await page.getByPlaceholder('Příjmení').fill('Dvorak');
		await page.getByPlaceholder('email@example.cz').fill('marie@example.com');
		await page.getByPlaceholder('Poznámka').fill('E2E fixture');
		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL('**/contacts/1');
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Kontakt vytvořen.')
		).toBeVisible();
		await expect(page.getByText('Marie Dvorak')).toBeVisible();
		await expect(page.getByText('marie@example.com')).toBeVisible();
	});

	test('kontakty se zobrazují jako přístupná tabulka', async ({ page }) => {
		await page.goto('/contacts/1');
		await waitForShell(page);

		const table = page.getByRole('table', { name: 'Seznam kontaktů' });
		await expect(table).toBeVisible();
		await expect(table.getByRole('columnheader', { name: 'Jméno' })).toBeVisible();
		await expect(table.getByRole('columnheader', { name: 'E-mail' })).toBeVisible();
		await expect(table.getByRole('columnheader', { name: 'Štítky' })).toBeVisible();
		await expect(table.getByRole('row', { name: /Jana Novak/ })).toBeVisible();
	});

	test('create bez e-mailu zobrazí inline chybu a neodešle request', async ({ page }) => {
		const createRequests: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/accounts\/1\/contacts$/.test(request.url())) {
				createRequests.push(request.url());
			}
		});

		await page.goto('/contacts/1?create=1');
		await waitForShell(page);

		await page.getByPlaceholder('Jméno').fill('Bez');
		await page.getByPlaceholder('Příjmení').fill('Emailu');
		await page.getByRole('button', { name: 'Uložit' }).click();

		await expect(page.locator('#contact-email-0')).toHaveAttribute('aria-invalid', 'true');
		await expect(page.locator('#contact-email-0-error')).toContainText(
			'Zadejte alespoň jeden e-mail.'
		);
		expect(createRequests).toHaveLength(0);
	});

	test('úprava jména kontaktu se uloží jedním PUT s celým kontaktem', async ({ page }) => {
		const putBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'PUT' &&
				/\/api\/v1\/accounts\/1\/contacts\/1$/.test(request.url())
			) {
				putBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts/1?edit=1');
		await expect(page.getByRole('heading', { name: 'Upravit kontakt' })).toBeVisible();

		await page.getByPlaceholder('Jméno').fill('Jana Edit');
		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL('**/contacts/1');
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Kontakt uložen.')
		).toBeVisible();
		await expect(page.getByText('Jana Edit Novak')).toBeVisible();
		await expect(page.getByText('jana@example.com')).toBeVisible();
		await expect(page.getByText('jana.home@example.com')).toBeVisible();
		expect(putBodies).toHaveLength(1);
		expect(putBodies[0]).toEqual({
			name: 'Jana Edit',
			surname: 'Novak',
			note: 'Projekt',
			emails: [
				{ email: 'jana@example.com', label: 'WORK' },
				{ email: 'jana.home@example.com', label: 'HOME' }
			]
		});
	});

	test('úprava e-mailů (přidat, hlavní, odebrat) se uloží jedním PUT', async ({ page }) => {
		const putBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'PUT' &&
				/\/api\/v1\/accounts\/1\/contacts\/1$/.test(request.url())
			) {
				putBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts/1?edit=1');

		// Add a third address, then remove it again — it must not reach the PUT body.
		await page.getByRole('button', { name: 'Přidat e-mail' }).click();
		await page.locator('#contact-email-2').fill('jana.extra@example.com');

		// Promote the second address (jana.home) to primary — it must come first in the PUT.
		await page.locator('#contact-email-1-primary').check();

		await page
			.locator('[data-email-row="2"]')
			.getByRole('button', { name: 'Odebrat e-mail' })
			.click();
		await expect(page.locator('#contact-email-2')).toHaveCount(0);

		await page.getByRole('button', { name: 'Uložit' }).click();
		await page.waitForURL('**/contacts/1');
		await expect(page.getByText('Jana Novak')).toBeVisible();

		expect(putBodies).toHaveLength(1);
		expect(putBodies[0]).toEqual({
			name: 'Jana',
			surname: 'Novak',
			note: 'Projekt',
			emails: [
				{ email: 'jana.home@example.com', label: 'HOME' },
				{ email: 'jana@example.com', label: 'WORK' }
			]
		});
	});

	test('selhání načtení editovaného kontaktu zobrazí chybu bez smyčky requestů', async ({
		page
	}) => {
		let getCount = 0;
		page.on('request', (request) => {
			if (
				request.method() === 'GET' &&
				/\/api\/v1\/accounts\/1\/contacts\/999$/.test(request.url())
			) {
				getCount += 1;
			}
		});

		await page.goto('/contacts/1?edit=999');
		await waitForShell(page);

		await expect(page.getByRole('alert')).toBeVisible();
		await expect(page.locator('#contact-name')).toHaveCount(0);
		// A refetch loop would fire many GETs — wait a beat, then assert a single call.
		await page.waitForTimeout(500);
		expect(getCount).toBe(1);
	});

	test('opuštění editace s neuloženými změnami vyžádá potvrzení a po potvrzení zahodí', async ({
		page
	}) => {
		await page.goto('/contacts/1');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts/1?edit=1');

		await page.getByPlaceholder('Jméno').fill('Jana Změněná');
		await page.getByRole('button', { name: 'Zrušit' }).click();

		const dialog = page.getByRole('dialog', { name: 'Neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zahodit změny' }).click();

		await page.waitForURL('**/contacts/1');
		// The edit was never persisted — the list still shows the original name.
		await expect(page.getByText('Jana Novak')).toBeVisible();
		await expect(page.getByText('Jana Změněná')).toHaveCount(0);
	});

	test('Escape zruší vytváření kontaktu; s neuloženými změnami až po potvrzení', async ({
		page
	}) => {
		await page.goto('/contacts/1?create=1');
		await waitForShell(page);
		// The onMount autofocus proves the form is hydrated (the Esc handler is a
		// window listener attached on mount).
		await expect(page.locator('#contact-name')).toBeFocused();

		// Pristine form: Esc leaves immediately, no confirmation.
		await page.keyboard.press('Escape');
		await page.waitForURL('**/contacts/1');
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();

		// Dirty form: Esc must not discard silently — the leave guard asks first.
		await page
			.getByRole('region', { name: 'Podokno kontaktů' })
			.getByRole('button', { name: 'Nový kontakt Ctrl+N' })
			.click();
		await page.waitForURL('**/contacts/1?create=1');
		await expect(page.locator('#contact-name')).toBeFocused();
		await page.getByPlaceholder('Jméno').fill('Nedokončený');
		await page.keyboard.press('Escape');

		const dialog = page.getByRole('dialog', { name: 'Neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zahodit změny' }).click();

		await page.waitForURL('**/contacts/1');
		await expect(page.getByText('Nedokončený')).toHaveCount(0);
	});

	test('bulk delete smaže vybrané kontakty přes multiselect', async ({ page }) => {
		const deleteBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'DELETE' &&
				/\/api\/v1\/accounts\/1\/contacts\/bulk$/.test(request.url())
			) {
				deleteBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1?create=1');
		await waitForShell(page);

		await page.getByPlaceholder('Jméno').fill('Bulk');
		await page.getByPlaceholder('Příjmení').fill('Delete');
		await page.getByPlaceholder('email@example.cz').fill('bulk.delete@example.com');
		await page.getByRole('button', { name: 'Uložit' }).click();
		await expect(page.getByText('Bulk Delete')).toBeVisible();

		await page.getByLabel('Vybrat kontakt Jana Novak').check();
		// The first selection announces the newly revealed bulk toolbar through
		// the persistent live region (the conditional status span alone is not
		// announced reliably when inserted with content).
		await expect(page.locator('#live-region')).toContainText(
			'Hromadné akce nad seznamem: sloučit, smazat.'
		);
		await page.getByLabel('Vybrat kontakt Bulk Delete').check();
		await expect(page.getByText('2 vybrané kontakty')).toBeVisible();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		const bulkDialog = page.getByRole('dialog', { name: 'Hromadné mazání kontaktů' });
		await expect(bulkDialog).toBeVisible();
		await bulkDialog.getByRole('button', { name: 'Smazat' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Smazáno: 2, selhalo: 0.')
		).toBeVisible();
		await expect(page.getByText('Jana Novak')).toHaveCount(0);
		await expect(page.getByText('Bulk Delete')).toHaveCount(0);
		expect(deleteBodies).toEqual([{ ids: [1, 2] }]);
	});

	test('merge dialog zobrazí preview a sloučí vybrané kontakty', async ({ page }) => {
		const mergeBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				/\/api\/v1\/accounts\/1\/contacts\/1\/merge$/.test(request.url())
			) {
				mergeBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);
		await page.evaluate(() => window.__MAIL_MSW__?.reset());
		await bulkCreateContacts(page, [
			{
				name: 'Jan',
				surname: 'Novak',
				note: 'Duplicitní kontakt',
				emails: [{ email: 'jan@example.com', label: 'WORK' }]
			}
		]);
		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts/1?q=example.com');
		await expect(page.getByText('Jan Novak')).toBeVisible();

		await page.getByLabel('Vybrat kontakt Jana Novak').check();
		await page.getByLabel('Vybrat kontakt Jan Novak').check();
		await expect(page.getByText('2 vybrané kontakty')).toBeVisible();
		await page.getByRole('button', { name: 'Sloučit' }).click();

		const dialog = page.getByRole('dialog', { name: 'Sloučit kontakty' });
		await expect(dialog).toBeVisible();
		await expect(dialog.getByRole('heading', { name: 'Po sloučení (3 e-maily)' })).toBeVisible();
		await expect(
			dialog.locator('section[aria-live="polite"]').getByText('jan@example.com', { exact: true })
		).toBeVisible();
		await expect(dialog.getByText('ze sloučeného')).toBeVisible();

		await dialog.getByRole('button', { name: 'Sloučit kontakty' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Kontakty sloučeny.')
		).toBeVisible();
		await expect(page.getByText('Jan Novak')).toHaveCount(0);
		expect(mergeBodies).toEqual([{ source: [2] }]);
	});

	test('merge dialog upozorní a zablokuje sloučení nad limit e-mailů', async ({ page }) => {
		await page.goto('/contacts/1');
		await waitForShell(page);
		await page.evaluate(() => window.__MAIL_MSW__?.reset());
		await bulkCreateContacts(page, [
			{
				name: 'Limit',
				surname: 'Emailu',
				emails: Array.from({ length: 9 }, (_, index) => ({
					email: `limit-${index + 1}@example.com`,
					label: 'OTHER' as const
				}))
			}
		]);
		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts/1?q=example.com');
		await expect(page.getByText('Limit Emailu')).toBeVisible();

		await page.getByLabel('Vybrat kontakt Jana Novak').check();
		await page.getByLabel('Vybrat kontakt Limit Emailu').check();
		await page.getByRole('button', { name: 'Sloučit' }).click();

		const dialog = page.getByRole('dialog', { name: 'Sloučit kontakty' });
		await expect(dialog.getByRole('heading', { name: 'Po sloučení (11 e-mailů)' })).toBeVisible();
		await expect(
			dialog.getByText(
				'Po sloučení by kontakt měl 11 e-mailů, maximum je 10. Před sloučením snižte počet adres v některém z kontaktů.'
			)
		).toBeVisible();
		await expect(dialog.getByRole('button', { name: 'Sloučit kontakty' })).toBeDisabled();
		await expect(dialog.getByRole('button', { name: 'Sloučit kontakty' })).toHaveAttribute(
			'aria-describedby',
			'contact-merge-limit-warning'
		);
	});

	test('smazání jednoho kontaktu zobrazí success toast a odstraní jej ze seznamu', async ({
		page
	}) => {
		const deletedIds: number[] = [];
		page.on('request', (request) => {
			const match = request.url().match(/\/api\/v1\/accounts\/1\/contacts\/(\d+)$/);
			if (request.method() === 'DELETE' && match) {
				deletedIds.push(Number(match[1]));
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);

		const row = page.getByRole('row', { name: /Jana Novak/ });
		await row.getByRole('button', { name: 'Smazat kontakt Jana Novak' }).click();

		const dialog = page.getByRole('dialog', { name: 'Smazat kontakt' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Kontakt smazán.')
		).toBeVisible();
		await expect(page.getByText('Jana Novak')).toHaveCount(0);
		expect(deletedIds).toEqual([1]);
	});

	test('vCard drag-and-drop naimportuje kontakty přes bulk endpoint', async ({ page }) => {
		const bulkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				/\/api\/v1\/accounts\/1\/contacts\/bulk$/.test(request.url())
			) {
				bulkBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);
		await expect(page.getByText('Jana Novak')).toBeVisible();

		const vcardText =
			'BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Marek Drag\r\nN:Drag;Marek;;;\r\nEMAIL;TYPE=WORK:marek.drag@example.com\r\nNOTE:Drop import\r\nEND:VCARD\r\nBEGIN:VCARD\r\nVERSION:3.0\r\nFN:Iva Drop\r\nEMAIL:iva.drop@example.com\r\nEND:VCARD\r\n';

		await page.evaluate((text) => {
			const file = new File([text], 'import.vcf', { type: 'text/vcard' });
			const dataTransfer = new DataTransfer();
			dataTransfer.items.add(file);
			const drop = new Event('drop', { bubbles: true, cancelable: true });
			Object.defineProperty(drop, 'dataTransfer', {
				value: dataTransfer,
				configurable: true
			});
			(document.body ?? document).dispatchEvent(drop);
		}, vcardText);

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Importováno: 2, selhalo: 0.')
		).toBeVisible();
		await expect(page.getByText('Marek Drag')).toBeVisible();
		await expect(page.getByText('marek.drag@example.com')).toBeVisible();
		await expect(page.getByText('Iva Drop')).toBeVisible();
		await expect(page.getByText('iva.drop@example.com')).toBeVisible();

		expect(bulkBodies).toEqual([
			{
				contacts: [
					{
						name: 'Marek',
						surname: 'Drag',
						note: 'Drop import',
						emails: [{ email: 'marek.drag@example.com', label: 'WORK' }]
					},
					{
						name: 'Iva',
						surname: 'Drop',
						note: null,
						emails: [{ email: 'iva.drop@example.com', label: null }]
					}
				]
			}
		]);
	});

	test('vCard import přes tlačítko v sidebaru naimportuje kontakty bez drag-and-dropu', async ({
		page
	}) => {
		const bulkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				/\/api\/v1\/accounts\/1\/contacts\/bulk$/.test(request.url())
			) {
				bulkBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);
		await expect(page.getByText('Jana Novak')).toBeVisible();

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(sidebar.getByRole('button', { name: 'Importovat vCard' })).toBeVisible();

		const vcardText =
			'BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Petra Picker\r\nN:Picker;Petra;;;\r\nEMAIL;TYPE=HOME:petra.picker@example.com\r\nEND:VCARD\r\n';
		await sidebar.locator('input[type="file"]').setInputFiles({
			name: 'import.vcf',
			mimeType: 'text/vcard',
			buffer: Buffer.from(vcardText, 'utf-8')
		});

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Importováno: 1, selhalo: 0.')
		).toBeVisible();
		// The list reloads without navigation — the imported contact appears.
		await expect(page.getByText('Petra Picker')).toBeVisible();
		await expect(page.getByText('petra.picker@example.com')).toBeVisible();

		expect(bulkBodies).toEqual([
			{
				contacts: [
					{
						name: 'Petra',
						surname: 'Picker',
						note: null,
						emails: [{ email: 'petra.picker@example.com', label: 'HOME' }]
					}
				]
			}
		]);
	});

	test('export vCard stáhne soubor a zobrazí success toast', async ({ page }) => {
		const exportResponses: { status: number; disposition: string | null }[] = [];
		page.on('response', (response) => {
			if (/\/api\/v1\/accounts\/1\/contacts\/export\.vcf$/.test(response.url())) {
				exportResponses.push({
					status: response.status(),
					disposition: response.headers()['content-disposition'] ?? null
				});
			}
		});

		await page.goto('/contacts/1');
		await waitForShell(page);

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(sidebar.getByRole('button', { name: 'Exportovat vCard' })).toBeVisible();
		const downloadPromise = page.waitForEvent('download');
		await sidebar.getByRole('button', { name: 'Exportovat vCard' }).click();
		const download = await downloadPromise;

		expect(download.suggestedFilename()).toBe('contacts-1.vcf');
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Adresář byl exportován.')
		).toBeVisible();
		expect(exportResponses).toHaveLength(1);
		expect(exportResponses[0].status).toBe(200);
		expect(exportResponses[0].disposition).toContain('filename="contacts-1.vcf"');
	});

	test('přímá URL s neexistujícím účtem přejde na existující účet', async ({ page }) => {
		await page.goto('/contacts/999');
		await waitForShell(page);

		await page.waitForURL('**/contacts/1');
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();
		await expect(page.getByText('Jana Novak')).toBeVisible();
	});
});
