import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Mail HTML sanitizer', () => {
	test('odstraní aktivní obsah a vzdálené zdroje z HTML zprávy', async ({ page }) => {
		await page.goto('/mail/1/INBOX/msg-01');
		await waitForShell(page);

		const iframe = page.getByTitle('Obsah zprávy');
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
