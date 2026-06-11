import { expect, test } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Search', () => {
	test('synchronizuje SearchBar s parametrem q v URL', async ({ page }) => {
		await page.goto('/search/1?q=foo');
		await waitForShell(page);

		const searchInput = page.locator('#search-input');
		await expect(searchInput).toHaveValue('foo');

		await searchInput.fill('projekt');
		await searchInput.press('Enter');
		await page.waitForURL('**/search/1?q=projekt');
		await expect(searchInput).toHaveValue('projekt');

		await page.goBack();
		await page.waitForURL('**/search/1?q=foo');
		await expect(searchInput).toHaveValue('foo');
	});

	test('najde zprávu v mockovaných datech a otevře detail z výsledků', async ({ page }) => {
		await page.goto('/search/1?q=projekt');
		await waitForShell(page);

		await expect(
			page.getByRole('heading', { name: 'Výsledky vyhledávání „projekt"' })
		).toBeVisible();

		const results = page.getByRole('grid', { name: 'Výsledky' });
		await expect(results).toBeVisible();

		const result = results.getByRole('row').filter({ hasText: 'Projektové podklady' });
		await expect(result).toBeVisible();
		await result.click();

		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		await expect(page.getByText('Jana Novak <jana@example.com>')).toBeVisible();
	});

	test('detail z výsledků hledání nabízí akce v inline toolbaru', async ({ page }) => {
		await page.goto('/search/1?q=projekt');
		await waitForShell(page);

		const results = page.getByRole('grid', { name: 'Výsledky' });
		const result = results.getByRole('row').filter({ hasText: 'Projektové podklady' });
		await expect(result).toBeVisible();
		await result.click();

		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();

		// The search route has no top-bar toolbar, so the inline one is the only
		// way to act on the open message.
		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar).toBeVisible();

		await toolbar.getByRole('button', { name: 'Odpovědět', exact: true }).click();
		await page.waitForURL(/\/compose\?reply=msg-01/);
	});
});
