import { expect, test, type Page } from '@playwright/test';
import { openApp, reflowLosses, setPrefs, waitForFocus } from './e2e-helpers';

const mailDetailPath = '/mail/1/INBOX/msg-01';

async function openMailDetailAt(page: Page, viewport: { width: number; height: number }) {
	await page.setViewportSize(viewport);
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });

	await openApp(page, mailDetailPath);
}

async function expectShellFitsViewport(page: Page) {
	const overflow = await page.evaluate(() => ({
		documentWidth: document.documentElement.scrollWidth,
		bodyWidth: document.body.scrollWidth,
		viewportWidth: window.innerWidth
	}));

	expect(overflow.documentWidth).toBeLessThanOrEqual(overflow.viewportWidth);
	expect(overflow.bodyWidth).toBeLessThanOrEqual(overflow.viewportWidth);
	await expect(page.getByRole('navigation', { name: 'Přepínač prostředí' })).toBeVisible();
	await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();
	await expect(page.getByRole('toolbar', { name: 'Akce se zprávami' })).toBeVisible();
	await expect(
		page.locator('[role="separator"][aria-orientation="vertical"][tabindex="0"]')
	).toBeVisible();
}

test.describe('Viewport baseline', () => {
	test('doporučené desktop okno 1280x800 zobrazí mail shell bez horizontálního přetečení', async ({
		page
	}) => {
		await openMailDetailAt(page, { width: 1280, height: 800 });

		await expectShellFitsViewport(page);
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
	});

	test('minimální desktop okno 1024x768 udrží rail, sidebar, toolbar i reading pane', async ({
		page
	}) => {
		await openMailDetailAt(page, { width: 1024, height: 768 });

		await expectShellFitsViewport(page);
		await expect(page.getByRole('button', { name: /Více/ })).toHaveCount(0);
	});
});

/*
 * Below 768 CSS px the workspace pane folds away and slides in over the
 * content on demand (stores/workspacePane.ts, WCAG 1.4.10). 640px is a 1280px
 * window at 200% zoom.
 */
