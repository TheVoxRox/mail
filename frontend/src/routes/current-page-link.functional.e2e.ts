import { expect, test, type Locator, type Page } from '@playwright/test';
import { openApp } from './e2e-helpers';

/*
 * A link to the page already open does nothing (lib/currentPageLinkGuard.ts).
 * SvelteKit 3 would navigate there with refreshAll: focus to <body>, then the
 * layout's afterNavigate parks it on the page heading, so Enter on the folder
 * already open in the pane would throw a screen reader user out of the pane.
 *
 * What is asserted is an absence, so the test first lets the whole route a
 * navigation would take run out: SvelteKit's click handler waits a frame and a
 * task before it navigates, and the layout moves focus a frame after that.
 */
test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
	});
});

async function settle(page: Page): Promise<void> {
	await page.evaluate(
		() =>
			new Promise<void>((resolve) =>
				requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(resolve, 500)))
			)
	);
}

async function expectEnterKeepsFocus(page: Page, link: Locator): Promise<void> {
	await expect(link).toHaveAttribute('aria-current', 'page');
	const url = page.url();

	await link.press('Enter');
	await settle(page);

	await expect(link).toBeFocused();
	expect(page.url()).toBe(url);
}

test.describe('Odkaz na otevřenou stránku', () => {
	test('Enter na otevřené složce nechá fokus v podokně', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		const inbox = page
			.getByRole('region', { name: 'Podokno pošty' })
			.getByRole('link', { name: /^Doručené/ });

		await expectEnterKeepsFocus(page, inbox);
	});

	test('Enter na otevřeném zobrazení kontaktů nechá fokus v podokně', async ({ page }) => {
		await openApp(page, '/contacts');
		const all = page
			.getByRole('region', { name: 'Podokno kontaktů' })
			.getByRole('navigation', { name: 'Zobrazení kontaktů' })
			.getByRole('link', { name: 'Všechny kontakty 1 kontakt' });

		await expectEnterKeepsFocus(page, all);
	});
});
