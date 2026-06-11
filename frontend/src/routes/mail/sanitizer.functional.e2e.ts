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
		expect(srcdoc).not.toContain('<script');
		expect(srcdoc).not.toContain('onclick');
		expect(srcdoc).not.toContain('onerror');
		expect(srcdoc).not.toContain('javascript:');
		expect(srcdoc).not.toContain('tracker.example.test');
	});
});
