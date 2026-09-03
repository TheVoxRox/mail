import { expect, test } from '@playwright/test';
import { openApp, setPrefs } from '../e2e-helpers';

/**
 * Row actions menu ("Akce pro zprávu …") in the message list. Delete removes
 * the row that owns the menu, which must not leave the menu itself behind.
 */
const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-04',
	subject: 'Testovací zpráva 4'
};

/*
 * Pace for keyboard walks through the menu, and the reason every such walk
 * asserts where focus landed before sending its next key.
 *
 * bits-ui places roving focus an `afterTick` after the key that asked for it.
 * Keys fired back-to-back at Playwright speed can arrive inside that window,
 * and the menu does not merely lag — it wedges: the focus the next step waits
 * for never arrives at all, so the assertion burns its full timeout rather
 * than settling late. That is why polling assertions alone do not make these
 * tests deterministic.
 *
 * The pace is measured, not guessed. A walk driven with a pause between
 * keystrokes lands every step correctly at 400 ms, 150 ms and 60 ms, nine runs
 * for nine — and 60 ms is quicker than anyone deliberately working a menu, so
 * the wedge is out of human reach and this stays a harness artifact rather
 * than a defect to chase. The assertions are what keep the tests honest: if
 * the affordance itself ever breaks, focus stops landing and they still fail.
 */
const KEY_PACE_MS = 60;

