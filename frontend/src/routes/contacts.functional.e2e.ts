import { expect, test, type Page } from '@playwright/test';
import { contactGrid, openApp, setMockFlags } from './e2e-helpers';

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
		const res = await fetch('/api/v1/contacts/bulk', {
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
		await openApp(page, '/contacts');

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(sidebar.getByRole('link', { name: /Nový kontakt/ })).toHaveCount(0);

		await sidebar.getByRole('button', { name: 'Nový kontakt Ctrl+N' }).click();
		await page.waitForURL('**/contacts?create=1');
		await expect(page.getByRole('heading', { name: 'Nový kontakt' })).toBeVisible();

		await openApp(page, '/contacts');
		await page.locator('body').dispatchEvent('keydown', {
			key: 'n',
			code: 'KeyN',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/contacts?create=1');
		await expect(page.getByRole('heading', { name: 'Nový kontakt' })).toBeVisible();
	});

	test('sidebar zobrazení jsou odkazy s počty a filtrují podle štítku', async ({ page }) => {
		await openApp(page, '/contacts');

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		const nav = sidebar.getByRole('navigation', { name: 'Zobrazení kontaktů' });

		// Fixture: 1 kontakt (Jana Novak) se štítkem Klienti, plus prázdný
		// štítek Rodina. Accessible name odkazu zahrnuje aria-label badge s
		// počtem.
		const allLink = nav.getByRole('link', { name: 'Všechny kontakty 1 kontakt' });
		await expect(allLink).toHaveAttribute('aria-current', 'page');

		// Nepoužitý štítek je v panelu vidět s nulou — na rozdíl od pevných typů
		// může být prázdný prostě proto, že je nový.
		await expect(nav.getByRole('link', { name: 'Rodina 0 kontaktů' })).toBeVisible();

		await nav.getByRole('link', { name: 'Klienti 1 kontakt' }).click();
		await page.waitForURL('**/contacts?labelId=1');
		await expect(nav.getByRole('link', { name: 'Klienti 1 kontakt' })).toHaveAttribute(
			'aria-current',
			'page'
		);
		await expect(page.getByText('Jana Novak')).toBeVisible();
		// Nadpis stránky i titulek okna nesou aktivní pohled.
		await expect(page.getByRole('heading', { level: 1, name: 'Klienti' })).toBeVisible();
		await expect(page).toHaveTitle('Pošta – Kontakty – Klienti');

		// Prázdný štítek jmenuje sám sebe místo obecné hlášky.
		await nav.getByRole('link', { name: 'Rodina 0 kontaktů' }).click();
		await page.waitForURL('**/contacts?labelId=2');
		await expect(page.getByRole('heading', { level: 1, name: 'Rodina' })).toBeVisible();
		await expect(page.getByText(/Žádný kontakt se štítkem Rodina/)).toBeVisible();

		await allLink.click();
		await page.waitForURL('**/contacts');
		await expect(allLink).toHaveAttribute('aria-current', 'page');
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();
	});

	test('řazení je persistovaná preference a přežije klik v sidebaru', async ({ page }) => {
		await openApp(page, '/contacts');

		await page.getByRole('combobox', { name: 'Řadit podle' }).selectOption('recent');
		await page.getByRole('button', { name: 'Použít filtr' }).click();
		await page.waitForURL('**/contacts?sort=recent');

		// Sidebar odkaz vede na čistou URL bez sortu — řazení se doplní z
		// persistované preference, ne z URL.
		const nav = page.getByRole('navigation', { name: 'Zobrazení kontaktů' });
		await nav.getByRole('link', { name: 'Klienti 1 kontakt' }).click();
		await page.waitForURL('**/contacts?labelId=1');
		await expect(page.getByRole('combobox', { name: 'Řadit podle' })).toHaveValue('recent');
	});

	test('rail otevře Kontakty i bez účtu a nezůstane v Nastavení', async ({ page }) => {
		await setMockFlags(page, { noAccounts: true });

		await openApp(page, '/settings/appearance');

		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();
		await page.waitForURL('**/contacts');

		await expect(
			page
				.getByRole('navigation', { name: 'Přepínač prostředí' })
				.getByRole('link', { name: 'Kontakty (Ctrl+2)' })
		).toHaveAttribute('aria-current', 'page');
		// The address book belongs to the application, not to a mailbox, so with no
		// account configured it still opens as the real thing rather than an
		// "add an account first" wall.
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();
		await expect(page.getByText('Jana Novak')).toBeVisible();

		// A contact can also be written before any mailbox exists — the primary
		// new action used to bail out on a missing account and do nothing.
		await page.keyboard.press('Control+n');
		await page.waitForURL('**/contacts?create=1');
		await expect(page.getByRole('heading', { level: 1, name: 'Nový kontakt' })).toBeVisible();
	});

	test('vytvoří kontakt přes MSW API a zobrazí ho v seznamu', async ({ page }) => {
		await openApp(page, '/contacts?create=1');

		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toHaveCount(0);
		await expect(page.getByRole('heading', { level: 1, name: 'Nový kontakt' })).toBeVisible();
		await expect(
			page.getByText(
				'Vyberte účet v postranním panelu, hledejte kontakty nebo otevřete vytvoření nového kontaktu.'
			)
		).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Exportovat vCard' })).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Importovat vCard' })).toHaveCount(0);
		// Spravovat štítky naopak zůstává: formulář na něj sám odkazuje, když
		// účet ještě žádné štítky nemá, takže skrýt ho tady by z té nápovědy
		// udělalo slepou uličku.
		await expect(page.getByRole('button', { name: 'Spravovat štítky' })).toBeVisible();
		// Štítky ve formuláři jdou ze stejného storu jako odznaky v podokně, a
		// ten plní až načtení seznamu — na tuhle URL se ale jde rovnou, seznam
		// se nenačte a nabídka štítků byla proto prázdná.
		await expect(page.getByRole('checkbox', { name: 'Klienti' })).toBeVisible();
		await expect(page.locator('#contact-name')).toBeFocused();

		await page.getByPlaceholder('Jméno').fill('Marie');
		await page.getByPlaceholder('Příjmení').fill('Dvorak');
		await page.getByPlaceholder('email@example.cz').fill('marie@example.com');
		await page.getByPlaceholder('Poznámka').fill('E2E fixture');
		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL('**/contacts');
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Kontakt vytvořen.')
		).toBeVisible();
		await expect(page.getByText('Marie Dvorak')).toBeVisible();
		await expect(page.getByText('marie@example.com')).toBeVisible();
	});

	test('kontakty se zobrazují jako přístupný grid', async ({ page }) => {
		await openApp(page, '/contacts');

		// A grid, not a plain table: the rows carry interactive controls and are
		// navigated with arrows under a roving tabindex.
		const grid = contactGrid(page);
		await expect(grid).toBeVisible();
		await expect(grid.getByRole('columnheader', { name: 'Jméno' })).toBeVisible();
		await expect(grid.getByRole('columnheader', { name: 'E-mail' })).toBeVisible();
		await expect(grid.getByRole('columnheader', { name: 'Štítky' })).toBeVisible();
		await expect(grid.getByRole('row', { name: /Jana Novak/ })).toBeVisible();
	});

	test('seznam kontaktů je jeden tab stop a šipky chodí po buňkách', async ({ page }) => {
		await openApp(page, '/contacts');
		await bulkCreateContacts(page, [
			{ name: 'Karel', surname: 'Druhy', emails: [{ email: 'karel@example.com' }] }
		]);
		// Re-query through the app so the freshly seeded contact joins the list.
		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts?q=example.com');

		const rows = page.locator('tbody tr[data-contact-id]');
		await expect(rows).toHaveCount(2);

		// Every row used to contribute four tab stops (checkbox + three action
		// buttons); the grid exposes exactly one for the whole list.
		const tabbable = await page.evaluate(
			() =>
				document.querySelectorAll(
					'tbody [data-cell-target]:not([tabindex="-1"]), tbody button:not([tabindex="-1"])'
				).length
		);
		expect(tabbable).toBe(1);

		const cell = (row: number, col: number) =>
			page.locator(`tbody [data-row-index="${row}"] [data-cell-target][data-col="${col}"]`);

		await cell(0, 1).focus();
		await page.keyboard.press('ArrowRight');
		await expect(cell(0, 2)).toBeFocused();

		// The action buttons are cells of the row like any other.
		await page.keyboard.press('End');
		await expect(cell(0, 7)).toBeFocused();
		await expect(cell(0, 7)).toHaveAccessibleName('Smazat kontakt Jana Novak');

		await page.keyboard.press('ArrowDown');
		await expect(cell(1, 7)).toBeFocused();

		await page.keyboard.press('Home');
		await expect(cell(1, 0)).toBeFocused();

		// Enter on a content cell opens the contact, like clicking the row.
		await page.keyboard.press('ArrowRight');
		await expect(cell(1, 1)).toBeFocused();
		await page.keyboard.press('Enter');
		await page.waitForURL(/\/contacts\?.*edit=\d+/);
	});

	test('create bez e-mailu zobrazí inline chybu a neodešle request', async ({ page }) => {
		const createRequests: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/contacts$/.test(request.url())) {
				createRequests.push(request.url());
			}
		});

		await openApp(page, '/contacts?create=1');

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
			if (request.method() === 'PUT' && /\/api\/v1\/contacts\/1$/.test(request.url())) {
				putBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');

		await page.getByRole('link', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts?edit=1');
		await expect(page.getByRole('heading', { name: 'Upravit kontakt' })).toBeVisible();

		await page.getByPlaceholder('Jméno').fill('Jana Edit');
		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL('**/contacts');
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
			],
			labelIds: [1]
		});
		// And the label really survived the round trip, not just the request body.
		await expect(page.getByRole('gridcell', { name: 'Klienti' })).toBeVisible();
	});

	test('úprava e-mailů (přidat, hlavní, odebrat) se uloží jedním PUT', async ({ page }) => {
		const putBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'PUT' && /\/api\/v1\/contacts\/1$/.test(request.url())) {
				putBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');

		await page.getByRole('link', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts?edit=1');

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
		await page.waitForURL('**/contacts');
		await expect(page.getByText('Jana Novak')).toBeVisible();

		expect(putBodies).toHaveLength(1);
		expect(putBodies[0]).toEqual({
			name: 'Jana',
			surname: 'Novak',
			note: 'Projekt',
			emails: [
				{ email: 'jana.home@example.com', label: 'HOME' },
				{ email: 'jana@example.com', label: 'WORK' }
			],
			// PUT replaces the whole contact, so an edit that never touches the
			// labels still has to send them back — otherwise reordering e-mails
			// would silently strip the contact's labels.
			labelIds: [1]
		});
	});

	test('selhání načtení editovaného kontaktu zobrazí chybu bez smyčky requestů', async ({
		page
	}) => {
		let getCount = 0;
		page.on('request', (request) => {
			if (request.method() === 'GET' && /\/api\/v1\/contacts\/999$/.test(request.url())) {
				getCount += 1;
			}
		});

		await openApp(page, '/contacts?edit=999');

		await expect(page.getByRole('alert')).toBeVisible();
		await expect(page.locator('#contact-name')).toHaveCount(0);
		// A refetch loop would fire many GETs — wait a beat, then assert a single call.
		await page.waitForTimeout(500);
		expect(getCount).toBe(1);
	});

	test('opuštění editace s neuloženými změnami vyžádá potvrzení a po potvrzení zahodí', async ({
		page
	}) => {
		await openApp(page, '/contacts');

		await page.getByRole('link', { name: 'Upravit' }).first().click();
		await page.waitForURL('**/contacts?edit=1');

		await page.getByPlaceholder('Jméno').fill('Jana Změněná');
		await page.getByRole('button', { name: 'Zrušit' }).click();

		const dialog = page.getByRole('dialog', { name: 'Neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zahodit změny' }).click();

		await page.waitForURL('**/contacts');
		// The edit was never persisted — the list still shows the original name.
		await expect(page.getByText('Jana Novak')).toBeVisible();
		await expect(page.getByText('Jana Změněná')).toHaveCount(0);
	});

	test('Escape zruší vytváření kontaktu; s neuloženými změnami až po potvrzení', async ({
		page
	}) => {
		await openApp(page, '/contacts?create=1');
		// The onMount autofocus proves the form is hydrated (the Esc handler is a
		// window listener attached on mount).
		await expect(page.locator('#contact-name')).toBeFocused();

		// Pristine form: Esc leaves immediately, no confirmation.
		await page.keyboard.press('Escape');
		await page.waitForURL('**/contacts');
		await expect(page.getByRole('heading', { level: 1, name: 'Kontakty' })).toBeVisible();

		// Dirty form: Esc must not discard silently — the leave guard asks first.
		await page
			.getByRole('region', { name: 'Podokno kontaktů' })
			.getByRole('button', { name: 'Nový kontakt Ctrl+N' })
			.click();
		await page.waitForURL('**/contacts?create=1');
		await expect(page.locator('#contact-name')).toBeFocused();
		await page.getByPlaceholder('Jméno').fill('Nedokončený');
		await page.keyboard.press('Escape');

		const dialog = page.getByRole('dialog', { name: 'Neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zahodit změny' }).click();

		await page.waitForURL('**/contacts');
		await expect(page.getByText('Nedokončený')).toHaveCount(0);
	});

	test('bulk delete smaže vybrané kontakty přes multiselect', async ({ page }) => {
		const deleteBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && /\/api\/v1\/contacts\/bulk$/.test(request.url())) {
				deleteBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts?create=1');

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
			'Hromadné akce nad seznamem: přiřadit štítky, sloučit, smazat.'
		);
		await page.getByLabel('Vybrat kontakt Bulk Delete').check();
		await expect(page.getByText('2 vybrané kontakty')).toBeVisible();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		const bulkDialog = page.getByRole('dialog', { name: 'Hromadné mazání kontaktů' });
		await expect(bulkDialog).toBeVisible();
		await bulkDialog.getByRole('button', { name: 'Smazat' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Smazáno: 2.')
		).toBeVisible();
		await expect(page.getByText('Jana Novak')).toHaveCount(0);
		await expect(page.getByText('Bulk Delete')).toHaveCount(0);
		expect(deleteBodies).toEqual([{ ids: [1, 2] }]);
	});

	test('merge dialog zobrazí preview a sloučí vybrané kontakty', async ({ page }) => {
		const mergeBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/contacts\/1\/merge$/.test(request.url())) {
				mergeBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');
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
		await page.waitForURL('**/contacts?q=example.com');
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
		await openApp(page, '/contacts');
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
		await page.waitForURL('**/contacts?q=example.com');
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
			const match = request.url().match(/\/api\/v1\/contacts\/(\d+)$/);
			if (request.method() === 'DELETE' && match) {
				deletedIds.push(Number(match[1]));
			}
		});

		await openApp(page, '/contacts');

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

	test('smazání kontaktu vrátí fokus na sousední řádek', async ({ page }) => {
		await openApp(page, '/contacts');
		await bulkCreateContacts(page, [
			{ name: 'Karel', surname: 'Druhy', emails: [{ email: 'karel@example.com' }] }
		]);
		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts?q=example.com');
		await expect(page.locator('tbody tr[data-contact-id]')).toHaveCount(2);

		// The Delete button and the confirm dialog's trigger both vanish with the
		// row, so without a restore focus falls to <body>.
		await page.getByRole('button', { name: 'Smazat kontakt Jana Novak' }).click();
		const dialog = page.getByRole('dialog', { name: 'Smazat kontakt' });
		await dialog.getByRole('button', { name: 'Smazat' }).click();

		await expect(page.getByText('Jana Novak')).toHaveCount(0);
		await expect(page.locator('tbody [data-row-index="0"] [data-col="1"]')).toBeFocused();
	});

	test('smazání posledního kontaktu přesune fokus na hlášku prázdného seznamu', async ({
		page
	}) => {
		// The fixture holds a single contact, so the whole grid goes away with
		// it and there is no row left to hand focus to.
		await openApp(page, '/contacts');

		await page.getByRole('button', { name: 'Smazat kontakt Jana Novak' }).click();
		await page
			.getByRole('dialog', { name: 'Smazat kontakt' })
			.getByRole('button', { name: 'Smazat' })
			.click();

		const empty = page.getByRole('status').filter({ hasText: 'Žádné kontakty' });
		await expect(empty).toBeVisible();
		await expect(empty).toBeFocused();
	});

	test('návrat z úpravy kontaktu vrátí fokus na jeho řádek', async ({ page }) => {
		await openApp(page, '/contacts');

		await page.getByRole('link', { name: 'Upravit kontakt Jana Novak' }).click();
		await page.waitForURL('**/contacts?edit=1');
		await expect(page.locator('#contact-name')).toBeFocused();

		// The form replaced the list, so the roving position is gone — Esc (like
		// Cancel and Save) has to hand focus back to the contact's row.
		await page.keyboard.press('Escape');
		await page.waitForURL((url) => url.pathname === '/contacts' && !url.searchParams.has('edit'));
		await expect(page.locator('tbody tr[data-contact-id="1"] [data-col="1"]')).toBeFocused();
	});

	test('vCard drag-and-drop naimportuje kontakty přes bulk endpoint', async ({ page }) => {
		const bulkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/contacts\/bulk$/.test(request.url())) {
				bulkBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');
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
			page.getByRole('region', { name: 'Oznámení' }).getByText('Importováno: 2.')
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
						emails: [{ email: 'marek.drag@example.com', label: 'WORK' }],
						labelIds: []
					},
					{
						name: 'Iva',
						surname: 'Drop',
						note: null,
						emails: [{ email: 'iva.drop@example.com', label: null }],
						labelIds: []
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
			if (request.method() === 'POST' && /\/api\/v1\/contacts\/bulk$/.test(request.url())) {
				bulkBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');
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
			page.getByRole('region', { name: 'Oznámení' }).getByText('Importováno: 1.')
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
						emails: [{ email: 'petra.picker@example.com', label: 'HOME' }],
						labelIds: []
					}
				]
			}
		]);
	});

	test('export vCard stáhne soubor a zobrazí success toast', async ({ page }) => {
		const exportResponses: { status: number; disposition: string | null }[] = [];
		page.on('response', (response) => {
			if (/\/api\/v1\/contacts\/export\.vcf$/.test(response.url())) {
				exportResponses.push({
					status: response.status(),
					disposition: response.headers()['content-disposition'] ?? null
				});
			}
		});

		await openApp(page, '/contacts');

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(sidebar.getByRole('button', { name: 'Exportovat vCard' })).toBeVisible();
		const downloadPromise = page.waitForEvent('download');
		await sidebar.getByRole('button', { name: 'Exportovat vCard' }).click();
		const download = await downloadPromise;

		expect(download.suggestedFilename()).toBe('contacts.vcf');
		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText('Adresář byl exportován.')
		).toBeVisible();
		expect(exportResponses).toHaveLength(1);
		expect(exportResponses[0].status).toBe(200);
		expect(exportResponses[0].disposition).toContain('filename="contacts.vcf"');
	});

	test('správa štítků vytvoří, přejmenuje a smaže štítek', async ({ page }) => {
		await openApp(page, '/contacts');

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		const nav = sidebar.getByRole('navigation', { name: 'Zobrazení kontaktů' });
		await sidebar.getByRole('button', { name: 'Spravovat štítky' }).click();

		// Named, because the delete confirmation below is a second role=dialog.
		const dialog = page.getByRole('dialog', { name: 'Štítky kontaktů' });
		await expect(dialog).toBeVisible();

		// Vytvoření: pole se vyprázdní a štítek se objeví v sidebaru s nulou.
		await dialog.getByLabel('Název nového štítku').fill('Škola');
		await dialog.getByRole('button', { name: 'Přidat štítek' }).click();
		await expect(dialog.getByLabel('Název nového štítku')).toHaveValue('');
		await expect(nav.getByRole('link', { name: 'Škola 0 kontaktů' })).toBeVisible();

		// Duplicita liší se jen velikostí písmen -> 409 inline, ne vytvoření.
		await dialog.getByLabel('Název nového štítku').fill('škola');
		await dialog.getByRole('button', { name: 'Přidat štítek' }).click();
		await expect(dialog.getByRole('alert')).toBeVisible();
		await expect(nav.getByRole('link', { name: /Škola/ })).toHaveCount(1);

		// Přejmenování přepíše i název na kontaktech, které štítek nesou.
		await dialog.getByRole('button', { name: 'Upravit štítek Klienti' }).click();
		const renameField = dialog.getByLabel('Nový název štítku Klienti');
		await expect(renameField).toBeFocused();
		await renameField.fill('Zákazníci');
		await dialog.getByRole('button', { name: 'Uložit' }).click();
		await expect(nav.getByRole('link', { name: 'Zákazníci 1 kontakt' })).toBeVisible();

		// Smazání se ptá inline a jmenuje počet dotčených kontaktů. Potvrzení je
		// uvnitř téhož dialogu, ne vnořený modál — fokus tak nikdy neopustí
		// otevřený dialog.
		await dialog.getByRole('button', { name: 'Smazat štítek Zákazníci' }).click();
		const confirm = dialog.getByRole('alert').filter({ hasText: 'Smazat štítek Zákazníci?' });
		await expect(confirm).toBeVisible();
		await expect(confirm).toContainText('1 kontaktu');
		await confirm.getByRole('button', { name: 'Smazat', exact: true }).click();

		await expect(nav.getByRole('link', { name: /Zákazníci/ })).toHaveCount(0);
		// Kontakt přežil, jen přišel o štítek.
		await dialog.getByRole('button', { name: 'Zavřít' }).click();
		await expect(page.getByText('Jana Novak')).toBeVisible();
		await expect(page.getByRole('gridcell', { name: 'Bez štítku' })).toBeVisible();
	});

	test('hromadné přiřazení štítků respektuje smíšený stav výběru', async ({ page }) => {
		await openApp(page, '/contacts');
		// Druhý kontakt bez štítků, aby výběr měl u Klientů smíšený stav.
		await bulkCreateContacts(page, [
			{ name: 'Bez', surname: 'Stitku', emails: [{ email: 'bez@example.com' }] }
		]);
		// Re-query through the app — a reload would reset the MSW fixtures and
		// take the seeded contact with them.
		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts?q=example.com');
		await expect(page.getByText('Bez Stitku')).toBeVisible();

		const assignRequests: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/contact-labels\/assignments$/.test(request.url())) {
				assignRequests.push(request.postDataJSON());
			}
		});

		await page.getByLabel('Vybrat kontakt Jana Novak').check();
		await page.getByLabel('Vybrat kontakt Bez Stitku').check();
		await page.getByRole('button', { name: 'Přiřadit štítky' }).click();

		const dialog = page.getByRole('dialog', { name: 'Štítky vybraných kontaktů' });
		const klienti = dialog.getByRole('checkbox', { name: /Klienti/ });
		const rodina = dialog.getByRole('checkbox', { name: /Rodina/ });
		// Klienti má jen jeden ze dvou vybraných -> mixed; Rodina nemá nikdo.
		await expect(klienti).toHaveJSProperty('indeterminate', true);
		await expect(rodina).not.toBeChecked();
		await expect(rodina).toHaveJSProperty('indeterminate', false);

		// Bez jediné změny je Použít neaktivní — dialog nesmí smíšený stav
		// zploštit jen tím, že ho uživatel otevřel.
		await expect(dialog.getByRole('button', { name: 'Použít štítky' })).toBeDisabled();

		await rodina.check();
		await dialog.getByRole('button', { name: 'Použít štítky' }).click();

		await expect(
			page.getByRole('region', { name: 'Oznámení' }).getByText(/Štítky upraveny u 2 kontaktů z 2/)
		).toBeVisible();
		expect(assignRequests).toHaveLength(1);
		// Klienti zůstali mimo obě pole — nedotčený smíšený štítek se neposílá.
		expect(assignRequests[0]).toEqual({
			contactIds: [1, 2],
			addLabelIds: [2],
			removeLabelIds: []
		});

		const nav = page.getByRole('navigation', { name: 'Zobrazení kontaktů' });
		await expect(nav.getByRole('link', { name: 'Rodina 2 kontakty' })).toBeVisible();
	});

	test('formulář kontaktu ukládá zaškrtnuté štítky', async ({ page }) => {
		const putBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'PUT' && /\/contacts\/1$/.test(request.url())) {
				putBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts?edit=1');

		const labels = page.getByRole('group', { name: 'Štítky' });
		await expect(labels.getByRole('checkbox', { name: 'Klienti' })).toBeChecked();
		await expect(labels.getByRole('checkbox', { name: 'Rodina' })).not.toBeChecked();

		await labels.getByRole('checkbox', { name: 'Rodina' }).check();
		await labels.getByRole('checkbox', { name: 'Klienti' }).uncheck();
		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL('**/contacts');
		expect(putBodies).toHaveLength(1);
		expect((putBodies[0] as { labelIds: number[] }).labelIds).toEqual([2]);
		await expect(page.getByRole('gridcell', { name: 'Rodina' })).toBeVisible();
	});

	test('vCard import vytvoří chybějící štítky z CATEGORIES', async ({ page }) => {
		const bulkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/contacts\/bulk$/.test(request.url())) {
				bulkBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, '/contacts');
		// Gate on the rendered list: the drop listener lives on window and is only
		// attached once the page has hydrated, so dispatching earlier is a no-op.
		await expect(page.getByText('Jana Novak')).toBeVisible();

		// "Klienti" už existuje (id 1) — musí se použít, ne vytvořit znovu;
		// "Kolegové" je nový. Escapovaná čárka drží jeden název pohromadě.
		const vcardText =
			'BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Petr Kategorie\r\nEMAIL:petr.kat@example.com\r\nCATEGORIES:klienti,Kolegové\\, externí\r\nEND:VCARD\r\n';

		await page.evaluate((text) => {
			const file = new File([text], 'labels.vcf', { type: 'text/vcard' });
			const dataTransfer = new DataTransfer();
			dataTransfer.items.add(file);
			const drop = new Event('drop', { bubbles: true, cancelable: true });
			Object.defineProperty(drop, 'dataTransfer', { value: dataTransfer, configurable: true });
			(document.body ?? document).dispatchEvent(drop);
		}, vcardText);

		await expect(page.getByText('Petr Kategorie')).toBeVisible();

		const nav = page.getByRole('navigation', { name: 'Zobrazení kontaktů' });
		await expect(nav.getByRole('link', { name: 'Kolegové, externí 1 kontakt' })).toBeVisible();
		// Existující štítek se nezaložil podruhé, jen se navýšil jeho počet.
		await expect(nav.getByRole('link', { name: 'Klienti 2 kontakty' })).toBeVisible();

		expect(bulkBodies).toHaveLength(1);
		const sent = bulkBodies[0] as { contacts: Array<{ labelIds: number[] }> };
		expect(sent.contacts[0].labelIds).toHaveLength(2);
		expect(sent.contacts[0].labelIds).toContain(1);
	});

	test('seznam se vykreslí i proti backendu, který štítky ještě nezná', async ({ page }) => {
		// Odpověď bez klíče `labels`, počty v předštítkovém tvaru a /contact-labels
		// vracející 500 — přesně to, co posílal starší sidecar v #252. Tehdy
		// `c.labels.length` shodilo vykreslení a seznam zůstal prázdný.
		await setMockFlags(page, { contactsLegacyShape: true });

		await openApp(page, '/contacts');

		await expect(page.getByText('Jana Novak')).toBeVisible();
		await expect(page.getByRole('gridcell', { name: 'jana@example.com' })).toBeVisible();
		// Chybějící seznam štítků čte řádek jako "bez štítku", ne jako pád.
		await expect(page.getByRole('gridcell', { name: 'Bez štítku' })).toBeVisible();
		await expect(page.getByRole('alert')).toHaveCount(0);
	});

	test('řádek, který renderer nezvládne, skončí chybou s možností načíst znovu', async ({
		page
	}) => {
		// Pole, kterému se změnil typ — třída driftu, kterou doplnění chybějícího
		// pole na hranici API neopraví. Bez hranice zůstala stránka viset na
		// "Načítám…" bez hlášky a bez čeho se chytit (#252).
		await setMockFlags(page, { contactsBrokenRow: true });

		await openApp(page, '/contacts');

		await expect(page.getByRole('alert')).toContainText('Seznam kontaktů se nepodařilo zobrazit');
		await expect(page.getByText('Načítám…')).toHaveCount(0);

		// Tlačítko musí opravdu znovu načítat: reset mocků vrátí odpovědi do
		// dnešního tvaru (backend mezitím doběhl do správné verze) a teprve pak
		// má klik smysl.
		const retry = page.getByRole('button', { name: 'Načíst kontakty znovu' });
		await expect(retry).toBeVisible();
		await page.evaluate(() => {
			window.localStorage.setItem('mail.e2e.contactsBrokenRow', '0');
			window.__MAIL_MSW__?.reset();
		});
		await retry.click();

		await expect(page.getByText('Jana Novak')).toBeVisible();
		await expect(page.getByRole('alert')).toHaveCount(0);
	});
});
