import { expect, test, type Locator, type Page } from '@playwright/test';
import { bodyFrame, expectAnnouncedOnce, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

const BANNER = 'Blokované obrázky z internetu';
// The fixtures' messages all come from this one address.
const TRUST_BUTTON = 'Vždy načítat obrázky od newsletter@example.com';
const BLOCKED_ANNOUNCEMENT = 'Obrázky z internetu jsou blokované.';

/*
 * Regression cover for the remote-image opt-in (audit finding F2). Remote images
 * are blocked by default as a tracking-pixel defense: the backend preserves a
 * remote https image inertly in `data-voxrox-remote-src` (never a live src) and
 * the frame CSP is `img-src data:`. A banner lets the user load them for this
 * message; only then does the frame promote the URL to a real `src` and relax
 * its CSP to `img-src data: https:`. Fixture msg-02 carries one remote image.
 */

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right', messageBodyView: 'html' });
});

test('vzdálené obrázky jsou blokované, dokud je uživatel nenačte', async ({ page }) => {
	await openApp(page, '/mail/1/INBOX/msg-02');

	const iframe = bodyFrame(page);
	await expect(iframe).toBeVisible();

	// Default: banner shown, and the srcdoc keeps the image inert — no live src,
	// CSP img-src blocks everything but data:.
	const banner = page.getByRole('region', { name: 'Blokované obrázky z internetu' });
	await expect(banner).toBeVisible();
	// The banner says why, not only that: testers could not tell what it was for.
	await expect(banner).toContainText(
		'Zpráva obsahuje 1 obrázek z internetu, který jsme nenačetli. Načtení obrázků z internetu může odesílateli prozradit, že jste zprávu otevřeli.'
	);

	const before = (await iframe.getAttribute('srcdoc')) ?? '';
	expect(before).toContain('data-voxrox-remote-src="https://cdn.example.test/logo.png"');
	expect(before).toContain('img-src data:;');
	// A live src is space-separated from the tag; the data-*-remote-src attr is not.
	expect(before).not.toContain(' src="https://cdn.example.test/logo.png"');

	// Opt in for this message.
	await page.getByRole('button', { name: 'Načíst obrázky' }).click();

	// Banner clears and the frame now loads the image over the relaxed https CSP.
	await expect(banner).toBeHidden();
	const after = (await iframe.getAttribute('srcdoc')) ?? '';
	expect(after).toContain('src="https://cdn.example.test/logo.png"');
	expect(after).toContain('img-src data: https:');
	expect(after).not.toContain('data-voxrox-remote-src');
});

test('baner blokovaných obrázků se nečte sám', async ({ page }) => {
	await openApp(page, '/mail/1/INBOX/msg-02');

	const banner = page.getByRole('region', { name: 'Blokované obrázky z internetu' });
	await expect(banner).toBeVisible();

	/*
	 * The banner used to be a polite live region. In split mode every arrow key
	 * is a full navigation, so the reading pane reloads per row and the region
	 * read itself out in full — label, count and both button names — over the
	 * row the reader had just announced. What replaces it is reachability, not
	 * an announcement: the region keeps its name for the landmark key, and both
	 * buttons stay in the tab order — each half asserted by a test of its own
	 * below, because a synthetic click by name proves neither.
	 */
	const liveAncestor = await banner.evaluate((element) => {
		const live = element.closest('[aria-live]');
		return live
			? `${live.tagName.toLowerCase()}[aria-live=${live.getAttribute('aria-live')}]`
			: null;
	});
	expect(liveAncestor).toBeNull();
});

test('záměrné otevření zprávy řekne jednou větou, že obrázky jsou blokované', async ({ page }) => {
	/*
	 * Enter puts the reading cursor in the body, past the banner, and the banner
	 * says nothing by itself. One short sentence, pushed where the body takes
	 * focus, tells the reader there is something above them to go back for.
	 */
	await openApp(page, '/mail/1/INBOX');

	const row = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
	await expect(row).toBeVisible();
	await row.press('Enter');
	await page.waitForURL('**/mail/1/INBOX/msg-02');

	await expectAnnouncedOnce(page, BLOCKED_ANNOUNCEMENT);
	await waitForFocus(bodyFrame(page));
});

