import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Screen-reader guidance into an opened message (MessageContent.svelte):
 * opening a message moves focus to the start of the body — the iframe for an
 * HTML message, the text container for plain text — so the reading cursor
 * lands on the text instead of the top of the page (the Outlook model; Esc
 * already restores focus to the list row). The body is additionally wrapped
 * in a named region with a visually hidden heading ("Text zprávy") so it
 * stays reachable via the landmark (D) and heading (H) quick keys.
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'off');
	});
});

test.describe('Fokus na tělo otevřené zprávy', () => {
	test('dvojklik na HTML zprávu přesune fokus na iframe s tělem', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		// Off mode: the deliberate open is a double-click (Enter's mouse twin);
		// a single click would only move the roving focus.
		await page.locator('[role="row"][data-stable-id="msg-01"]').dblclick();
		await page.waitForURL('**/mail/1/INBOX/msg-01');

		const frame = page.getByTitle('Obsah zprávy');
		await expect(frame).toBeVisible();
		// activeElement poll instead of toBeFocused() — the matcher additionally
		// requires document.hasFocus(), which headless Chromium reports false on
		// the outer document while the programmatically focused sandbox <iframe>
		// holds focus (flaky in CI). Same workaround as the search test in
		// a11y.e2e.ts.
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);

		// Landmark + hidden heading: in off mode the subject is <h1>, the body <h2>.
		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region).toBeVisible();
		await expect(region.getByRole('heading', { name: 'Text zprávy', level: 2 })).toHaveCount(1);
	});

	test('plain-text zobrazení fokusuje textový kontejner', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.messageBodyView', 'plain');
		});
		await page.goto('/mail/1/INBOX/msg-01');
		await waitForShell(page);

		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region).toContainText('Projektové podklady');
		await expect(region.locator('div[tabindex="-1"]')).toBeFocused();
	});

	test('ve split režimu přesune Enter na řádku fokus do čtecího podokna', async ({ page }) => {
		await page.addInitScript(() => {
			window.localStorage.setItem('mail.readingPane', 'right');
		});
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const row = page.locator('[role="row"][data-stable-id="msg-01"]');
		await expect(row).toBeVisible();
		await row.focus();
		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-01');

		const frame = page.getByTitle('Obsah zprávy');
		// Same activeElement poll as above — toBeFocused() on the sandbox
		// <iframe> is flaky in headless CI (document.hasFocus() dependency).
		await expect.poll(() => frame.evaluate((el) => el === document.activeElement)).toBe(true);

		// In split mode the subject is <h2>, so the body heading is <h3>.
		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region.getByRole('heading', { name: 'Text zprávy', level: 3 })).toHaveCount(1);
	});
});
