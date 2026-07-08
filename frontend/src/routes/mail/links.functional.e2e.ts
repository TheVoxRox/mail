import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Regression cover for the message-body iframe LINK bridge (lib/mail/mailFrame.ts).
 * The body renders in an opaque-origin `sandbox="allow-scripts"` iframe with no
 * `allow-popups`, so a sanitizer-forced `target="_blank"` link is a dead click —
 * the engine blocks the popup before it reaches the app. A hash-pinned forwarder
 * `preventDefault`s a genuine anchor click and postMessages the href to the
 * parent, which opens it in the OS browser via shell:allow-open. Without the
 * bridge, clicking a link in an email does nothing (audit finding F1).
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
		window.localStorage.setItem('mail.messageBodyView', 'html');
	});
});

test('klik na odkaz v těle zprávy se přepošle ven místo mrtvé _blank navigace', async ({
	page
}) => {
	await page.goto('/mail/1/INBOX/msg-01');
	await waitForShell(page);

	const iframe = page.getByTitle('Obsah zprávy');
	await expect(iframe).toBeVisible();

	// Record the forwarder's link relay on the parent window. The frame is an
	// opaque origin (sandbox allow-scripts), so genuine relays arrive with
	// origin "null".
	await page.evaluate(() => {
		(window as unknown as { __linkRelays: string[] }).__linkRelays = [];
		window.addEventListener('message', (event: MessageEvent) => {
			if (event.origin !== 'null') return;
			const data = event.data as { __voxroxMailFrameLink?: unknown; href?: unknown };
			if (data && data.__voxroxMailFrameLink === true && typeof data.href === 'string') {
				(window as unknown as { __linkRelays: string[] }).__linkRelays.push(data.href);
			}
		});
	});

	// A trusted click (CDP input) on the sanitized body link, reached through the
	// opaque-origin frame. If the bridge is broken the click is swallowed and no
	// relay arrives.
	await page.frameLocator('iframe').locator('a[href="https://example.com/safe"]').click();

	await expect
		.poll(() => page.evaluate(() => (window as unknown as { __linkRelays: string[] }).__linkRelays))
		.toContain('https://example.com/safe');

	// preventDefault + no popup: the click must not navigate the frame or the app.
	await expect(page).toHaveURL(/\/mail\/1\/INBOX\/msg-01$/);
});
