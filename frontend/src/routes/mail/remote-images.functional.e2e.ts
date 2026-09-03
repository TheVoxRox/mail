import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

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
	const banner = page.getByRole('region', { name: 'Blokované vzdálené obrázky' });
	await expect(banner).toBeVisible();

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

	const banner = page.getByRole('region', { name: 'Blokované vzdálené obrázky' });
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
	await expect(page.getByRole('region', { name: 'Blokované vzdálené obrázky' })).toBeVisible();

	const row = page.locator('[role="row"][data-stable-id="msg-02"] [data-col="2"]');
	await expect(row).toBeVisible();
	await row.focus();

	const focusedName = () =>
		page.evaluate(() => {
			const el = document.activeElement as HTMLElement | null;
			if (!el) return null;
			if (el.tagName === 'IFRAME') return el.getAttribute('title');
			return el.getAttribute('aria-label') ?? el.textContent?.trim() ?? el.tagName;
		});

	const walk: string[] = [];
	for (let step = 0; step < 15; step += 1) {
		await page.keyboard.press('Tab');
		const name = (await focusedName()) ?? '';
		walk.push(name);
		if (name === 'Obsah zprávy') break;
	}

	expect(walk).toContain('Obsah zprávy');
	expect(walk).toContain('Načíst obrázky');
	expect(walk).toContain('Vždy od tohoto odesílatele');
	// Order matters as much as presence: past the frame the offer is not "on the
	// way to the body" any more, it is behind it.
	expect(walk.indexOf('Načíst obrázky')).toBeLessThan(walk.indexOf('Obsah zprávy'));
	expect(walk.indexOf('Vždy od tohoto odesílatele')).toBeLessThan(walk.indexOf('Obsah zprávy'));
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

	const banner = page.getByRole('region', { name: 'Blokované vzdálené obrázky' });
	await expect(banner).toBeVisible();

	await page.getByRole('button', { name: 'Načíst obrázky' }).click();
	await expect(banner).toBeHidden();

	await expect(page.locator('#live-region')).toContainText('Vzdálené obrázky načteny.');
	await waitForFocus(bodyFrame(page));
});

test('důvěra odesílateli se ohlásí jako trvalá, ne jen jako načtení', async ({ page }) => {
	// The two buttons do different things — one is remembered, one is not — so
	// they must not report the same outcome.
	await openApp(page, '/mail/1/INBOX/msg-02');

	const banner = page.getByRole('region', { name: 'Blokované vzdálené obrázky' });
	await expect(banner).toBeVisible();

	await page.getByRole('button', { name: 'Vždy od tohoto odesílatele' }).click();
	await expect(banner).toBeHidden();

	await expect(page.locator('#live-region')).toContainText(
		'Odesílatel přidán mezi důvěryhodné, vzdálené obrázky načteny.'
	);
	await waitForFocus(bodyFrame(page));
});
