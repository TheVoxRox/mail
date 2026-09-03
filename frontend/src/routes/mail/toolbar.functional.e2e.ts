import { expect, test } from '@playwright/test';
import { openApp, setPrefs } from '../e2e-helpers';

const fixture = {
	accountId: 1,
	folderName: 'INBOX',
	stableId: 'msg-01',
	moveStableId: 'msg-02',
	replyPrefill: {
		to: 'Jana Novak <jana@example.com>',
		subject: 'Re: Projektové podklady'
	}
};

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('Mail toolbar', () => {
	test('reply akce v toolbaru otevře compose s prefillem vybrané zprávy', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.click();

		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.stableId)}`
		);
		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar).toBeVisible();

		const replyButton = toolbar.getByRole('button', { name: 'Odpovědět', exact: true });
		await expect(replyButton).toHaveAttribute('aria-keyshortcuts', 'Control+R');
		await replyButton.click();

		await page.waitForURL(new RegExp(`/compose\\?reply=${fixture.stableId}`));
		await expect(page.getByText(fixture.replyPrefill.to)).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue(fixture.replyPrefill.subject);
	});

	test('po flag PATCH se znovu otevřený detail nenačte ze staré cache', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`);
		await expect(row).toBeVisible();
		await row.click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await expect(toolbar.getByRole('button', { name: 'Zrušit hvězdičku' })).toBeVisible();
		await toolbar.getByRole('button', { name: 'Zrušit hvězdičku' }).click();
		await expect(toolbar.getByRole('button', { name: 'Označit hvězdičkou' })).toBeVisible();

		await page.getByRole('button', { name: 'Zpět' }).click();
		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);
		await expect(toolbar.getByRole('button', { name: 'Označit hvězdičkou' })).toBeVisible();
	});

	test('samostatné přepnutí hvězdičky a přečtení se ohlásí do live regionu', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		// msg-02 is flagged in the fixtures; opening it auto-marks it read
		// (that path is deliberately silent — only the explicit toggles talk).
		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Zrušit hvězdičku' }).click();
		await expect(page.locator('#live-region')).toContainText('Hvězdička zrušena.');

		await toolbar.getByRole('button', { name: 'Označit jako nepřečtené' }).click();
		await expect(page.locator('#live-region')).toContainText('Označeno jako nepřečtené.');
	});

	test('přesun zprávy pošle MoveRequest a refreshne zdrojový i cílový seznam', async ({ page }) => {
		const moveBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-02\/move$/.test(request.url())) {
				moveBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Přesunout', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Archiv', exact: true }).click();

		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await expect(
			page.getByRole('status').filter({ hasText: 'Zpráva přesunuta do složky Archiv.' })
		).toBeVisible();
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toHaveCount(0);
		expect(moveBodies).toEqual([{ folderRef: 'ARCHIVE' }]);

		await page.getByRole('link', { name: 'Archiv' }).click();
		await page.waitForURL(`**/mail/${fixture.accountId}/ARCHIVE`);
		await expect(
			page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`)
		).toBeVisible();
		await expect(page.getByText('Testovací zpráva 2')).toBeVisible();
	});

	test('přesun do spamu přes nabídku Přesunout pošle JUNK na move endpoint', async ({ page }) => {
		const junkBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-07\/move$/.test(request.url())) {
				junkBodies.push(request.postDataJSON());
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.locator('[role="row"][data-stable-id="msg-07"]').click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/msg-07`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Přesunout', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Spam', exact: true }).click();

		await page.waitForURL(`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await expect(
			page.getByRole('status').filter({ hasText: 'Zpráva přesunuta do složky Spam.' })
		).toBeVisible();
		expect(junkBodies).toEqual([{ folderRef: 'JUNK' }]);
		await expect(page.locator('[role="row"][data-stable-id="msg-07"]')).toHaveCount(0);
	});

	test('multiselect zpráv podporuje hromadné přečtení, přesun a smazání', async ({ page }) => {
		const flagRequests: string[] = [];
		const moveBodies: unknown[] = [];
		const deleteRequests: string[] = [];

		page.on('request', (request) => {
			const url = request.url();
			if (request.method() === 'PATCH' && /\/api\/v1\/messages\/msg-0[12]\/flags/.test(url)) {
				flagRequests.push(url);
			}
			if (request.method() === 'POST' && /\/api\/v1\/messages\/msg-0[34]\/move$/.test(url)) {
				moveBodies.push(request.postDataJSON());
			}
			if (request.method() === 'DELETE' && /\/api\/v1\/messages\/msg-0[56]$/.test(url)) {
				deleteRequests.push(url);
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 2', exact: true })
			.check();
		await expect(page.getByText('2 vybrané zprávy')).toBeVisible();

		await page.getByRole('button', { name: 'Označit jako…', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Přečtené', exact: true }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Označeno jako přečtené: 2.' })
		).toBeVisible();
		expect(flagRequests).toHaveLength(2);
		expect(
			flagRequests.every((url) => url.includes('type=seen') && url.includes('value=true'))
		).toBe(true);

		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 3', exact: true })
			.check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 4', exact: true })
			.check();
		await page.getByRole('button', { name: 'Přesunout vybrané' }).click();
		await page.getByRole('menuitem', { name: 'Archiv', exact: true }).click();
		await expect(
			page.getByRole('status').filter({ hasText: 'Přesunuto do složky Archiv: 2.' })
		).toBeVisible();
		expect(moveBodies).toEqual([{ folderRef: 'ARCHIVE' }, { folderRef: 'ARCHIVE' }]);
		await expect(page.locator('[role="row"][data-stable-id="msg-03"]')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="msg-04"]')).toHaveCount(0);

		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 5', exact: true })
			.check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 6', exact: true })
			.check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();
		await expect(page.getByRole('status').filter({ hasText: 'Smazáno: 2.' })).toBeVisible();
		expect(deleteRequests).toHaveLength(2);
		await expect(page.locator('[role="row"][data-stable-id="msg-05"]')).toHaveCount(0);
		await expect(page.locator('[role="row"][data-stable-id="msg-06"]')).toHaveCount(0);
	});

	test('výběr zprávy oznámí dostupné hromadné akce a panel má roli toolbar', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const bulkToolbar = page.getByRole('toolbar', { name: 'Hromadné akce', exact: true });
		await expect(bulkToolbar).toBeVisible();
		// Action buttons are absent until something is selected.
		await expect(bulkToolbar.getByRole('button', { name: 'Smazat vybrané' })).toHaveCount(0);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();

		// First selection announces the available actions to screen readers.
		await expect(page.locator('#live-region')).toContainText('Hromadné akce nad seznamem');

		// The action buttons now live inside the labelled bulk toolbar.
		await expect(
			bulkToolbar.getByRole('button', { name: 'Označit jako…', exact: true })
		).toBeVisible();
		await expect(bulkToolbar.getByRole('button', { name: 'Smazat vybrané' })).toBeVisible();
	});

	test('hromadné označení nepřečtené přes dropdown Označit jako… pošle value=false', async ({
		page
	}) => {
		const flagRequests: string[] = [];
		page.on('request', (request) => {
			const url = request.url();
			if (request.method() === 'PATCH' && /\/api\/v1\/messages\/msg-0[12]\/flags/.test(url)) {
				flagRequests.push(url);
			}
		});

		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 2', exact: true })
			.check();

		await page.getByRole('button', { name: 'Označit jako…', exact: true }).click();
		await page.getByRole('menuitem', { name: 'Nepřečtené', exact: true }).click();

		await expect(
			page.getByRole('status').filter({ hasText: 'Označeno jako nepřečtené: 2.' })
		).toBeVisible();
		expect(flagRequests).toHaveLength(2);
		expect(
			flagRequests.every((url) => url.includes('type=seen') && url.includes('value=false'))
		).toBe(true);
	});

	test('smazání jedné zprávy klávesou Delete ohlásí její předmět', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		const row = page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`);
		await expect(row).toBeVisible();
		await row.focus();
		await row.press('Delete');

		// The single-delete outcome names which message was deleted, so a
		// screen-reader user hears what just disappeared (not a bare "deleted").
		await expect(
			page.getByRole('status').filter({ hasText: 'Zpráva smazána: Projektové podklady' })
		).toBeVisible();
		await expect(page.locator(`[role="row"][data-stable-id="${fixture.stableId}"]`)).toHaveCount(0);
	});

	test('hromadné smazání nepřečtených aktualizuje počet v nadpisu složky', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);

		// INBOX starts with 3 unread (msg-01/02/03) in the fixtures.
		const heading = page.getByRole('heading', { level: 1 });
		await expect(heading).toHaveAccessibleName(/3 nepřečten/);

		// Deleting two unread messages must drop the heading badge immediately —
		// the trash move is async, so a server re-fetch would leave it stale.
		await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Vybrat zprávu Testovací zpráva 2', exact: true })
			.check();
		await page.getByRole('button', { name: 'Smazat vybrané' }).click();

		await expect(page.getByRole('status').filter({ hasText: 'Smazáno: 2.' })).toBeVisible();
		await expect(heading).toHaveAccessibleName(/1 nepřečten/);
	});

	/*
	 * Both bulk menus carry the defect the row actions menu was fixed for:
	 * bits-ui parks focus on the panel when the menu opens and only reaches the
	 * first item an `afterTick` later, and a screen reader reads the nameless
	 * container by reciting its contents (NVDA read out every item — heard
	 * 2026-09-02 on the row menu, whose cause is in the library and therefore
	 * shared). Asserted by counting focus events, since the settled state is
	 * the same either way and only the route to it differs.
	 */
	for (const menu of [
		{ trigger: 'Označit jako…', name: 'Označit jako…' },
		{ trigger: 'Přesunout vybrané', name: 'Přesunout vybrané' }
	]) {
		test(`hromadné menu ${menu.trigger} nedá fokus svému kontejneru`, async ({ page }) => {
			await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
			await page.getByRole('checkbox', { name: 'Vybrat zprávu Projektové podklady' }).check();
			await expect(page.getByText('1 vybraná zpráva')).toBeVisible();

			await page.getByRole('button', { name: menu.trigger, exact: true }).focus();
			await page.evaluate(() => {
				const seen: string[] = [];
				(window as unknown as { __menuFocus: string[] }).__menuFocus = seen;
				document.addEventListener(
					'focusin',
					(e) => {
						const role = (e.target as HTMLElement | null)?.getAttribute?.('role');
						if (role === 'menu' || role === 'menuitem') seen.push(role);
					},
					true
				);
			});

			await page.keyboard.press('Enter');
			const panel = page.getByRole('menu');
			// Whichever item is first — the move menu lists folders, whose order
			// belongs to the folder store, not to this assertion.
			await expect(panel.getByRole('menuitem').first()).toBeFocused();
			await page.waitForTimeout(60);

			const focused = await page.evaluate(
				() => (window as unknown as { __menuFocus: string[] }).__menuFocus
			);
			expect(focused).not.toContain('menu');
			expect(focused).toContain('menuitem');
			await expect(panel).toHaveAccessibleName(menu.name);
		});
	}

	// The third menu with the same defect, and the only one reached from an open
	// message rather than from a selection — hence its own setup rather than a
	// third turn of the loop above.
	test('nabídka Přesunout v otevřené zprávě nedá fokus svému kontejneru', async ({ page }) => {
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Přesunout', exact: true }).focus();
		await page.evaluate(() => {
			const seen: string[] = [];
			(window as unknown as { __menuFocus: string[] }).__menuFocus = seen;
			document.addEventListener(
				'focusin',
				(e) => {
					const role = (e.target as HTMLElement | null)?.getAttribute?.('role');
					if (role === 'menu' || role === 'menuitem') seen.push(role);
				},
				true
			);
		});

		await page.keyboard.press('Enter');
		const panel = page.getByRole('menu');
		await expect(panel.getByRole('menuitem').first()).toBeFocused();
		await page.waitForTimeout(60);

		const focused = await page.evaluate(
			() => (window as unknown as { __menuFocus: string[] }).__menuFocus
		);
		expect(focused).not.toContain('menu');
		expect(focused).toContain('menuitem');
		await expect(panel).toHaveAccessibleName('Přesunout');
	});

	test('nabídka Více v panelu zprávy nedá fokus svému kontejneru', async ({ page }) => {
		/*
		 * The fifth menu, and the one that shows why the handler had to stop being
		 * something a menu opts into. #372 and #373 fixed four panels by adding
		 * `focusFirstMenuItem` to each by hand; this one was never in either list,
		 * so it kept giving focus to its own container while the fix was described
		 * as complete. It even had the accessible name already — #372 cited it as
		 * the example the row menu was copying — which is exactly why nobody
		 * looked at it twice.
		 *
		 * It is fixed here by construction rather than by another call site:
		 * MenuContent installs the handler, so the question "did this menu
		 * remember?" no longer has a per-menu answer.
		 *
		 * It only renders below 640px, where the toolbar collapses its buttons
		 * into an overflow menu — which is the other half of why it was missed,
		 * and no reason to leave it broken: the window is resizable and the menu
		 * is a real one when it is there.
		 */
		await page.setViewportSize({ width: 600, height: 800 });
		await openApp(page, `/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}`);
		await page.locator(`[role="row"][data-stable-id="${fixture.moveStableId}"]`).click();
		await page.waitForURL(
			`**/mail/${fixture.accountId}/${encodeURIComponent(fixture.folderName)}/${encodeURIComponent(fixture.moveStableId)}`
		);

		const toolbar = page.getByRole('toolbar', { name: 'Akce se zprávami' });
		await toolbar.getByRole('button', { name: 'Více', exact: true }).focus();
		await page.evaluate(() => {
			const seen: string[] = [];
			(window as unknown as { __menuFocus: string[] }).__menuFocus = seen;
			document.addEventListener(
				'focusin',
				(e) => {
					const role = (e.target as HTMLElement | null)?.getAttribute?.('role');
					if (role === 'menu' || role === 'menuitem') seen.push(role);
				},
				true
			);
		});

		await page.keyboard.press('Enter');
		const panel = page.getByRole('menu');
		await expect(panel.getByRole('menuitem').first()).toBeFocused();
		await page.waitForTimeout(60);

		const focused = await page.evaluate(
			() => (window as unknown as { __menuFocus: string[] }).__menuFocus
		);
		expect(focused).not.toContain('menu');
		expect(focused).toContain('menuitem');
		await expect(panel).toHaveAccessibleName('Více');
	});
});
