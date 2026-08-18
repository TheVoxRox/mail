import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs } from '../e2e-helpers';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('Mail HTML sanitizer', () => {
	test('odstraní aktivní obsah a vzdálené zdroje z HTML zprávy', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX/msg-01');

		const iframe = bodyFrame(page);
		await expect(iframe).toBeVisible();

		const srcdoc = (await iframe.getAttribute('srcdoc')) ?? '';

		expect(srcdoc).toContain('<strong>Projektové podklady</strong>');
		expect(srcdoc).toContain('href="https://example.com/safe"');
		// The srcdoc now carries exactly one trusted script — the hash-pinned key
		// forwarder (mailFrame.ts) — so assert the hostile inline script payload is
		// gone rather than that no <script> exists at all.
		expect(srcdoc).not.toContain('window.__xss');
		expect(srcdoc.split('<script>').length - 1).toBe(1);
		expect(srcdoc).not.toContain('onclick');
		expect(srcdoc).not.toContain('onerror');
		expect(srcdoc).not.toContain('javascript:');
		expect(srcdoc).not.toContain('tracker.example.test');
	});
});

test.describe('Zobrazení těla jako prostý text', () => {
	test.beforeEach(async ({ page }) => {
		await setPrefs(page, { messageBodyView: 'plain' });
	});

	test('zploští HTML na čitelný text místo zobrazení holých značek', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX/msg-01');

		// Plain-text view renders directly in the document — no sandboxed iframe.
		await expect(bodyFrame(page)).toHaveCount(0);

		const main = page.locator('#main-content');
		await expect(main).toContainText('Projektové podklady');

		// The content endpoint returns display HTML; the plain-text view must flatten
		// it, not dump the raw markup (or a <script> body) into the pane.
		const text = await main.innerText();
		expect(text).not.toContain('<strong>');
		expect(text).not.toContain('<div');
		expect(text).not.toContain('<p');
		expect(text).not.toContain('window.__xss');
	});
});
