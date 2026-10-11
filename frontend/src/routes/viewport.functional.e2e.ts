import { expect, test, type Page } from '@playwright/test';
import { openApp, setPrefs, waitForFocus } from './e2e-helpers';

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
		await expect(page.getByRole('button', { name: /^Podokno pošty/ })).toHaveCount(0);

		await page.keyboard.press('Control+y');
		await waitForFocus(pane(page).getByRole('link', { name: /^Doručené/ }));
	});
});
