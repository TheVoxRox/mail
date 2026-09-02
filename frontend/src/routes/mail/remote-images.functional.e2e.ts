import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs } from '../e2e-helpers';

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
	 * buttons stay in the tab order (asserted above by clicking one by name).
	 */
	const liveAncestor = await banner.evaluate((element) => {
		const live = element.closest('[aria-live]');
		return live
			? `${live.tagName.toLowerCase()}[aria-live=${live.getAttribute('aria-live')}]`
			: null;
	});
	expect(liveAncestor).toBeNull();
});
