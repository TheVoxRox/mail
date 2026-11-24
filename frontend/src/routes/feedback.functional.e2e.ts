import { expect, test, type Page } from '@playwright/test';
import { openApp, setMockFlags, setPrefs } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

async function fillReport(page: Page): Promise<void> {
	await page.getByRole('radio', { name: 'Chyba v aplikaci' }).check();
	await page.getByRole('textbox', { name: 'Shrnutí jednou větou' }).fill('Přesunutá zpráva zmizí');
	await page
		.getByRole('textbox', { name: 'Popis' })
		.fill('Přesunu zprávu do Archivu a není ani tam, ani v Doručené.');
}

test.describe('Napište nám', () => {
	test('prázdný formulář označí všechna pole a fokus dá na první z nich', async ({ page }) => {
		await openApp(page, '/settings/feedback');

		await page.getByRole('button', { name: 'Pokračovat k odeslání' }).click();

		const firstRadio = page.getByRole('radio', { name: 'Chyba v aplikaci' });
		await expect(firstRadio).toBeFocused();
		await expect(firstRadio).toHaveAccessibleDescription('Vyberte, o co jde.');
		await expect(
			page.getByRole('textbox', { name: 'Shrnutí jednou větou' })
		).toHaveAccessibleDescription(/Napište stručné shrnutí\./);
		await expect(page.getByRole('textbox', { name: 'Popis' })).toHaveAccessibleDescription(
			/Napište popis\./
		);
		// Only the field focus went to speaks up; the others are read with their fields.
		await expect(page.getByRole('alert')).toHaveText('Vyberte, o co jde.');
		await expect(page).toHaveURL(/\/settings\/feedback$/);
	});

	test('nápověda k popisu se řídí typem hlášení', async ({ page }) => {
		await openApp(page, '/settings/feedback');
		const description = page.getByRole('textbox', { name: 'Popis' });

		await expect(description).toHaveAccessibleDescription('Napište nám, co potřebujete.');
		await page.getByRole('radio', { name: 'Nápad na vylepšení' }).check();
		await expect(description).toHaveAccessibleDescription('Co by aplikace měla umět a proč?');
	});

	test('hlášení přejde do nové zprávy i s diagnostikou a uloží se jako koncept', async ({
		page
	}) => {
		const draftBodies: { to?: string; subject?: string; attachments?: { fileName: string }[] }[] =
			[];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/api/v1/accounts/1/drafts')) {
				draftBodies.push(request.postDataJSON());
			}
		});
		await openApp(page, '/settings/feedback');

		await fillReport(page);
		await page.getByRole('checkbox', { name: 'Přiložit diagnostiku' }).check();
		await page.getByRole('button', { name: 'Pokračovat k odeslání' }).click();

		await page.waitForURL(/\/compose\?feedback=/);
		await expect(page.getByText('info@voxrox.org', { exact: true })).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue(
			'[VoxRox Mail] Chyba: Přesunutá zpráva zmizí'
		);
		const body = page.locator('#compose-body');
		await expect(body).toHaveValue(
			/^Přesunu zprávu do Archivu a není ani tam, ani v Doručené\.\n\nTechnické údaje:\n- Verze frontendu: /
		);
		await expect(body).toHaveValue(/\n- Jazyk rozhraní: Čeština/);
		await expect(page.getByText('mail-diagnostic.zip')).toBeVisible();

		// Nothing else holds the report now, so it saves itself without an edit.
		await expect.poll(() => draftBodies.length).toBeGreaterThan(0);
		expect(draftBodies[0]).toMatchObject({
			to: 'info@voxrox.org',
			subject: '[VoxRox Mail] Chyba: Přesunutá zpráva zmizí',
			attachments: [expect.objectContaining({ fileName: 'mail-diagnostic.zip' })]
		});

		// The form handed the report over and starts empty again.
		await page.goBack();
		await page.waitForURL(/\/settings\/feedback$/);
		await expect(page.getByRole('textbox', { name: 'Shrnutí jednou větou' })).toHaveValue('');
	});

	test('zahození předaného hlášení se zeptá', async ({ page }) => {
		await openApp(page, '/settings/feedback');
		await fillReport(page);
		await page.getByRole('button', { name: 'Pokračovat k odeslání' }).click();
		await page.waitForURL(/\/compose\?feedback=/);
		await expect(page.locator('#compose-subject')).toHaveValue(/Přesunutá zpráva zmizí/);

		await page.getByRole('button', { name: 'Zahodit', exact: true }).click();

		await expect(page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' })).toBeVisible();
	});

	test('rozepsaný text vydrží odchod na jinou stránku Nastavení', async ({ page }) => {
		await openApp(page, '/settings/feedback');
		await page.getByRole('textbox', { name: 'Shrnutí jednou větou' }).fill('Rozepsané shrnutí');

		const sidebar = page.getByRole('region', { name: 'Podokno nastavení' });
		await sidebar.getByRole('link', { name: 'O aplikaci' }).click();
		await page.waitForURL(/\/settings\/about$/);
		await sidebar.getByRole('link', { name: 'Napište nám' }).click();
		await page.waitForURL(/\/settings\/feedback$/);

		await expect(page.getByRole('textbox', { name: 'Shrnutí jednou větou' })).toHaveValue(
			'Rozepsané shrnutí'
		);
	});

	test('bez účtu nabídne jen náhradní cestu', async ({ page }) => {
		await setMockFlags(page, { noAccounts: true });
		await openApp(page, '/settings/feedback');

		await expect(page.getByText(/Nemáte nastavený žádný poštovní účet/)).toBeVisible();
		await expect(page.getByRole('button', { name: 'Pokračovat k odeslání' })).toHaveCount(0);
		await expect(page.getByRole('checkbox', { name: 'Přiložit diagnostiku' })).toHaveCount(0);
		await expect(page.getByRole('button', { name: 'Zkopírovat zprávu' })).toBeVisible();
	});

	test('zkopíruje zprávu i s adresou a předmětem', async ({ page }) => {
		await page.addInitScript(() => {
			Object.defineProperty(navigator, 'clipboard', {
				configurable: true,
				value: {
					writeText: async (text: string) => {
						(window as unknown as { copiedText?: string }).copiedText = text;
					}
				}
			});
		});
		await openApp(page, '/settings/feedback');
		await fillReport(page);

		await page.getByRole('button', { name: 'Zkopírovat zprávu' }).click();

		await expect(page.getByRole('status').filter({ hasText: 'Zpráva je zkopírovaná' })).toHaveText(
			'Zpráva je zkopírovaná do schránky.'
		);
		const copied = await page.evaluate(
			() => (window as unknown as { copiedText?: string }).copiedText
		);
		expect(copied).toMatch(
			/^Komu: info@voxrox\.org\nPředmět: \[VoxRox Mail\] Chyba: Přesunutá zpráva zmizí\n\nPřesunu zprávu/
		);
	});

	test('příkazová paleta otevře formulář', async ({ page }) => {
		await setMockFlags(page, { e2e: true });
		await openApp(page, '/');
		await page.waitForFunction(() => typeof window.__MAIL_E2E__?.openPalette === 'function');
		await page.evaluate(() => {
			window.__MAIL_E2E__?.openPalette();
		});
		const input = page.locator('#command-palette-input');
		await expect(input).toBeFocused();

		await input.fill('nahlásit chybu');
		await page.getByRole('option', { name: 'Nahlásit problém nebo navrhnout funkci' }).click();

		await page.waitForURL(/\/settings\/feedback$/);
		await expect(page.locator('main h1')).toHaveText('Napište nám');
	});
});
