import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Regression cover for the remote-image opt-in (audit finding F2). Remote images
 * are blocked by default as a tracking-pixel defense: the backend preserves a
 * remote https image inertly in `data-voxrox-remote-src` (never a live src) and
 * the frame CSP is `img-src data:`. A banner lets the user load them for this
 * message; only then does the frame promote the URL to a real `src` and relax
 * its CSP to `img-src data: https:`. Fixture msg-02 carries one remote image.
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
		window.localStorage.setItem('mail.messageBodyView', 'html');
	});
});

test('vzdálené obrázky jsou blokované, dokud je uživatel nenačte', async ({ page }) => {
	await page.goto('/mail/1/INBOX/msg-02');
	await waitForShell(page);

	const iframe = page.getByTitle('Obsah zprávy');
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