/** First item of the row actions menu — where bits-ui puts focus on open. */
const FIRST_ITEM = 'Odpovědět';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('Řádkové menu Akce', () => {
	test('Smazat z řádkového menu smaže zprávu a menu se zavře', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();

		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		// The message is gone from the list…
		await expect(row).toHaveCount(0);
		// …and the menu closed with it instead of lingering as an orphan.
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu klávesnicí smaže zprávu a menu se zavře', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		// The keyboard path: open the menu with Enter on the trigger and walk to
		// Delete with the menu's own arrow-key navigation (bits-ui highlight),
		// exactly like a keyboard user — no programmatic focus of the item.
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();
		await page.keyboard.press('Enter');
		const menu = page.getByRole('menu');
		// Visible is not ready: the menu paints before bits-ui has placed roving
		// focus, and ArrowUp sent into that gap wedges it. Wait for the focus.
		await expect(menu.getByRole('menuitem', { name: FIRST_ITEM, exact: true })).toBeFocused();

		// `loop` wraps ArrowUp from the top straight to the last item = Smazat.
		await page.waitForTimeout(KEY_PACE_MS);
		await page.keyboard.press('ArrowUp');
		await expect(menu.getByRole('menuitem', { name: 'Smazat' })).toBeFocused();
		await page.keyboard.press('Enter');

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu v koši: potvrzovací dialog, smazání a menu zavřené', async ({
		page
	}) => {
		await openApp(page, `/mail/${fixture.accountId}/TRASH`);

		const row = page.locator('[role="row"][data-stable-id="trash-01"]');
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: 'Akce pro zprávu Smazaný e-mail 1' }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Trvalé smazání' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Smazat trvale' }).click();

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Smazat z řádkového menu s pomalým backendem: menu se zavře hned, fokus skončí na sousedním řádku', async ({
		page
	}) => {
		// The real IMAP delete takes hundreds of ms; MSW answers instantly. Delay
		// the DELETE so the row outlives the menu-close sequence like in the app.
		// Patched via window.fetch: Playwright routing does not see requests the
		// MSW service worker answers, but the page's own fetch runs before the SW.
		await page.addInitScript(() => {
			const origFetch = window.fetch;
			window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
				const method = init?.method ?? (input instanceof Request ? input.method : 'GET');
				const url =
					typeof input === 'string' ? input : input instanceof Request ? input.url : String(input);
				if (method === 'DELETE' && /\/api\/v1\/messages\/[^/]+$/.test(url)) {
					await new Promise((resolve) => setTimeout(resolve, 800));
				}
				return origFetch(input, init);
			};
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		// The menu must close on select, before the backend answers…
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(row).toBeVisible();
		// …then the row disappears once the delete lands, and focus moves on to a
		// neighbouring row instead of dying with the removed trigger.
		await expect(row).toHaveCount(0);
		await expect(page.locator('[role="row"] [data-cell-target]:focus')).toHaveCount(1);
	});

	test('po smazání z řádkového menu pokračují šipky v seznamu místo otevírání menu', async ({
		page
	}) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		await page.getByRole('menu').getByRole('menuitem', { name: 'Smazat' }).click();
		await expect(row).toHaveCount(0);

		// Focus lands on the neighbouring row's subject cell — not on its Akce
		// trigger, where the next ArrowDown used to pop that row's menu open.
		const focused = page.locator('[data-cell-target]:focus');
		await expect(focused).toHaveAttribute('data-col', '2');

		await page.keyboard.press('ArrowDown');
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '2');
	});

	test('šipka dolů na tlačítku Akce neotevře menu, ale naviguje v gridu', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();

		// Grid semantics: ArrowDown moves to the next row's actions cell instead
		// of acting as a menubutton open key.
		await page.keyboard.press('ArrowDown');
		await expect(page.getByRole('menu')).toHaveCount(0);
		await expect(page.locator('[data-cell-target]:focus')).toHaveAttribute('data-col', '5');

		// Enter still opens the (now focused) neighbour's menu.
		await page.keyboard.press('Enter');
		await expect(page.getByRole('menu')).toBeVisible();
	});

	test('Smazat z řádkového menu v režimu bez podokna čtení zavře menu', async ({ page }) => {
		await setPrefs(page, { readingPane: 'off' });
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		const menu = page.getByRole('menu');
		await expect(menu).toBeVisible();
		await menu.getByRole('menuitem', { name: 'Smazat' }).click();

		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Přesunout z řádkového menu pošle cílovou složku a zavře menu', async ({ page }) => {
		const moveBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				new RegExp(`/api/v1/messages/${fixture.stableId}/move$`).test(request.url())
			) {
				moveBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).click();
		await page.getByRole('menuitem', { name: 'Přesunout' }).click();
		await page.getByRole('menuitem', { name: 'Archiv', exact: true }).click();

		/*
		 * Polled, not read once. `moveBodies` is filled by a `page.on('request')`
		 * listener, so it is asynchronous state, and neither the click above nor
		 * a key press waits for the app to reach the network — unlike the toolbar
		 * suite, where an awaited outcome toast stands between the action and the
		 * assertion and the request is necessarily out by then. This read had
		 * nothing in front of it, and it went red on main on a docs-only commit
		 * (d29df5f, `Received: Array []`), which is what a plain read of an array
		 * that fills later looks like when the machine is slow enough.
		 */
		await expect.poll(() => moveBodies).toEqual([{ folderRef: 'ARCHIVE' }]);
		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('Přesunout z řádkového menu klávesnicí: submenu otevře šipka a složku najde psaní', async ({
		page
	}) => {
		const moveBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				new RegExp(`/api/v1/messages/${fixture.stableId}/move$`).test(request.url())
			) {
				moveBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();

		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();
		await page.keyboard.press('Enter');
		await expect(page.getByRole('menuitem', { name: FIRST_ITEM, exact: true })).toBeFocused();

		/*
		 * The keyboard path a screen-reader user takes: walk up to Move (loop
		 * wraps ArrowUp to the last item, Delete, so one more step lands on the
		 * submenu trigger), open the submenu with ArrowRight, then jump to the
		 * folder by typing its first letters instead of arrowing through the
		 * whole list. That typeahead is why this stayed a menu rather than
		 * becoming a filterable dialog — if it ever stops working, the
		 * affordance argument goes with it.
		 *
		 * Each step asserts where focus landed before the next key goes out, and
		 * the walk is paced — see KEY_PACE_MS for why both are needed.
		 */
		const walk: ReadonlyArray<{ key: string; lands: string }> = [
			{ key: 'ArrowUp', lands: 'Smazat' },
			{ key: 'ArrowUp', lands: 'Přesunout' },
			{ key: 'ArrowRight', lands: 'Odeslané' },
			{ key: 's', lands: 'Spam' }
		];
		for (const { key, lands } of walk) {
			await page.waitForTimeout(KEY_PACE_MS);
			await page.keyboard.press(key);
			await expect(page.getByRole('menuitem', { name: lands, exact: true })).toBeFocused();
		}

		await page.keyboard.press('Enter');

		// Polled for the reason given on the mouse path above; this is the read
		// that actually failed in CI.
		await expect.poll(() => moveBodies).toEqual([{ folderRef: 'JUNK' }]);
		await expect(row).toHaveCount(0);
		await expect(page.getByRole('menu')).toHaveCount(0);
	});

	test('submenu Přesunout se jmenuje a fokus dá rovnou první složce', async ({ page }) => {
		/*
		 * The submenu was the one panel in the app with no accessible name at all
		 * — neither `aria-label` nor `aria-labelledby` — which the four top-level
		 * menus had been given precisely because a nameless container is one a
		 * screen reader can only describe by reciting its contents.
		 *
		 * The focus half is deliberately the opposite assertion from its parent's,
		 * and it is measured rather than inherited: bits-ui runs a different
		 * handler for `SubContent` than for `Content`, and recording focusin while
		 * opening this submenu produced one event, the first item. The container
		 * never takes a turn here, so `MenuSubContent` installs no focus handler —
		 * this test is what keeps that claim honest if the library changes.
		 */
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();
		await page.keyboard.press('Enter');
		await expect(page.getByRole('menuitem', { name: FIRST_ITEM, exact: true })).toBeFocused();

		// Walk up to the Move sub-trigger the way the typeahead test above does.
		for (const key of ['ArrowUp', 'ArrowUp']) {
			await page.waitForTimeout(KEY_PACE_MS);
			await page.keyboard.press(key);
		}
		await expect(page.getByRole('menuitem', { name: 'Přesunout', exact: true })).toBeFocused();

		await page.evaluate(() => {
			const seen: string[] = [];
			(window as unknown as { __subFocus: string[] }).__subFocus = seen;
			document.addEventListener(
				'focusin',
				(e) => {
					const role = (e.target as HTMLElement | null)?.getAttribute?.('role');
					if (role === 'menu' || role === 'menuitem') seen.push(role);
				},
				true
			);
		});

		await page.waitForTimeout(KEY_PACE_MS);
		await page.keyboard.press('ArrowRight');

		const submenu = page.getByRole('menu').last();
		await expect(submenu.getByRole('menuitem').first()).toBeFocused();
		await page.waitForTimeout(KEY_PACE_MS);

		const focused = await page.evaluate(
			() => (window as unknown as { __subFocus: string[] }).__subFocus
		);
		expect(focused).not.toContain('menu');
		expect(focused).toContain('menuitem');
		await expect(submenu).toHaveAccessibleName('Přesunout');
	});

	test('otevřené menu nedá fokus svému kontejneru, jen první položce', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.getByRole('button', { name: `Akce pro zprávu ${fixture.subject}` }).focus();

		/*
		 * Counts focus events instead of polling the settled state, because the
		 * defect was never visible in the settled state: focus ended on the
		 * first item either way. bits-ui parks focus on the content element on
		 * open and reaches the item an `afterTick` later, and a screen reader
		 * reads the container during that gap — role="menu" has no value of its
		 * own, so NVDA announced the menu name and then every item in it (heard
		 * 2026-09-02). What the fix removes is the container's turn at focus,
		 * which only an event log can see.
		 */
		await page.evaluate(() => {
			const seen: string[] = [];
			(window as unknown as { __menuFocus: string[] }).__menuFocus = seen;
			document.addEventListener(
				'focusin',
				(e) => {
					const el = e.target as HTMLElement | null;
					const role = el?.getAttribute?.('role');
					if (role === 'menu' || role === 'menuitem') seen.push(role);
				},
				true
			);
		});

		await page.keyboard.press('Enter');
		const menu = page.getByRole('menu');
		await expect(menu.getByRole('menuitem', { name: FIRST_ITEM, exact: true })).toBeFocused();
		// The second pass through the container lands ~15 ms after the first;
		// settle past it so the assertion covers both, not just the opening one.
		await page.waitForTimeout(KEY_PACE_MS);

		const focused = await page.evaluate(
			() => (window as unknown as { __menuFocus: string[] }).__menuFocus
		);
		expect(focused).not.toContain('menu');
		expect(focused).toContain('menuitem');

		// The name is the other half: a container a screen reader may still
		// reach some other way must not be nameless.
		await expect(menu).toHaveAccessibleName(`Akce pro zprávu ${fixture.subject}`);
	});
});
