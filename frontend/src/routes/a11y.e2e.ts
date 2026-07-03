import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

test.setTimeout(60000);

const mailFixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-01'
};

/**
 * Všechny obrazovky aplikace, které mají projít základní a11y kontrolou.
 * `/` není v seznamu – rovnou se přesměruje; pokrývají ho ostatní testy níže.
 *
 * Trasy s parametry používají placeholder `1`; pokud backend nejede (což je
 * případ `npm run preview`), obrazovky zůstanou v error/waiting state – to je
 * pro axe scan v pořádku, jen nesmí obsahovat porušení přístupnosti.
 */
const routes: ReadonlyArray<{ path: string; name: string }> = [
	{ path: '/settings/appearance', name: 'Nastavení vzhledu' },
	{ path: '/settings/language', name: 'Nastavení jazyka' },
	{ path: '/settings/shortcuts', name: 'Nastavení klávesových zkratek' },
	{ path: '/settings/about', name: 'Nastavení aplikace' },
	{ path: '/settings/accounts', name: 'Seznam účtů' },
	{ path: '/settings/accounts/new', name: 'Nový účet' },
	{ path: '/settings/accounts/1', name: 'Detail účtu' },
	{ path: '/compose', name: 'Nová zpráva' },
	{ path: '/contacts', name: 'Kontakty bez účtu' },
	{ path: '/contacts/1', name: 'Kontakty' },
	{ path: '/contacts/1?create=1', name: 'Nový kontakt' },
	{ path: '/search/1?q=test', name: 'Hledání' },
	{ path: '/mail/1/INBOX', name: 'Výpis složky' },
	{
		path: `/mail/${mailFixture.accountId}/${mailFixture.folderName}/${mailFixture.stableId}`,
		name: 'Detail zprávy'
	},
	{ path: '/auth/finished', name: 'Návrat z OAuth' }
];

/**
 * Layout renderuje `<main>` až poté, co se načte i18n. Čekání na `main`
 * eliminuje race, kdy axe skenuje jen placeholder „…".
 */
async function waitForShell(page: Page): Promise<void> {
	await page.waitForSelector('main', { state: 'attached' });
}

