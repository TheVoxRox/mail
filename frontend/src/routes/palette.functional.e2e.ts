import { expect, test, type Page } from '@playwright/test';
import { waitForRootRedirect, waitForShell } from './e2e-helpers';

const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-01',
	moveStableId: 'msg-03',
	replyPrefill: {
		to: 'Jana Novak <jana@example.com>',
		subject: 'Re: Projektové podklady'
	}
};

async function openPalette(page: Page): Promise<void> {
	await page.waitForFunction(() => typeof window.__MAIL_E2E__?.openPalette === 'function');
	await page.evaluate(() => {
		window.__MAIL_E2E__?.openPalette();
	});
	await expect(page.getByRole('dialog', { name: 'Příkazy' })).toBeVisible();
	await expect(page.locator('#command-palette-input')).toBeFocused();
	await expect(
		page.getByRole('listbox', { name: 'Výsledky příkazů' }).getByRole('option').first()
	).toBeVisible();
}

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.e2e', '1');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Command palette', () => {
	test('otevře se zkratkou a zavře se na Escape', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const dialog = page.locator('[role="dialog"]');
		await expect(dialog).toBeVisible();
		await expect(page.locator('#palette-title')).toHaveText('Příkazy');
		await expect(page.locator('#command-palette-input')).toBeFocused();

		await page.keyboard.press('Escape');
		await expect(dialog).toBeHidden();
	});

	test('umí navigovat do Nastavení přes filtrovaný příkaz', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const input = page.locator('#command-palette-input');
		await input.fill('nastavení');
		const settingsOption = page.getByRole('option', { name: /Přepnout na Nastavení/ });
		await expect(settingsOption).toBeVisible();
		await expect(input).toBeFocused();
		await settingsOption.click();

		await page.waitForURL('**/settings/appearance');
		await expect(page.locator('main h1')).toHaveText('Vzhled');
	});

	test('zobrazuje aktuální Ctrl zkratky pro přepínání pracovních sekcí', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		await page.locator('#command-palette-input').fill('pošta');
		const mailOption = page.getByRole('option', { name: /Přepnout na Poštu/ });

		await expect(mailOption).toContainText('Ctrl+1');
		await expect(mailOption).not.toContainText('Alt+1');
	});

	test('klíčová slova se zobrazí až po zadání dotazu', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const mailOption = page.getByRole('option', { name: /Přepnout na Poštu/ });
		await expect(mailOption).not.toContainText('posta, prepnout na postu, mail');

		await page.locator('#command-palette-input').fill('posta');
		await expect(mailOption).toContainText('posta, prepnout na postu, mail');
	});

	test('ve výchozím pořadí řadí přepnutí workspace před jednorázové view akce', async ({
		page
	}) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		await openPalette(page);
		const optionTexts = await page.getByRole('option').allTextContents();
		const contactsIndex = optionTexts.findIndex((text) => text.includes('Přepnout na Kontakty'));
		const settingsIndex = optionTexts.findIndex((text) => text.includes('Přepnout na Nastavení'));
		const languageIndex = optionTexts.findIndex((text) => text.includes('Přepnout jazyk na'));

		expect(contactsIndex).toBeGreaterThanOrEqual(0);
		expect(settingsIndex).toBeGreaterThanOrEqual(0);
		expect(languageIndex).toBeGreaterThanOrEqual(0);
		expect(contactsIndex).toBeLessThan(languageIndex);
		expect(settingsIndex).toBeLessThan(languageIndex);
	});

	test('Nová zpráva zůstává ve výchozí paletě před jednorázovými nastaveními', async ({ page }) => {
		await page.goto('/settings/language');
		await waitForShell(page);

		await openPalette(page);
		const optionTexts = await page.getByRole('option').allTextContents();
		const composeIndex = optionTexts.findIndex((text) => text.includes('Nová zpráva'));
		const languageIndex = optionTexts.findIndex((text) => text.includes('Přepnout jazyk na'));

		expect(composeIndex).toBeGreaterThanOrEqual(0);
		expect(languageIndex).toBeGreaterThanOrEqual(0);
		expect(composeIndex).toBeLessThan(languageIndex);
	});

	test('šipka dolů posune výběr jen o jeden příkaz', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);
		// Let the root → mailbox redirect settle before reading the option order
		// and pressing ArrowDown; otherwise the late re-derive can reset the
		// active item back to the first command mid-interaction.
		await waitForRootRedirect(page);

		await openPalette(page);
		const input = page.locator('#command-palette-input');
		await expect(input).toBeFocused();
		await expect(input).toHaveAttribute('aria-activedescendant', 'palette-option-other-compose');
		const options = page.getByRole('listbox', { name: 'Výsledky příkazů' }).getByRole('option');
		const secondOptionId = await options.nth(1).getAttribute('id');
		expect(secondOptionId).toBeTruthy();

		await input.focus();
		await page.keyboard.press('ArrowDown');
		await expect(input).toHaveAttribute('aria-activedescendant', secondOptionId!);
		const activeOptionId = await input.getAttribute('aria-activedescendant');
		expect(activeOptionId).toBeTruthy();
		await expect(page.locator(`#${activeOptionId}`)).toHaveAttribute('role', 'option');
	});

	test('aktivní položku předává bez duplicitního live oznámení', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const input = page.locator('#command-palette-input');

		await expect(input).toHaveAttribute('aria-activedescendant', 'palette-option-other-compose');
		await expect(page.locator('#palette-active-command')).toHaveCount(0);

		await expect(input).toBeFocused();
		await input.press('ArrowDown');
		await expect(input).toBeFocused();
		await expect(page.locator('#palette-active-command')).toHaveCount(0);
	});

	test('seznam Výsledky příkazů má přístupnou listbox strukturu', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const input = page.locator('#command-palette-input');
		const results = page.getByRole('listbox', { name: 'Výsledky příkazů' });
		const visibleOptions = results.getByRole('option');

		await expect(results).toBeVisible();
		await expect(input).toHaveAttribute('aria-controls', 'palette-listbox');
		await expect(input).toHaveAttribute('aria-expanded', 'true');

		const visibleOptionCount = await visibleOptions.count();
		expect(visibleOptionCount).toBeGreaterThan(1);

		const activeOptionId = await input.getAttribute('aria-activedescendant');
		expect(activeOptionId).toBeTruthy();
		await expect(page.locator(`#${activeOptionId}`)).toHaveAttribute('role', 'option');
		await expect(visibleOptions.first()).toHaveAttribute('aria-posinset', '1');
		await expect(visibleOptions.first()).toHaveAttribute('aria-setsize', /\d+/);
		const setSize = Number(await visibleOptions.first().getAttribute('aria-setsize'));
		expect(setSize).toBeGreaterThanOrEqual(visibleOptionCount);

		await input.fill('nenajitelnypovel');
		await expect(results.getByText('Nebyl nalezen žádný odpovídající příkaz.')).toBeVisible();
		await expect(results.getByRole('option')).toHaveCount(0);
		await expect(input).not.toHaveAttribute('aria-activedescendant', /.+/);
	});

	test('prochází příkazy šipkami bez přesunu fokusu z vyhledávacího pole', async ({ page }) => {
		await page.goto('/');
		await waitForShell(page);

		await openPalette(page);
		const input = page.locator('#command-palette-input');

		await expect(input).toBeFocused();
		await expect(input).toHaveAttribute('aria-activedescendant', 'palette-option-other-compose');

		await input.press('ArrowDown');
		await expect(input).toBeFocused();
		let activeOptionId = await input.getAttribute('aria-activedescendant');
		expect(activeOptionId).toBeTruthy();
		await expect(page.locator(`#${activeOptionId}`)).toHaveAttribute('role', 'option');

		await input.press('ArrowUp');
		await expect(input).toBeFocused();
		activeOptionId = await input.getAttribute('aria-activedescendant');
		expect(activeOptionId).toBeTruthy();
		await expect(page.locator(`#${activeOptionId}`)).toHaveAttribute('role', 'option');
	});

	test('na detailu nabídne kontextový Reply command a otevře compose s prefillem', async ({
		page
	}) => {
		await page.goto(
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		await waitForShell(page);
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();

		await openPalette(page);
		await page.locator('#command-palette-input').fill('odpovědět');
		await page.keyboard.press('Enter');

		await page.waitForURL(new RegExp(`/compose\\?reply=${fixture.stableId}`));
		await expect(page.getByText(fixture.replyPrefill.to)).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue(fixture.replyPrefill.subject);
	});

	test('na detailu nabídne command pro přesun zprávy do složky', async ({ page }) => {
		await page.goto(
			`/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);
		await waitForShell(page);
		await expect(page.getByRole('heading', { name: 'Testovací zpráva 3' })).toBeVisible();

		await openPalette(page);
		const input = page.locator('#command-palette-input');
		await input.fill('archiv');
		await expect(page.getByRole('option', { name: /Přesunout do složky Archiv/ })).toBeVisible();
		await input.press('Enter');

		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toHaveCount(0);

		await page.getByRole('link', { name: 'Archiv' }).click();
		await page.waitForURL(`**/mail/${fixture.accountId}/ARCHIVE`);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toBeVisible();
	});
});