test('řádek, který podokno čtení jen následuje, blokované obrázky neohlásí', async ({ page }) => {
	/*
	 * The reason the banner stopped being a live region: in the split pane an
	 * arrow key loads the next message, and the announcement landed on top of
	 * the row the reader had just heard. The sentence above must stay tied to a
	 * deliberate open.
	 */
	await openApp(page, '/mail/1/INBOX');

	const first = page.locator('[role="row"][data-stable-id="msg-01"] [data-col="2"]');
	await expect(first).toBeVisible();

	/*
	 * Everything the polite region ever holds from here on is kept. Looking at
	 * the region afterwards is not enough: it drops a message 1.5 s after it
	 * arrives, and the waits below can take longer than that, so a sentence
	 * that was said and already removed would read as one never said.
	 */
	await page.evaluate(() => {
		const region = document.querySelector('#live-region');
		if (!region) throw new Error('the polite live region is not mounted');
		const heard: string[] = [];
		new MutationObserver(() => heard.push(region.textContent ?? '')).observe(region, {
			childList: true,
			subtree: true,
			characterData: true
		});
		(window as unknown as { __heard: string[] }).__heard = heard;
	});

	await first.press('ArrowDown');
	await page.waitForURL('**/mail/1/INBOX/msg-02');

	// The message with the blocked image is in the pane, and focus is still in
	// the list: this is the state the announcement must not fire in.
	await expect(page.getByRole('region', { name: BANNER })).toBeVisible();
	await waitForFocus(page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]'));
	// It would be pushed a frame after the body renders; give it two.
	await page.evaluate(
		() =>
			new Promise<void>((resolve) => {
				requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
			})
	);
	const heard = await page.evaluate(() =>
		(window as unknown as { __heard: string[] }).__heard.join('\n')
	);
	expect(heard).not.toContain(BLOCKED_ANNOUNCEMENT);
});

test('obě tlačítka baneru jsou dosažitelná Tabem mezi řádkem a tělem', async ({ page }) => {
	/*
	 * The reachability the removed live region was traded for. Clicking a button
	 * by name (the test above) proves it exists, not that a keyboard ever gets
	 * to it — and "between the row and the body" is the half that decides
	 * whether the offer is findable at all without the announcement.
	 *
	 * The walk asserts the two buttons and the frame, not the whole sequence:
	 * what sits between them belongs to the detail toolbar and would make this
	 * fail for reasons that have nothing to do with the banner.
	 */
	await openApp(page, '/mail/1/INBOX/msg-02');
	await expect(page.getByRole('region', { name: 'Blokované obrázky z internetu' })).toBeVisible();

	const row = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
	await expect(row).toBeVisible();
	await row.focus();
	await waitForFocus(row);

	const focusedName = () =>
		page.evaluate(() => {
			const el = document.activeElement as HTMLElement | null;
			if (!el) return null;
			if (el.tagName === 'IFRAME') return el.getAttribute('title');
			return el.getAttribute('aria-label') ?? el.textContent?.trim() ?? el.tagName;
		});

	// The bound is "more stops than the toolbar has", not a measurement of it:
	// the toolbar grows (Print was the most recent), and a bound that only just
	// fitted turned this into a test of the button count.
	const walk: string[] = [];
	for (let step = 0; step < 30; step += 1) {
		await page.keyboard.press('Tab');
		const name = (await focusedName()) ?? '';
		walk.push(name);
		if (name === 'Obsah zprávy') break;
	}

	expect(walk).toContain('Obsah zprávy');
	expect(walk).toContain('Načíst obrázky');
	expect(walk).toContain(TRUST_BUTTON);
	// Order matters as much as presence: past the frame the offer is not "on the
	// way to the body" any more, it is behind it.
	expect(walk.indexOf('Načíst obrázky')).toBeLessThan(walk.indexOf('Obsah zprávy'));
	expect(walk.indexOf(TRUST_BUTTON)).toBeLessThan(walk.indexOf('Obsah zprávy'));
});

test('načtení obrázků nenechá fokus spadnout na body a řekne, co se stalo', async ({ page }) => {
	/*
	 * The press destroys the region the pressed button lives in, so focus had
	 * nowhere to go and fell to <body> — where a screen reader reads nothing and
	 * the way back is the landmark key. And since the banner deliberately
	 * stopped being a live region, nothing said the images had loaded either.
	 * Focus therefore lands on the frame the user just asked to see, and the
	 * outcome goes through the app's polite announcer.
	 */
	await openApp(page, '/mail/1/INBOX/msg-02');

	const banner = page.getByRole('region', { name: 'Blokované obrázky z internetu' });
	await expect(banner).toBeVisible();

	await page.getByRole('button', { name: 'Načíst obrázky' }).click();
	await expect(banner).toBeHidden();

	await expect(page.locator('#live-region')).toContainText('Obrázky z internetu načteny.');
	await waitForFocus(bodyFrame(page));
});

