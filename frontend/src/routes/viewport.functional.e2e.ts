import { expect, test, type Page } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

const mailDetailPath = '/mail/1/INBOX/msg-01';

async function openMailDetailAt(page: Page, viewport: { width: number; height: number }) {
	await page.setViewportSize(viewport);
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});

	await page.goto(mailDetailPath);
	await waitForShell(page);
}

async function expectShellFitsViewport(page: Page) {
	const overflow = await page.evaluate(() => ({
		documentWidth: document.documentElement.scrollWidth,
		bodyWidth: document.body.scrollWidth,
		viewportWidth: window.innerWidth
	}));

	expect(overflow.documentWidth).toBeLessThanOrEqual(overflow.viewportWidth);
	expect(overflow.bodyWidth).toBeLessThanOrEqual(overflow.viewportWidth);
	await expect(page.getByRole('navigation', { name: 'Přepínač prostředí' })).toBeVisible();
	await expect(page.getByRole('region', { name: 'Podokno složek' })).toBeVisible();
	await expect(page.getByRole('toolbar', { name: 'Akce se zprávami' })).toBeVisible();
	await expect(
		page.locator('[role="separator"][aria-orientation="vertical"][tabindex="0"]')
	).toBeVisible();
}

test.describe('Viewport baseline', () => {
	test('doporučené desktop okno 1280x800 zobrazí mail shell bez horizontálního přetečení', async ({
		page
	}) => {
		await openMailDetailAt(page, { width: 1280, height: 800 });

		await expectShellFitsViewport(page);
		await expect(page.getByRole('heading', { name: 'Projektové podklady' })).toBeVisible();
	});

	test('minimální desktop okno 1024x768 udrží rail, sidebar, toolbar i reading pane', async ({
		page
	}) => {
		await openMailDetailAt(page, { width: 1024, height: 768 });

		await expectShellFitsViewport(page);
		await expect(page.getByRole('button', { name: /Více/ })).toHaveCount(0);
	});
});
