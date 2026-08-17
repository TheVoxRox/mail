import { expect, test, type Locator } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * What a screen reader does to the grid instead of pressing Enter. In browse
 * mode it keeps the unmodified keys for its own navigation and never delivers
 * the keydown; activating the element under its cursor reaches the page as a
 * click, and depending on the reader that click may carry no click count at
 * all. Playwright's keyboard and mouse cannot produce that event, so the
 * app-side half of the SR path is only testable this way.
 */
const activateLikeScreenReader = (cell: Locator) => cell.evaluate((el: HTMLElement) => el.click());

/*
 * Off-mode list keyboard model (SR audit findings 1+2): with the reading pane
 * off there is no pane that could follow the selection — a row change on
 * Arrow/Page keys must only move the roving focus, and the message opens on
 * Enter/Space.
 *
 * The mouse is a separate model, the web-mail one (Gmail, Outlook Web): a click
 * anywhere on the row opens the message, and the checkbox is the only thing
 * that selects. #201 briefly made a single click select instead, mirroring
 * Outlook desktop, and that swallowed the click a screen reader sends in place
 * of the Enter it never delivers — Enter looked dead in both readers. Delete
 * must hand focus to a neighbouring row instead of dropping it on <body>.
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

	test('jednoklik na předmět otevře zprávu', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.click();

		await page.waitForURL('**/mail/1/INBOX/msg-01');
	});

	test('jednoklik mimo předmět otevře zprávu taky', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// The whole row opens, not only the link — the row click handler is the
		// path a screen reader's activation takes when it does not target the link.
		await page.locator('[role="row"][data-stable-id="msg-01"] [data-col="4"]').click();

		await page.waitForURL('**/mail/1/INBOX/msg-01');
	});

	test('zaškrtávátko vybere řádek a zprávu neotevře', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// Selection is the checkbox alone; it must keep its click to itself.
		await page.locator('[role="row"][data-stable-id="msg-01"] input[type="checkbox"]').check();

		// Proving a non-event needs a settle: an open takes well under this budget
		// (measured ~280 ms), so after it the folder URL must still hold.
		await page.waitForTimeout(700);
		await expect(page).toHaveURL(/\/mail\/1\/INBOX$/);
		await expect(page.getByRole('button', { name: 'Smazat vybrané' })).toBeVisible();
	});

	test('aktivace odečítačem otevře zprávu jako Enter', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const firstSubject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(firstSubject).toBeVisible();
		await firstSubject.focus();

		// The subject is a real link, so this is the reader's own activation path;
		// under the old select-on-click model it did nothing visible at all.
		await activateLikeScreenReader(firstSubject);

		await page.waitForURL('**/mail/1/INBOX/msg-01');
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

	test('smazání poslední zprávy přesune fokus na hlášku prázdné složky', async ({ page }) => {
		// The grid goes away with the row, so there is no cell left to receive
		// focus — without a target it falls to <body> and the deletion is silent.
		await page.goto('/mail/1/SENT');
		await waitForShell(page);

		// SENT seeds two messages; empty it row by row — the interesting step is
		// the LAST deletion, after which no cell is left to receive focus.
		const rows = page.locator('[role="row"][data-stable-id]');
		await expect(rows).toHaveCount(2);
		await rows.first().locator('[data-col="2"]').focus();
		await page.keyboard.press('Delete');
		await expect(rows).toHaveCount(1);

		// No manual focus() here: the first deletion must have restored focus onto
		// the surviving row's subject cell by itself. Re-focusing would hand the
		// second Delete a keyboard target the app never had to provide, and the
		// restore this suite exists to guard would go untested on this path.
		await expect(rows.first().locator('[data-col="2"]')).toBeFocused();
		await page.keyboard.press('Delete');

		const empty = page.getByRole('status').filter({ hasText: 'Žádné zprávy' });
		await expect(empty).toBeVisible();
		await expect(empty).toBeFocused();
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
		await expect(page.locator('#live-region')).toContainText('Strana 1 z 1, 2 zprávy');
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

	test('jednoklik ve split režimu otevře zprávu a pustí kurzor do těla', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const subject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(subject).toBeVisible();

		// A click is a deliberate open in either pane mode: the message shows in the
		// pane and the reading cursor follows it into the body. Only the Arrow keys
		// keep the cursor in the list — that is the split-mode test above.
		await subject.click();
		await page.waitForURL('**/mail/1/INBOX/msg-01');
		const frame = page.getByTitle('Obsah zprávy');
		await expect(frame).toBeVisible();
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);
	});

	test('aktivace odečítačem ve split režimu pustí kurzor do těla zprávy', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const subject = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
		await expect(subject).toBeVisible();
		await subject.focus();

		// The deliberate-open half matters here too: treated as a single click the
		// message would show in the pane but the reading cursor would stay on the
		// row, so the SR user hears nothing of the message they just opened.
		await activateLikeScreenReader(subject);

		await page.waitForURL('**/mail/1/INBOX/msg-01');
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

		/*
		 * Wait for the app to park focus in the content frame before taking it
		 * away. Opening a message moves focus there on its own, a step behind the
		 * navigation, and a rendered heading does not mean that step has run —
		 * so a row focused too early gets it stolen back. Delete then reaches the
		 * detail rather than the grid and removes the OPEN message instead of the
		 * focused row, which is why this failed as "msg-02 still present" rather
		 * than as a lost keystroke. Measured: with focus left in the frame, Delete
		 * takes msg-01 and leaves msg-02 standing, three runs of three.
		 */
		const frame = page.getByTitle('Obsah zprávy');
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);

		const secondSubject = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
		await secondSubject.focus();
		// Confirm the row really holds focus before the key goes out, so a future
		// theft fails here instead of three lines down as a puzzling count.
		await expect(secondSubject).toBeFocused();
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