test.describe('Podokno v úzkém okně', () => {
	const pane = (page: Page, name = 'Podokno pošty') => page.getByRole('region', { name });
	const toggle = (page: Page, name = 'Podokno pošty') =>
		page.getByRole('button', { name: `${name} (Ctrl+Y)` });

	async function openAt(page: Page, path: string, width: number, height: number) {
		await page.setViewportSize({ width, height });
		await setPrefs(page, { locale: 'cs', readingPane: 'right' });
		await openApp(page, path);
		// The pane opens on the item for the page in view, which exists only once
		// the pane's data has loaded; hidden or not, it is in the document then.
		await expect(page.locator('#workspace-pane [aria-current="page"]')).toBeAttached();
	}
	const openNarrow = (page: Page, path: string) => openAt(page, path, 640, 400);

	test('tlačítko podokno vysune a Escape ho zavře s fokusem zpět na tlačítku', async ({ page }) => {
		await openNarrow(page, '/mail/1/INBOX');
		await expect(page.getByRole('heading', { level: 1, name: /^Doručené/ })).toBeFocused();
		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
		// The shortcut is in the name; aria-keyshortcuts as well made a reader
		// that speaks both say it twice.
		await expect(toggle(page)).not.toHaveAttribute('aria-keyshortcuts');

		await toggle(page).click();
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
		// It lands on the folder in view, as Outlook's folder pane does.
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));

		await page.keyboard.press('Escape');
		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toBeFocused();
	});

	test('Ctrl+Y podokno vysune a výběr složky ho zavře, fokus dopadne na nadpis', async ({
		page
	}) => {
		await openNarrow(page, '/mail/1/INBOX');
		await expect(page.getByRole('heading', { level: 1, name: /^Doručené/ })).toBeFocused();

		await page.keyboard.press('Control+y');
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));
		await pane(page)
			.getByRole('link', { name: /^Odeslané/ })
			.click();

		await expect(pane(page)).toHaveCount(0);
		await expect(page.getByRole('heading', { level: 1, name: /^Odeslané/ })).toBeFocused();
	});

	test('odchod Tabem z podokna ho zavře, aby fokus nezůstal pod ním', async ({ page }) => {
		await openNarrow(page, '/mail/1/INBOX');
		// The list's first control is where Tab goes next; before the list has
		// loaded there is nothing in the content to go to.
		const selectAll = page.getByRole('checkbox', { name: 'Vybrat vše' });
		await expect(selectAll).toBeVisible();
		await toggle(page).click();
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));

		// The pane's last control; Tab goes on to the content under the pane.
		await pane(page).getByRole('button', { name: 'Synchronizovat' }).press('Tab');

		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
		await expect(selectAll).toBeFocused();
	});

	test('dialog otevřený z podokna ho nechá vysunuté a fokus se do něj vrátí', async ({ page }) => {
		await openNarrow(page, '/contacts');
		await toggle(page, 'Podokno kontaktů').click();
		const manage = pane(page, 'Podokno kontaktů').getByRole('button', {
			name: 'Spravovat štítky'
		});
		await manage.click();
		const dialog = page.getByRole('dialog', { name: 'Štítky kontaktů' });
		await expect(dialog).toBeVisible();
		await expect(pane(page, 'Podokno kontaktů')).toBeVisible();

		await page.keyboard.press('Escape');
		await expect(dialog).toBeHidden();
		await expect(manage).toBeFocused();
		await expect(pane(page, 'Podokno kontaktů')).toBeVisible();
	});

	test('zúžení okna s fokusem v podokně ho nechá vysunuté', async ({ page }) => {
		// Zooming in from the folder list folded the pane under the focus.
		await openAt(page, '/mail/1/INBOX', 1280, 720);
		await expect(page.getByRole('heading', { level: 1, name: /^Doručené/ })).toBeFocused();
		const inbox = pane(page).getByRole('link', { name: /^Doručené/ });
		await page.keyboard.press('Control+y');
		await waitForFocus(inbox);

		await page.setViewportSize({ width: 640, height: 400 });
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
		await expect(inbox).toBeFocused();
	});

	test('v širokém okně podokno zůstává a Ctrl+Y do něj přesune fokus', async ({ page }) => {
		await openAt(page, '/mail/1/INBOX', 1280, 720);
		await expect(page.getByRole('heading', { level: 1, name: /^Doručené/ })).toBeFocused();
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');

		await page.keyboard.press('Control+y');
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));
	});

	test('v širokém okně tlačítko podokno skryje, zapamatuje si to a zase ho ukotví', async ({
		page
	}) => {
		await openAt(page, '/mail/1/INBOX', 1280, 720);
		const main = page.locator('main#main-content');
		const docked = await main.evaluate((el) => el.getBoundingClientRect().left);

		await toggle(page).click();
		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
		await expect(toggle(page)).toBeFocused();
		// The content takes the pane's width, not just its pixels.
		expect(await main.evaluate((el) => el.getBoundingClientRect().left)).toBeLessThan(docked);

		await page.reload();
		await expect(page.locator('#workspace-pane [aria-current="page"]')).toBeAttached();
		await expect(pane(page)).toHaveCount(0);

		await toggle(page).click();
		await expect(pane(page)).toBeVisible();
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
		expect(await main.evaluate((el) => el.getBoundingClientRect().left)).toBe(docked);
	});

	test('podokno skryté v širokém okně vysune Ctrl+Y jen nakrátko', async ({ page }) => {
		await openAt(page, '/mail/1/INBOX', 1280, 720);
		await toggle(page).click();
		await expect(pane(page)).toHaveCount(0);

		await page.keyboard.press('Control+y');
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');

		await page.keyboard.press('Escape');
		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toBeFocused();
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
	});

	test('tlačítko podokno vysunuté přes Ctrl+Y zasune a až další stisk ho ukotví', async ({
		page
	}) => {
		// The button said "rozbaleno" over a pane slid out by Ctrl+Y, and a press
		// docked the pane instead of putting it away: the state it reported did
		// not change, and the overlay a mouse user meant to close stayed.
		await openAt(page, '/mail/1/INBOX', 1280, 720);
		const main = page.locator('main#main-content');
		const docked = await main.evaluate((el) => el.getBoundingClientRect().left);
		await toggle(page).click();
		await expect(pane(page)).toHaveCount(0);
		await page.keyboard.press('Control+y');
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');

		await toggle(page).click();
		await expect(pane(page)).toHaveCount(0);
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'false');
		await expect(toggle(page)).toBeFocused();

		await toggle(page).click();
		await expect(pane(page)).toBeVisible();
		await expect(toggle(page)).toHaveAttribute('aria-expanded', 'true');
		expect(await main.evaluate((el) => el.getBoundingClientRect().left)).toBe(docked);
	});

	/*
	 * 320 CSS px is the width the fold is for (a 1280px window at 400%), and
	 * the rail with a pane at its docked width is wider than that: 64 + 272px,
	 * 72 + 306px at the large text size. A fixed box cannot be scrolled to, so
	 * the pane's right edge, where the unread counts sit, was simply cut off.
	 */
	for (const [path, name] of [
		['/mail/1/INBOX', 'Podokno pošty'],
		['/contacts', 'Podokno kontaktů'],
		['/settings/appearance', 'Podokno nastavení']
	] as const) {
		for (const [textSize, sizeName] of [
			['medium', 'běžné'],
			['large', 'velké']
		] as const) {
			test(`${name} se na šířce 320 px vejde do okna, ${sizeName} písmo (1.4.10)`, async ({
				page
			}) => {
				await setPrefs(page, { textSize });
				await openAt(page, path, 320, 256);
				await toggle(page, name).click();
				await expect(pane(page, name)).toBeVisible();

				expect(
					await pane(page, name).evaluate((el) => el.getBoundingClientRect().right)
				).toBeLessThanOrEqual(320);
				expect(await reflowLosses(page)).toEqual([]);
			});
		}
	}
});
