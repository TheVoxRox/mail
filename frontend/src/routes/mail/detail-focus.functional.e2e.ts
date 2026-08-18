import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

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
	await setPrefs(page, { locale: 'cs', readingPane: 'off' });
});

test.describe('Fokus na tělo otevřené zprávy', () => {
	test('dvojklik na HTML zprávu přesune fokus na iframe s tělem', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		// A click anywhere on the row is a deliberate open (the web-mail model),
		// so the reading cursor lands in the body just like it does on Enter.
		await page.locator('[role="row"][data-stable-id="msg-01"]').click();
		await page.waitForURL('**/mail/1/INBOX/msg-01');

		const frame = bodyFrame(page);
		await expect(frame).toBeVisible();
		await waitForFocus(frame);

		// Landmark + hidden heading: in off mode the subject is <h1>, the body <h2>.
		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region).toBeVisible();
		await expect(region.getByRole('heading', { name: 'Text zprávy', level: 2 })).toHaveCount(1);
	});

	test('plain-text zobrazení fokusuje textový kontejner', async ({ page }) => {
		await setPrefs(page, { messageBodyView: 'plain' });
		await openApp(page, '/mail/1/INBOX/msg-01');

		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region).toContainText('Projektové podklady');
		await expect(region.locator('div[tabindex="-1"]')).toBeFocused();
	});

	test('ve split režimu přesune Enter na řádku fokus do čtecího podokna', async ({ page }) => {
		await setPrefs(page, { readingPane: 'right' });
		await openApp(page, '/mail/1/INBOX');

		const row = page.locator('[role="row"][data-stable-id="msg-01"]');
		await expect(row).toBeVisible();
		await row.focus();
		await page.keyboard.press('Enter');
		await page.waitForURL('**/mail/1/INBOX/msg-01');

		const frame = bodyFrame(page);
		await waitForFocus(frame);

		// In split mode the subject is <h2>, so the body heading is <h3>.
		const region = page.getByRole('region', { name: 'Text zprávy' });
		await expect(region.getByRole('heading', { name: 'Text zprávy', level: 3 })).toHaveCount(1);
	});
});