test('důvěra odesílateli se ohlásí jako trvalá, ne jen jako načtení', async ({ page }) => {
	// The two buttons do different things — one is remembered, one is not — so
	// they must not report the same outcome.
	await openApp(page, '/mail/1/INBOX/msg-02');

	const banner = page.getByRole('region', { name: 'Blokované obrázky z internetu' });
	await expect(banner).toBeVisible();

	await page.getByRole('button', { name: TRUST_BUTTON }).click();
	await expect(banner).toBeHidden();

	// Both name the address: it is what gets trusted, and the sender's display
	// name in the header above can say something else.
	await expect(page.locator('#live-region')).toContainText(
		'Obrázky od newsletter@example.com se budou načítat vždy, obrázky v této zprávě jsou načtené.'
	);
	await waitForFocus(bodyFrame(page));
});

test('ve složce Spam baner nenabízí trvalou důvěru odesílateli', async ({ page }) => {
	/*
	 * The allow-list is keyed on the From address, which anyone can write, and
	 * junk is where a message forged in a trusted sender's name ends up. The
	 * backend does not apply the list there, so offering to extend it from such
	 * a message would be a button that remembers something it will not honor.
	 * Loading this one message's images stays possible.
	 */
	await openApp(page, '/mail/1/JUNK/junk-plan-01');

	const banner = page.getByRole('region', { name: BANNER });
	await expect(banner).toBeVisible();
	await expect(banner.getByRole('button', { name: 'Načíst obrázky' })).toBeVisible();
	await expect(banner.getByRole('button')).toHaveCount(1);

	await banner.getByRole('button', { name: 'Načíst obrázky' }).click();
	await expect(banner).toBeHidden();
	expect((await bodyFrame(page).getAttribute('srcdoc')) ?? '').toContain(
		'src="https://cdn.example.test/prize.png"'
	);
});

test('Ctrl+Shift+W přenese fokus z těla zprávy na nabídku načtení obrázků', async ({ page }) => {
	/*
	 * Opening a message puts the reading cursor in the body, past the banner,
	 * and the banner is silent on purpose. This is Outlook's key for the strip
	 * under a message's header. Pressed on the frame, so it also travels the
	 * route a reader's key takes: through the frame's forwarder and back out.
	 */
	await openApp(page, '/mail/1/INBOX/msg-02');
	await expect(page.getByRole('region', { name: BANNER })).toBeVisible();

	await bodyFrame(page).press('Control+Shift+W');

	await waitForFocus(page.getByRole('button', { name: 'Načíst obrázky' }));
});

test('Ctrl+Shift+W u zprávy bez blokovaných obrázků řekne, že žádné nejsou', async ({ page }) => {
	// A press that does nothing and says nothing leaves a screen-reader user
	// unsure the key landed at all.
	await openApp(page, '/mail/1/INBOX/msg-03');
	await expect(bodyFrame(page)).toBeVisible();
	await expect(page.getByRole('region', { name: BANNER })).toHaveCount(0);

	await bodyFrame(page).press('Control+Shift+W');

	await expect(page.locator('#live-region')).toContainText(
		'Tato zpráva nemá žádné blokované obrázky z internetu.'
	);
});

test('paleta příkazů načte obrázky a nabízí to jen zprávě, která nějaké blokuje', async ({
	page
}) => {
	const openPalette = async (from: Locator) => {
		await from.press('Control+K');
		await expect(page.locator('#command-palette-input')).toBeFocused();
	};
	const command = page.getByRole('option', { name: /Načíst obrázky z internetu v této zprávě/ });

	await openApp(page, '/mail/1/INBOX/msg-02');
	const banner = page.getByRole('region', { name: BANNER });
	await expect(banner).toBeVisible();

	// From the list, which is where a reader paging through a split pane is.
	await openPalette(page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]'));
	await page.locator('#command-palette-input').fill('obrázky');
	await expect(command).toBeVisible();
	await command.click();

	await expect(banner).toBeHidden();
	await expect(page.locator('#live-region')).toContainText('Obrázky z internetu načteny.');
	// The command moves focus to the body it has just filled in; the closing
	// palette must not take it back to the row Ctrl+K was pressed on.
	await waitForFocus(bodyFrame(page));

	// With the images showing there is nothing left to offer. The unfiltered
	// list is the control: the open message's other commands are in it.
	await openPalette(bodyFrame(page));
	await expect(page.getByRole('option', { name: /Vytisknout zprávu/ })).toBeVisible();
	await expect(command).toHaveCount(0);
});

