import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs } from '../e2e-helpers';

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
	await setPrefs(page, { locale: 'cs', readingPane: 'right', messageBodyView: 'html' });
});

test('klik na odkaz v těle zprávy se přepošle ven místo mrtvé _blank navigace', async ({
	page
}) => {
	await openApp(page, '/mail/1/INBOX/msg-01');

	const iframe = bodyFrame(page);
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

/*
 * A text/plain body carries no markup, so a bare URL in it used to be inert
 * text — nothing to click and nothing a screen reader announces as a link
 * (audit F4). The backend now linkifies it (HtmlSanitizer.escapePlainText); this
 * asserts the resulting anchor survives the frontend allow-list inside <pre> and
 * reaches the same bridge as a link from an HTML mail.
 */
test('odkaz v textové zprávě je skutečný odkaz a přepošle se ven', async ({ page }) => {
	await openApp(page, '/mail/1/INBOX/msg-04');

	const iframe = bodyFrame(page);
	await expect(iframe).toBeVisible();

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

	const link = page.frameLocator('iframe').locator('a[href="https://example.com/plain"]');
	// The anchor is a real link in the accessibility tree, not styled text.
	await expect(link).toHaveText('https://example.com/plain');
	await link.click();

	await expect
		.poll(() => page.evaluate(() => (window as unknown as { __linkRelays: string[] }).__linkRelays))
		.toContain('https://example.com/plain');

	await expect(page).toHaveURL(/\/mail\/1\/INBOX\/msg-04$/);
});
