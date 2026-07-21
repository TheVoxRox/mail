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

	test('příchod výsledků hledání ohlásí počet do live regionu', async ({ page }) => {
		await page.goto('/search/1?q=projekt');
		await waitForShell(page);

		// Focus stays in the search box while the results render below it,
		// so the arrival of results must be announced explicitly.
		await expect(page.getByRole('grid', { name: 'Výsledky' })).toBeVisible();
		await expect(page.locator('#live-region')).toContainText('Nalezeno 1 zpráva.');
	});

	test('stránkování výsledků ohlásí novou stranu do live regionu', async ({ page }) => {
		await page.addInitScript(() => {
			// 25 fixture matches for "zpráva" paginate by 10 → 3 pages; the
			// default page size (25) would leave a single page and no pager.
			window.localStorage.setItem('mail.e2e.mailPageSize', '10');
		});
		await page.goto('/search/1?q=zpráva');
		await waitForShell(page);

		await expect(page.getByRole('grid', { name: 'Výsledky' })).toBeVisible();
		await expect(page.locator('#live-region')).toContainText('Nalezeno 25 zpráv.');

		const pagination = page.getByRole('navigation', { name: 'Stránkování výsledků' });
		await pagination.getByRole('button', { name: 'Další →' }).click();
		await page.waitForURL(/page=1/);
		await expect(page.locator('#live-region')).toContainText('Strana 2 z 3, 25 zpráv');
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

	test('Esc na detailu výsledku vrátí zpět do výsledků a na jeho řádek', async ({ page }) => {
		// The detail renders in place of the results here, so the mail route's
		// closing path (back to the last browsed folder) would throw the user
		// into the inbox and lose the results entirely.
		await page.goto('/search/1?q=projekt');
		await waitForShell(page);

		const subject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="1"]');
		await expect(subject).toBeVisible();
		await subject.focus();
		await page.keyboard.press('Enter');

		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		const frame = page.getByTitle('Obsah zprávy');
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);

		await page.locator('main').focus();
		await page.keyboard.press('Escape');

		await expect(page.getByRole('grid', { name: 'Výsledky' })).toBeVisible();
		await expect(page).toHaveURL(/\/search\/1\?q=projekt$/);
		await expect(subject).toBeFocused();
	});

	test('smazání výsledku z řádkového menu vrátí fokus na sousední řádek', async ({ page }) => {
		await page.goto('/search/1?q=zpráva');
		await waitForShell(page);

		const grid = page.getByRole('grid', { name: 'Výsledky' });
		await expect(grid).toBeVisible();
		const rows = grid.locator('[role="row"][data-stable-id]');
		const firstId = await rows.nth(0).getAttribute('data-stable-id');
		const secondId = await rows.nth(1).getAttribute('data-stable-id');
		if (!firstId || !secondId) throw new Error('Výsledky hledání neobsahují data-stable-id.');

		// The row menu trigger unmounts with its row — without a restore of its
		// own (messagesState knows nothing about search results) focus would
		// drop to <body>.
		await page.locator(`[role="row"][data-stable-id="${firstId}"] [data-col="5"]`).click();
		await page.getByRole('menuitem', { name: 'Smazat' }).click();

		await expect(page.locator(`[role="row"][data-stable-id="${firstId}"]`)).toHaveCount(0);
		await expect(
			page.locator(`[role="row"][data-stable-id="${secondId}"] [data-col="1"]`)
		).toBeFocused();
	});

	test('smazání posledního výsledku přesune fokus na hlášku bez výsledků', async ({ page }) => {
		// A single match, so the grid disappears with it — focus has to land on
		// the message that replaces it instead of falling to <body>.
		await page.goto('/search/1?q=projekt');
		await waitForShell(page);

		const rows = page
			.getByRole('grid', { name: 'Výsledky' })
			.locator('[role="row"][data-stable-id]');
		await expect(rows).toHaveCount(1);
		await rows.locator('[data-col="5"]').click();
		await page.getByRole('menuitem', { name: 'Smazat' }).click();

		const empty = page.getByRole('status').filter({ hasText: 'Žádné zprávy neodpovídají dotazu' });
		await expect(empty).toBeVisible();
		await expect(empty).toBeFocused();
	});
});