test.describe('důvěryhodní odesílatelé v nastavení účtu', () => {
	const settingsList = (page: Page) =>
		page.getByRole('list', { name: 'Obrázky z internetu' }).getByRole('listitem');

	test('seznam jde projít a odesílatele odebrat, fokus zůstane v seznamu', async ({ page }) => {
		const deletes: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'DELETE' && request.url().includes('/remote-images/allowlist')) {
				deletes.push(request.url());
			}
		});

		await openApp(page, '/settings/accounts/1');
		await expect(
			page.getByRole('heading', { level: 2, name: 'Obrázky z internetu' })
		).toBeVisible();
		await expect(settingsList(page)).toHaveText([/faktury@example\.com/, /novinky@example\.com/]);

		// The pressed button leaves with its row. Focus goes to the row that
		// moved up into the gap, not to <body>.
		await page.getByRole('button', { name: 'Odebrat faktury@example.com' }).click();
		await expect(settingsList(page)).toHaveText([/novinky@example\.com/]);
		await expect(page.locator('#live-region')).toContainText(
			'Obrázky od faktury@example.com se už nebudou načítat samy.'
		);
		await waitForFocus(page.getByRole('button', { name: 'Odebrat novinky@example.com' }));

		// Both values as query parameters and no body: that is where
		// RemoteImageAllowlistController.disallow reads them.
		expect(deletes).toHaveLength(1);
		const sent = new URL(deletes[0]);
		expect(sent.searchParams.get('accountId')).toBe('1');
		expect(sent.searchParams.get('senderEmail')).toBe('faktury@example.com');

		// With the last one gone there is no row left to land on: the sentence
		// that says the list is empty takes focus.
		await page.getByRole('button', { name: 'Odebrat novinky@example.com' }).click();
		const empty = page.getByText('Zatím tu není žádný odesílatel.', { exact: false });
		await expect(empty).toBeVisible();
		await waitForFocus(empty);
	});

	test('účet bez důvěryhodných odesílatelů to řekne a neukáže prázdný seznam', async ({ page }) => {
		await openApp(page, '/settings/accounts/2');
		await expect(
			page.getByRole('heading', { level: 2, name: 'Obrázky z internetu' })
		).toBeVisible();
		await expect(page.getByText('Zatím tu není žádný odesílatel.', { exact: false })).toBeVisible();
		await expect(page.getByRole('list', { name: 'Obrázky z internetu' })).toHaveCount(0);
	});

	test('odebraný odesílatel má obrázky znovu blokované i ve zprávě, která už byla otevřená', async ({
		page
	}) => {
		/*
		 * The reading pane caches message bodies, and a body carries the
		 * "images may load" answer as it was when fetched. Taking the trust back
		 * has to reach a message already in that cache, or the images of a sender
		 * the user just removed keep loading by themselves until the entry falls
		 * out. Each step below fetches or reuses the body on purpose.
		 */
		const rail = page.getByRole('navigation', { name: 'Přepínač prostředí' });
		const row = (stableId: string) => page.locator(`[role="row"][data-stable-id="${stableId}"]`);
		const banner = page.getByRole('region', { name: BANNER });

		await openApp(page, '/mail/1/INBOX/msg-02');
		await expect(banner).toBeVisible();
		await page.getByRole('button', { name: TRUST_BUTTON }).click();
		await expect(banner).toBeHidden();

		// Away and back: the body is fetched again, now from a trusted sender,
		// and that answer is what the cache holds from here on.
		await row('msg-03').click();
		await page.waitForURL('**/mail/1/INBOX/msg-03');
		await row('msg-02').click();
		await page.waitForURL('**/mail/1/INBOX/msg-02');
		await expect(bodyFrame(page)).toHaveAttribute(
			'srcdoc',
			/ src="https:\/\/cdn\.example\.test\/logo\.png"/
		);
		await expect(banner).toHaveCount(0);

		// In-app navigation throughout: a reload would empty the cache and the
		// mock's list with it, and prove nothing.
		await rail.getByRole('link', { name: 'Nastavení (Ctrl+3)' }).click();
		await page
			.getByRole('region', { name: 'Podokno nastavení' })
			.getByRole('link', { name: 'Účty' })
			.click();
		await page
			.getByRole('listitem')
			.filter({ hasText: 'Pracovní účet' })
			.getByRole('link', { name: 'Upravit' })
			.click();
		await page.waitForURL('**/settings/accounts/1');
		await page.getByRole('button', { name: 'Odebrat newsletter@example.com' }).click();
		await expect(page.getByRole('button', { name: 'Odebrat newsletter@example.com' })).toHaveCount(
			0
		);

		await rail.getByRole('link', { name: 'Pošta (Ctrl+1)' }).click();
		await page.waitForURL('**/mail/1/INBOX**');
		await row('msg-02').click();
		await page.waitForURL('**/mail/1/INBOX/msg-02');

		await expect(banner).toBeVisible();
		expect((await bodyFrame(page).getAttribute('srcdoc')) ?? '').not.toContain(
			' src="https://cdn.example.test/logo.png"'
		);
	});
});
