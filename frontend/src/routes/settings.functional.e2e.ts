import { expect, test, type Locator } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

async function expectArrowDownOpensSelect(locator: Locator): Promise<void> {
	await locator.evaluate((element) => {
		const select = element as HTMLSelectElement & { __showPickerCalls?: number };
		select.__showPickerCalls = 0;
		select.showPicker = () => {
			select.__showPickerCalls = (select.__showPickerCalls ?? 0) + 1;
		};
	});
	await locator.focus();
	await locator.press('ArrowDown');
	await expect(locator).toBeFocused();
	await expect
		.poll(() =>
			locator.evaluate(
				(element) =>
					(element as HTMLSelectElement & { __showPickerCalls?: number }).__showPickerCalls ?? 0
			)
		)
		.toBe(1);
}

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.removeItem('mail.readingPane');
	});
});

test.describe('Settings – language', () => {
	test('přepnutí jazyka okamžitě změní UI a uloží volbu do localStorage', async ({ page }) => {
		await page.goto('/settings/language');
		await waitForShell(page);

		await expect(page.getByRole('heading', { level: 1, name: 'Jazyk' })).toBeVisible();
		await expect(page.getByRole('heading', { level: 2, name: 'Jazyk aplikace' })).toBeVisible();

		const select = page.locator('#locale-select');
		await expect(select).toHaveValue('cs');
		await select.selectOption('en');

		await expect(page.getByRole('heading', { level: 1, name: 'Language' })).toBeVisible();
		await expect(
			page.getByRole('heading', { level: 2, name: 'Application language' })
		).toBeVisible();

		const stored = await page.evaluate(() => window.localStorage.getItem('mail.locale'));
		expect(stored).toBe('en');

		const htmlLang = await page.evaluate(() => document.documentElement.lang);
		expect(htmlLang).toBe('en');
	});

	test('rozbalovací seznam jazyka se otevře šipkou dolů', async ({ page }) => {
		await page.goto('/settings/language');
		await waitForShell(page);

		await expectArrowDownOpensSelect(page.locator('#locale-select'));
	});
});

test.describe('Settings – appearance', () => {
	test('výchozí podokno čtení je skryté', async ({ page }) => {
		await page.goto('/settings/appearance');
		await waitForShell(page);

		const select = page.locator('#reading-pane-select');
		await expect(select).toHaveValue('off');
		await expect
			.poll(() => page.evaluate(() => window.localStorage.getItem('mail.readingPane')))
			.toBe('off');
	});

	test('rozbalovací seznam motivu se otevře šipkou dolů', async ({ page }) => {
		await page.goto('/settings/appearance');
		await waitForShell(page);

		await expectArrowDownOpensSelect(page.locator('#theme-select'));
	});

	test('rozbalovací seznam podokna čtení se otevře šipkou dolů', async ({ page }) => {
		await page.goto('/settings/appearance');
		await waitForShell(page);

		await expectArrowDownOpensSelect(page.locator('#reading-pane-select'));
	});
});
