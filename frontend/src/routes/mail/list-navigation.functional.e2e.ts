import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Off-mode list keyboard model (SR audit findings 1+2): with the reading pane
 * off there is no pane that could follow the selection — a row change on
 * Arrow/Page keys must only move the roving focus, and only Enter/Space (or a
 * click) opens the message. Without this, arrowing through the list tears a
 * screen-reader user out of the list into the detail route. Delete must hand
 * focus to a neighbouring row instead of dropping it on <body>.
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'off');
	});
});

test.describe('Seznam zpráv v režimu bez podokna čtení', () => {
	test('šipka dolů jen přesune fokus, zprávu otevře až Enter', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		await page.keyboard.press('ArrowDown');

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(secondSubject).toBeFocused();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);
		await expect(page.getByRole('grid', { name: 'Seznam zpráv' })).toBeVisible();

		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-02');
	});

	test('PageDown a Home v seznamu neotevírají zprávy', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		await page.keyboard.press('PageDown');
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);

		await page.keyboard.press('Control+Home');
		await expect(firstSubject).toBeFocused();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);
	});

	test('Delete vrátí fokus na sousední řádek', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(secondSubject).toBeVisible();
		await secondSubject.focus();

		await page.keyboard.press('Delete');

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		const thirdSubject = page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]');
		await expect(thirdSubject).toBeFocused();
	});

	test('hromadné smazání vrátí fokus na předmět sousedního řádku', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// Ticking the box parks the roving cell on the select column, and that
		// row then disappears — the restore must land on a content cell. A
		// checkbox would announce "Select message X" and say nothing about where
		// focus actually went.
		await page.locator('[role="row"][data-stable-id="msg-02"] input[type="checkbox"]').check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		await expect(
			page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]')
		).toBeFocused();
	});
});

test.describe('Přepnutí složky', () => {
	test('přepnutí složky ohlásí načtený seznam do live regionu', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);
		await expect(page.locator('[role="row"][data-stable-id="msg-01"]')).toBeVisible();

		// Focus stays on the sidebar folder link while the list swaps —
		// announce the loaded page (the initial folder load stays quiet).
		await page.getByRole('link', { name: 'Odeslané' }).click();
		await page.waitForURL('**/mail/1/SENT');
		await expect(page.locator('#live-region')).toContainText('Strana 1 z 1, 1 zpráva');
	});
});

test.describe('Koncepty ve split režimu', () => {
	test('šipka v Konceptech jen přesune fokus, composer otevře až Enter', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/DRAFTS');
		await waitForShell(page);

		// Drafts open the composer, not the reading pane — a row change must
		// not navigate even in split mode (same guard as effective off mode).
		const firstSubject = page.locator('[role="row"][data-stable-id="draft-42"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		await page.keyboard.press('ArrowDown');

		const secondSubject = page.locator('[role="row"][data-stable-id="draft-43"] [data-col="2"]');
		await expect(secondSubject).toBeFocused();
		await expect(page).toHaveURL(/\/mail\/1\/DRAFTS$/);

		await page.keyboard.press('Enter');
		await page.waitForURL('**/compose?draft=draft-43');
		await expect(page.locator('#compose-subject')).toHaveValue('Druhý rozepsaný koncept');
	});
});

test.describe('Seznam zpráv ve split režimu', () => {
	test('šipky drží fokus v seznamu, do těla zprávy pustí až Enter', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const activeCell = () =>
			page.evaluate(() => ({
				stableId:
					document.activeElement?.closest('[data-stable-id]')?.getAttribute('data-stable-id') ??
					null,
				col: document.activeElement?.getAttribute('data-col') ?? null
			}));

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		// A row change follows focus with the selection, so the message opens in
		// the pane — but focus must stay on the roving grid cell. The body loads
		// asynchronously, so re-check after it rendered: it must not pull focus
		// out of the list once it arrives.
		await page.keyboard.press('ArrowDown');
		await page.waitForURL('**/mail/1/INBOX/msg-02');
		await expect(page.getByTitle('Obsah zprávy')).toBeVisible();
		await expect.poll(activeCell).toEqual({ stableId: 'msg-02', col: '2' });

		// Focus still in the grid means the next Arrow key keeps navigating.
		await page.keyboard.press('ArrowDown');
		await page.waitForURL('**/mail/1/INBOX/msg-03');
		await expect.poll(activeCell).toEqual({ stableId: 'msg-03', col: '2' });

		// Enter is the deliberate open — that one does move the reading cursor
		// into the body of the message already showing in the pane.
		await page.keyboard.press('Enter');
		const frame = page.getByTitle('Obsah zprávy');
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);
	});

	test('Delete na neotevřeném řádku neztratí fokus a nechá detail otevřený', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// Open msg-01 in the reading pane, then move focus back to another row.
		await page.locator('[role="row"][data-stable-id="msg-01"]').click();
		await page.waitForURL('**/mail/1/INBOX/msg-01');
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await secondSubject.focus();
		await page.keyboard.press('Delete');

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		const thirdSubject = page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]');
		await expect(thirdSubject).toBeFocused();
		// The open message was not the deleted one — the detail must stay.
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
		await expect(page).toHaveURL(/\/mail\/1\/INBOX\/msg-01$/);
	});

	test('Esc na otevřené zprávě vrátí fokus na její řádek', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const first = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(first).toBeVisible();
		await first.focus();

		// Reached by the roving selection, which is the case that breaks: the
		// list is already mounted, so the restore fires before the navigation
		// settles and only survives if the close keeps focus (without it this
		// ends on <main>; an Enter-opened message happens to survive either way).
		await page.keyboard.press('ArrowDown');
		await page.waitForURL('**/mail/1/INBOX/msg-02');
		const second = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect.poll(() => second.evaluate((el) => el === document.activeElement)).toBe(true);

		await page.keyboard.press('Escape');
		await page.waitForURL((url) => url.pathname === '/mail/1/INBOX');
		await expect(second).toBeFocused();
	});

	test('smazání otevřené zprávy vrátí fokus na sousední řádek', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const subject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(subject).toBeVisible();
		await subject.focus();
		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-02');

		// Deleting from the detail toolbar closes the detail and navigates back
		// to the folder; the message that was open is gone, so focus belongs on
		// the row that took its place.
		await page
			.getByRole('toolbar', { name: 'Akce se zprávami' })
			.getByRole('button', { name: 'Smazat' })
			.click();

		await expect(page.locator('[role="row"][data-stable-id="msg-02"]')).toHaveCount(0);
		await expect(
			page.locator('[role="row"][data-stable-id="msg-03"] [data-col="2"]')
		).toBeFocused();
	});

	test('odkaz zpět do složky vrátí fokus na řádek zprávy', async ({ page }) => {
		// Off mode replaces the detail's Back button with the breadcrumb link in
		// the top bar — the visible way back must restore focus like Esc does.
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const subject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await expect(subject).toBeVisible();
		await subject.focus();
		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-02');

		await page.getByRole('link', { name: 'Zpět do složky Doručené' }).click();
		await page.waitForURL((url) => url.pathname === '/mail/1/INBOX');
		await expect(subject).toBeFocused();
	});
});