async function openPalette(page: Page): Promise<void> {
	await page.waitForFunction(() => typeof window.__MAIL_E2E__?.openPalette === 'function');
	await page.evaluate(() => {
		window.__MAIL_E2E__?.openPalette();
	});
	await expect(page.locator('[role="dialog"]')).toBeVisible();
}

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.e2e', '1');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Přístupnost', () => {
	test('hlavní stránka nemá a11y porušení', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
		expect(results.violations).toEqual([]);
	});

	test('skip-link odkazy jsou přítomné a funkční', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		const skipLink = page.locator('a[href="#main-content"]');
		await expect(skipLink).toBeAttached();

		await skipLink.focus();
		await expect(skipLink).toBeVisible();

		const shortcutsLink = page.getByRole('link', { name: 'Přejít na klávesové zkratky' });
		await expect(shortcutsLink).toHaveAttribute('href', '/settings/shortcuts');
		await shortcutsLink.focus();
		await expect(shortcutsLink).toBeVisible();
	});

	test('stránka má správné ARIA landmarks', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		await expect(page.locator('main')).toBeAttached();
		await expect(page.locator('nav[aria-label="Přepínač prostředí"]')).toBeAttached();
	});

	test('stránka má nastavený jazyk', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		const lang = await page.locator('html').getAttribute('lang');
		expect(lang).toBe('cs');
	});

	test('detail pošty v režimu right obsahuje přístupný separator', async ({ page }) => {
		await page.goto(
			`/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}/${encodeURIComponent(mailFixture.stableId)}`
		);
		await waitForShell(page);
		await expect(
			page.locator('[role="separator"][aria-orientation="vertical"][tabindex="0"]')
		).toBeAttached();
	});

	test('detail pošty v režimu off zůstává bez split separatoru', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'off');
		});
		await page.goto(
			`/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}/${encodeURIComponent(mailFixture.stableId)}`
		);
		await waitForShell(page);
		await expect(
			page.locator('[role="separator"][aria-orientation="vertical"][tabindex="0"]')
		).toHaveCount(0);
	});

	test('search landmark není vnořený v navigaci (pošta i kontakty)', async ({ page }) => {
		// Sidebar je pojmenovaný region; hledání a <nav> se seznamem složek v něm
		// stojí vedle sebe — search uvnitř nav je sémanticky špatně.
		await page.goto(`/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}`);
		await waitForShell(page);
		const mailPane = page.getByRole('region', { name: 'Podokno složek' });
		await expect(mailPane.getByRole('search')).toHaveCount(1);
		await expect(mailPane.getByRole('navigation', { name: 'Složky' })).toBeVisible();
		await expect(page.getByRole('navigation').getByRole('search')).toHaveCount(0);

		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);
		const contactsPane = page.getByRole('region', { name: 'Podokno kontaktů' });
		await expect(contactsPane.getByRole('search')).toHaveCount(1);
		await expect(page.getByRole('navigation').getByRole('search')).toHaveCount(0);
	});

	test('tlačítko exportu vCard je dostupné přes roli a název', async ({ page }) => {
		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);

		const sidebar = page.getByRole('region', { name: 'Podokno kontaktů' });
		const exportButton = sidebar.getByRole('button', { name: 'Exportovat vCard' });
		await expect(exportButton).toBeAttached();
		await expect(exportButton).toHaveAttribute('type', 'button');
		await expect(exportButton).toBeEnabled();
		const ariaBusy = await exportButton.getAttribute('aria-busy');
		expect(ariaBusy === null || ariaBusy === 'false').toBe(true);
	});

	test('formulář nového kontaktu má jednoznačně pojmenované seznamy štítků', async ({ page }) => {
		await page.goto(`/contacts/${mailFixture.accountId}?create=1`);
		await waitForShell(page);

		const firstLabelSelect = page.getByRole('combobox', { name: 'Štítek adresy 1' });
		await expect(firstLabelSelect).toHaveCount(1);
		await expect(firstLabelSelect).toHaveAccessibleName('Štítek adresy 1');
		await expect(firstLabelSelect).toHaveValue('');
		await expect(page.locator('#contact-email-0-label-status')).toHaveText(
			'Štítek adresy 1: Bez štítku'
		);
		await firstLabelSelect.evaluate((element) => {
			const select = element as HTMLSelectElement & { __showPickerCalls?: number };
			select.__showPickerCalls = 0;
			select.showPicker = () => {
				select.__showPickerCalls = (select.__showPickerCalls ?? 0) + 1;
			};
		});
		await firstLabelSelect.focus();
		await page.keyboard.press('ArrowDown');
		await expect(firstLabelSelect).toHaveValue('');
		await expect
			.poll(() =>
				firstLabelSelect.evaluate(
					(element) =>
						(element as HTMLSelectElement & { __showPickerCalls?: number }).__showPickerCalls ?? 0
				)
			)
			.toBe(1);
		await firstLabelSelect.selectOption('WORK');
		await expect(page.locator('#contact-email-0-label-status')).toHaveText(
			'Štítek adresy 1: Práce'
		);
		await firstLabelSelect.selectOption('HOME');
		await expect(firstLabelSelect).toHaveValue('HOME');
		await expect(page.locator('#contact-email-0-label-status')).toHaveText(
			'Štítek adresy 1: Domov'
		);

		await page.getByRole('button', { name: 'Přidat e-mail' }).click();

		const secondLabelSelect = page.getByRole('combobox', { name: 'Štítek adresy 2' });
		await expect(secondLabelSelect).toHaveCount(1);
		await expect(secondLabelSelect).toHaveAccessibleName('Štítek adresy 2');
		await expect(secondLabelSelect).toHaveValue('');
		await expect(page.locator('#contact-email-1-label-status')).toHaveText(
			'Štítek adresy 2: Bez štítku'
		);
	});

	test('filtr štítků v kontaktech používá standardní select s explicitním použitím', async ({
		page
	}) => {
		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);

		const labelFilter = page.getByRole('combobox', { name: 'Filtr podle štítku' });
		const applyFilter = page.getByRole('button', { name: 'Použít filtr' });
		await expect(labelFilter).toHaveCount(1);
		await expect(labelFilter).toHaveAccessibleName('Filtr podle štítku');
		await expect(labelFilter).toHaveValue('');
		await expect(page.getByRole('listbox')).toHaveCount(0);
		await expect(applyFilter).toBeDisabled();

		await labelFilter.selectOption('WORK');
		await expect(labelFilter).toHaveValue('WORK');
		expect(new URL(page.url()).searchParams.get('label')).toBeNull();
		expect(new URL(page.url()).searchParams.get('sort')).toBeNull();
		await expect(applyFilter).toBeEnabled();
		await applyFilter.click();
		await page.waitForURL('**/contacts/1?label=WORK');
	});

	test('řazení kontaktů používá standardní select s dostupným názvem a stavem', async ({
		page
	}) => {
		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);

		const sortSelect = page.getByRole('combobox', { name: 'Řadit podle' });
		const applyFilter = page.getByRole('button', { name: 'Použít filtr' });
		await expect(sortSelect).toHaveCount(1);
		await expect(sortSelect).toHaveAccessibleName('Řadit podle');
		await expect(sortSelect).toHaveValue('surname');
		await expect(page.getByRole('listbox')).toHaveCount(0);
		await expect(applyFilter).toBeDisabled();

		await sortSelect.selectOption('name');
		await expect(sortSelect).toHaveValue('name');
		expect(new URL(page.url()).searchParams.get('sort')).toBeNull();
		expect(new URL(page.url()).searchParams.get('label')).toBeNull();
		await expect(applyFilter).toBeEnabled();
		await applyFilter.click();
		await page.waitForURL('**/contacts/1?sort=name');
		await expect(sortSelect).toHaveValue('name');
	});

	test('export vCard oznamuje busy stav a success/error toast přístupně', async ({ page }) => {
		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);
		await page.waitForFunction(
			() =>
				typeof window.__MAIL_MSW__?.setVCardExportDelayMs === 'function' &&
				typeof window.__MAIL_MSW__?.failNextVCardExport === 'function'
		);

		await page.evaluate(() => {
			window.__MAIL_MSW__?.setVCardExportDelayMs(1000);
		});

		const exportButton = page.getByRole('button', { name: 'Exportovat vCard' });
		const downloadPromise = page.waitForEvent('download');
		await exportButton.click();

		await expect(exportButton).toBeDisabled();
		await expect(exportButton).toHaveAttribute('aria-busy', 'true');
		await expect(exportButton).toHaveText('Exportuji…');

		const download = await downloadPromise;
		expect(download.suggestedFilename()).toBe('contacts-1.vcf');
		await expect(exportButton).toBeEnabled();
		await expect(exportButton).toHaveAttribute('aria-busy', 'false');

		const successToast = page.getByRole('status').filter({ hasText: 'Adresář byl exportován.' });
		await expect(successToast).toBeVisible();
		await expect(successToast.getByRole('button', { name: 'Zavřít oznámení' })).toBeVisible();

		await page.evaluate(() => {
			window.__MAIL_MSW__?.setVCardExportDelayMs(0);
			window.__MAIL_MSW__?.failNextVCardExport();
		});
		await exportButton.click();

		const errorToast = page.getByRole('alert').filter({ hasText: /API error 500/ });
		await expect(errorToast).toBeVisible();
		await expect(errorToast.getByRole('button', { name: 'Zavřít oznámení' })).toBeVisible();
	});

	test('dialog sloučení kontaktů nemá a11y porušení a preview oznamuje výsledek živě', async ({
		page
	}) => {
		await page.goto(`/contacts/${mailFixture.accountId}`);
		await waitForShell(page);
		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.reset === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.reset());

		// Default fixture has a single contact; a merge needs at least two.
		const created = await page.evaluate(async () => {
			const res = await fetch('/api/v1/accounts/1/contacts/bulk', {
				method: 'POST',
				headers: {
					Accept: 'application/json',
					'Content-Type': 'application/json',
					'X-API-KEY': 'e2e-test-key'
				},
				body: JSON.stringify({
					contacts: [
						{ name: 'Jan', surname: 'Novak', emails: [{ email: 'jan@example.com', label: 'WORK' }] }
					]
				})
			});
			return res.status;
		});
		expect(created).toBe(200);

		await page.locator('#contacts-sidebar-search').fill('example.com');
		await page.keyboard.press('Enter');
		await page.waitForURL('**/contacts/1?q=example.com');
		await expect(page.getByText('Jan Novak')).toBeVisible();

		await page.getByLabel('Vybrat kontakt Jana Novak').check();
		await page.getByLabel('Vybrat kontakt Jan Novak').check();
		await page.getByRole('button', { name: 'Sloučit' }).click();

		const dialog = page.getByRole('dialog', { name: 'Sloučit kontakty' });
		await expect(dialog).toBeVisible();

		// The merge target is chosen from a labelled radio group (fieldset/legend).
		await expect(dialog.getByRole('group', { name: 'Hlavní kontakt po sloučení' })).toBeVisible();

		// The preview lives in a polite, atomic live region so a screen reader reads
		// the whole resulting address set — and re-reads it when the target changes.
		const preview = dialog.locator('section[aria-live="polite"][aria-atomic="true"]');
		await expect(preview).toHaveCount(1);
		await expect(preview.getByRole('heading', { name: /Po sloučení \(3 e-maily\)/ })).toBeVisible();

		const results = await new AxeBuilder({ page })
			.include('[role="dialog"]')
			.withTags(['wcag2a', 'wcag2aa'])
			.analyze();
		expect(results.violations).toEqual([]);
	});

	test('AppRail je přítomný a aktivní tlačítko odpovídá módu', async ({ page }) => {
		const cases = [
			{
				path: `/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}`,
				label: 'Pošta (Ctrl+1)'
			},
			{ path: `/contacts/${mailFixture.accountId}`, label: 'Kontakty (Ctrl+2)' },
			{ path: '/settings/appearance', label: 'Nastavení (Ctrl+3)' }
		];

		for (const testCase of cases) {
			await page.goto(testCase.path);
			await waitForShell(page);
			const rail = page.locator('nav[aria-label="Přepínač prostředí"]');
			await expect(rail).toBeAttached();
			await expect(rail.locator('a[aria-current="page"]')).toHaveAccessibleName(testCase.label);
		}
	});

	test('nastavení vzhledu má čitelnou osnovu a select podokna čtení', async ({ page }) => {
		await page.goto('/settings/appearance');
		await waitForShell(page);

		await expect(page.getByRole('heading', { level: 1, name: 'Vzhled' })).toBeVisible();
		await expect(page.getByRole('heading', { level: 2, name: 'Téma' })).toBeVisible();
		await expect(page.getByRole('heading', { level: 2, name: 'Podokno čtení' })).toBeVisible();

		// The theme/text-size field labels are visually hidden (the card heading
		// shows the same text), so the selects must keep their accessible names.
		await expect(page.getByRole('combobox', { name: 'Téma' })).toHaveCount(1);
		await expect(page.getByRole('combobox', { name: 'Velikost textu' })).toHaveCount(1);

		const readingPaneSelect = page.getByRole('combobox', { name: 'Rozložení podokna čtení' });
		await expect(readingPaneSelect).toHaveCount(1);
		await expect(readingPaneSelect).toHaveAccessibleName('Rozložení podokna čtení');
		await expect(readingPaneSelect).toHaveValue('right');
		await expect(readingPaneSelect.locator('option')).toHaveText(['Vpravo', 'Dole', 'Skryté']);
	});

	test('nový účet vystavuje provider/custom přepínač a chybové stavy přes role', async ({
		page
	}) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Nastavit ručně' }).click();
		await expect(page.locator('#acc-email')).toBeFocused();

		const serverModeGroup = page.getByRole('group', { name: 'Konfigurace serveru' });
		await expect(serverModeGroup).toBeVisible();
		await expect(
			serverModeGroup.getByRole('radio', { name: 'Vybrat poskytovatele', exact: true })
		).toHaveAccessibleName('Vybrat poskytovatele');
		await expect(
			serverModeGroup.getByRole('radio', { name: 'Vlastní nastavení', exact: true })
		).toHaveAccessibleName('Vlastní nastavení');

		await page.getByRole('radio', { name: 'Vybrat poskytovatele' }).click();
		await page.locator('#acc-email').fill('manual-no-provider@unknown.invalid');
		await page.locator('#acc-accountName').fill('Bez providera');
		await page.locator('#acc-username').fill('manual-no-provider@unknown.invalid');
		await page.locator('#acc-password').fill('secret123');
		await page.getByRole('button', { name: 'Vytvořit účet' }).click();

		await expect(page.getByRole('alert')).toContainText('Vyberte poskytovatele.');
		await expect(page.locator('#acc-provider')).toBeVisible();

		await page.getByRole('radio', { name: 'Vlastní nastavení' }).click();
		await page.getByRole('button', { name: 'Vytvořit účet' }).click();

		await expect(page.getByRole('alert')).toContainText('Vyplňte hostitele IMAP serveru.');
		await expect(page.locator('#acc-imap-host')).toBeVisible();
	});

	test('compose validace, autosave stav a dialog neuložených změn jsou přístupné', async ({
		page
	}) => {
		await page.goto('/compose');
		await waitForShell(page);

		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
		await page.locator('#compose-subject').fill('A11y bez příjemce');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		const toInput = page.locator('#compose-to');
		await expect(toInput).toHaveAttribute('aria-invalid', 'true');
		await expect(toInput).toHaveAttribute('aria-describedby', 'compose-to-error');
		await expect(page.locator('#compose-to-error')).toHaveAttribute('role', 'alert');
		await expect(page.locator('#compose-to-error')).toContainText('Vyplňte adresu příjemce.');

		await toInput.fill('autosave-a11y@example.com');
		await page.locator('#compose-body').fill('Text pro ověření autosave stavu.');
		await expect(
			page.getByRole('status').filter({ hasText: /Koncept uložen|Ukládám/ })
		).toBeVisible({ timeout: 8000 });

		await page.locator('#compose-body').fill('Rozepsaná změna pro dialog.');
		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();

		const dialog = page.getByRole('dialog', { name: 'Máte neuložené změny' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Rozepsaná zpráva obsahuje změny');
		await expect(dialog.getByRole('button', { name: 'Zůstat' })).toBeVisible();
		await expect(dialog.getByRole('button', { name: 'Uložit koncept' })).toBeVisible();
		await expect(dialog.getByRole('button', { name: 'Zahodit změny' })).toBeVisible();
	});

	test('Esc na detailu zprávy zavře detail a vrátí fokus do seznamu', async ({ page }) => {
		const folderHref = `/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}`;
		const detailHref = `${folderHref}/${encodeURIComponent(mailFixture.stableId)}`;

		await page.goto(detailHref);
		await waitForShell(page);

		await expect(
			page.locator(`[role="row"][data-stable-id="${mailFixture.stableId}"]`)
		).toHaveAttribute('aria-current', 'page');

		await page.locator('main').focus();
		await page.keyboard.press('Escape');

		await page.waitForURL((url) => url.pathname === folderHref);
		await expect(
			page.locator(`[role="row"][data-stable-id="${mailFixture.stableId}"]`)
		).not.toHaveAttribute('aria-current', 'page');
	});

	test('MessageList podporuje Home, End, PageDown a PageUp', async ({ page }) => {
		await page.goto(`/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam zpráv' });
		await expect(grid).toBeAttached();
		const rows = grid.locator('[role="row"][data-stable-id]');
		await expect(rows.first()).toBeAttached();

		const firstId = await rows.first().getAttribute('data-stable-id');
		const lastId = await rows.last().getAttribute('data-stable-id');
		const count = await rows.count();
		expect(count).toBeGreaterThan(10);
		if (!firstId || !lastId) throw new Error('MessageList neobsahuje data-stable-id atributy.');

		const subjectCell = (id: string) =>
			page.locator(`[role="row"][data-stable-id="${id}"] [data-cell-target][data-col="1"]`);

		// Each of these keys both moves the roving cell AND opens the message, so
		// focus settles asynchronously (selectAndFocus → goto → tick → focusCell).
		// Poll the active cell (row + column) rather than asserting once, otherwise
		// a late-resolving in-flight goto can re-focus and flake the check.
		const activeCell = () =>
			page.evaluate(() => {
				const el = document.activeElement;
				return {
					stableId: el?.closest('[data-stable-id]')?.getAttribute('data-stable-id') ?? null,
					col: el?.getAttribute('data-col') ?? null
				};
			});

		await subjectCell(firstId).focus();
		await page.keyboard.press('Control+End');
		await expect.poll(activeCell).toEqual({ stableId: lastId, col: '1' });

		await page.keyboard.press('Control+Home');
		await expect.poll(activeCell).toEqual({ stableId: firstId, col: '1' });

		await page.keyboard.press('PageDown');
		await expect.poll(async () => (await activeCell()).stableId).not.toBe(firstId);
		expect((await activeCell()).stableId).toBeTruthy();

		await page.keyboard.press('PageUp');
		await expect.poll(activeCell).toEqual({ stableId: firstId, col: '1' });
	});

	test('MessageList ArrowLeft/ArrowRight přepíná mezi buňkami v rámci řádku', async ({ page }) => {
		await page.goto(`/mail/${mailFixture.accountId}/${encodeURIComponent(mailFixture.folderName)}`);
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Seznam zpráv' });
		await expect(grid).toBeAttached();
		const firstId = await grid
			.locator('[role="row"][data-stable-id]')
			.first()
			.getAttribute('data-stable-id');
		if (!firstId) throw new Error('MessageList neobsahuje data-stable-id atributy.');

		const cell = (col: number) =>
			page.locator(
				`[role="row"][data-stable-id="${firstId}"] [data-cell-target][data-col="${col}"]`
			);

		await cell(1).focus();
		await expect(cell(1)).toBeFocused();

		await page.keyboard.press('ArrowRight');
		await expect(cell(2)).toBeFocused();

		await page.keyboard.press('ArrowRight');
		await expect(cell(3)).toBeFocused();

		await page.keyboard.press('ArrowRight');
		await expect(cell(4)).toBeFocused();

		await page.keyboard.press('ArrowRight');
		await expect(cell(5)).toBeFocused();

		await page.keyboard.press('ArrowRight');
		await expect(cell(5)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(4)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(3)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(2)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(1)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(0)).toBeFocused();

		await page.keyboard.press('ArrowLeft');
		await expect(cell(0)).toBeFocused();
	});

	test('výsledky hledání tvoří grid s navigací po buňkách a otevření přesune fokus na text zprávy', async ({
		page
	}) => {
		await page.goto('/search/1?q=test');
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Výsledky' });
		await expect(grid).toBeAttached();
		const firstId = await grid
			.locator('[role="row"][data-stable-id]')
			.first()
			.getAttribute('data-stable-id');
		if (!firstId) throw new Error('SearchResultsGrid neobsahuje data-stable-id atributy.');

		const cell = (col: number) =>
			page.locator(
				`[role="row"][data-stable-id="${firstId}"] [data-cell-target][data-col="${col}"]`
			);

		// Roving cell navigation: subject (1) → sender (2) → End jumps to the
		// trailing actions column (5), Home returns to the status cell (0).
		await cell(1).focus();
		await expect(cell(1)).toBeFocused();
		await page.keyboard.press('ArrowRight');
		await expect(cell(2)).toBeFocused();
		await page.keyboard.press('End');
		await expect(cell(5)).toBeFocused();
		await page.keyboard.press('Home');
		await expect(cell(0)).toBeFocused();

		// Opening a result from a content cell swaps the list for the detail in
		// place (no route change). Focus first anchors on <main> (so it cannot
		// fall back to <body> when the focused grid cell unmounts) and then
		// lands on the message body once it renders (MessageContent.svelte).
		await page.keyboard.press('Enter');
		await expect(page.getByRole('toolbar', { name: 'Akce se zprávami' })).toBeVisible();
		// activeElement check instead of a `:focus` locator — Chromium does not
		// match the pseudo-class on a programmatically focused <iframe>.
		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region).toBeVisible();
		await expect
			.poll(() => region.evaluate((el) => el.contains(document.activeElement)))
			.toBe(true);
	});

	test('AccountForm vystavuje per-field aria-invalid u IMAP/SMTP polí', async ({ page }) => {
		await page.goto('/settings/accounts/new');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Nastavit ručně' }).click();
		await page.getByRole('radio', { name: 'Vlastní nastavení' }).click();

		await page.locator('#acc-email').fill('manual@example.com');
		await page.locator('#acc-accountName').fill('Manuálně');
		await page.locator('#acc-username').fill('manual@example.com');
		await page.locator('#acc-password').fill('secret123');
		await page.getByRole('button', { name: 'Vytvořit účet' }).click();

		const imapHost = page.locator('#acc-imap-host');
		await expect(imapHost).toHaveAttribute('aria-invalid', 'true');
		await expect(imapHost).toHaveAttribute('aria-describedby', 'acc-imap-host-error');
		await expect(imapHost).toBeFocused();
		await expect(page.locator('#acc-imap-host-error')).toHaveAttribute('role', 'alert');
		await expect(page.locator('#acc-imap-host-error')).toContainText(
			'Vyplňte hostitele IMAP serveru.'
		);

		await imapHost.fill('imap.example.com');
		await page.locator('#acc-imap-port').fill('99999');
		await page.getByRole('button', { name: 'Vytvořit účet' }).click();

		const imapPort = page.locator('#acc-imap-port');
		await expect(imapPort).toHaveAttribute('aria-invalid', 'true');
		await expect(imapPort).toHaveAttribute('aria-describedby', 'acc-imap-port-error');
		await expect(page.locator('#acc-imap-port-error')).toHaveAttribute('role', 'alert');
	});

	test('AddressTokenField listbox propojuje aktivní option přes aria-activedescendant', async ({
		page
	}) => {
		await page.goto('/compose');
		await waitForShell(page);

		const toInput = page.locator('#compose-to');
		await toInput.fill('jana');

		const listbox = page.locator('#compose-to-suggestions');
		await expect(listbox).toBeVisible();
		await expect(listbox).toHaveAttribute('role', 'listbox');

		const activeId = await toInput.getAttribute('aria-activedescendant');
		expect(activeId).toBeTruthy();
		if (!activeId) throw new Error('AddressTokenField nepředal aria-activedescendant.');
		expect(activeId).toMatch(/^compose-to-suggestion-\d+$/);

		const activeOption = page.locator(`#${activeId}`);
		await expect(activeOption).toHaveAttribute('role', 'option');
		await expect(activeOption).toHaveAttribute('aria-selected', 'true');

		await expect(toInput).toBeFocused();
	});

	test('command palette dialog nemá a11y porušení', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		await openPalette(page);

		const results = await new AxeBuilder({ page })
			.include('[role="dialog"]')
			.withTags(['wcag2a', 'wcag2aa'])
			.analyze();

		expect(results.violations).toEqual([]);
	});

	test('command palette drží fokus ve vyhledávání a aktivní příkaz předává přes combobox', async ({
		page
	}) => {
		await page.goto('/');
		await waitForShell(page);
		await openPalette(page);

		const input = page.locator('#command-palette-input');
		await expect(input).toBeFocused();
		const initialActiveId = await input.getAttribute('aria-activedescendant');
		expect(initialActiveId).toBeTruthy();
		if (!initialActiveId)
			throw new Error('Command palette did not expose an initial active option.');
		await expect(page.locator(`#${initialActiveId}`)).toHaveAttribute('role', 'option');

		await input.press('ArrowDown');
		await expect(input).toBeFocused();
		const activeOptionId = await input.getAttribute('aria-activedescendant');
		expect(activeOptionId).toBeTruthy();
		if (!activeOptionId) throw new Error('Command palette did not expose an active option.');
		await expect(page.locator(`#${activeOptionId}`)).toHaveAttribute('role', 'option');

		await input.press('ArrowUp');
		await expect(input).toBeFocused();
		await expect(page.locator(`#${initialActiveId}`)).toHaveAttribute('role', 'option');
	});
});

test.describe('Přístupnost – jednotlivé obrazovky', () => {
	for (const { path, name } of routes) {
		test(`${name} (${path}) nemá a11y porušení`, async ({ page }) => {
			await page.goto(path);
			await waitForShell(page);
			const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze();
			expect(results.violations).toEqual([]);
		});

		test(`${name} (${path}) má živou oblast, skip-link a main landmark`, async ({ page }) => {
			await page.goto(path);
			await waitForShell(page);
			await expect(page.locator('a[href="#main-content"]')).toBeAttached();
			await expect(page.getByRole('link', { name: 'Přejít na klávesové zkratky' })).toBeAttached();
			await expect(page.locator('main#main-content')).toBeAttached();
			await expect(page.locator('#live-region[aria-live="polite"]')).toBeAttached();
		});
	}
});
