import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

/*
 * Which folder is a subfolder of which, as the backend sends it (`parentRef`).
 * The fixture's account 1 has "Projekty" with a subfolder "2024", whose ref
 * "Projekty/2024" starts with its parent's. The hierarchy has to be in the
 * accessibility tree, not only in the indent: the maintainer reads it with a
 * screen reader.
 */
test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('Hierarchie složek', () => {
	test('podsložka je v seznamu vnořeném do položky rodiče', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		const folders = page
			.getByRole('region', { name: 'Podokno pošty' })
			.getByRole('navigation', { name: 'Složky' });
		await expect(folders.getByRole('link', { name: '2024', exact: true })).toBeVisible();

		await expect(folders).toMatchAriaSnapshot(`
			- navigation "Složky":
			  - list:
			    - listitem:
			      - link "Projekty"
			      - list:
			        - listitem:
			          - link "2024"
		`);
	});

	test('v podsložce je aktuální jen ona, ne rodič, jehož ref je začátkem jejího', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX');
		const folders = page
			.getByRole('region', { name: 'Podokno pošty' })
			.getByRole('navigation', { name: 'Složky' });
		const subfolder = folders.getByRole('link', { name: '2024', exact: true });

		await subfolder.click();
		await page.waitForURL(`**/mail/1/${encodeURIComponent('Projekty/2024')}`);

		await expect(subfolder).toHaveAttribute('aria-current', 'page');
		await expect(folders.getByRole('link', { name: 'Projekty', exact: true })).not.toHaveAttribute(
			'aria-current'
		);
	});

	test('nabídka Přesunout dá podsložky do skupiny pojmenované rodičem', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.locator('[role="row"][data-stable-id="msg-02"]').click();
		await page.waitForURL('**/mail/1/INBOX/msg-02');
		// Opening a message parks focus in the body frame a frame later; a click
		// on the trigger inside that gap would race it.
		await waitForFocus(bodyFrame(page));

		await page
			.getByRole('toolbar', { name: 'Akce se zprávami' })
			.getByRole('button', { name: 'Přesunout', exact: true })
			.click();

		const menu = page.getByRole('menu', { name: 'Přesunout' });
		await expect(menu).toMatchAriaSnapshot(`
			- menu "Přesunout":
			  - menuitem "Projekty"
			  - group "Projekty":
			    - menuitem "2024"
		`);

		// Typeahead reaches into the group: the items are collected from the
		// whole panel, whatever wraps them.
		await expect(menu.getByRole('menuitem').first()).toBeFocused();
		await page.keyboard.press('2');
		await expect(menu.getByRole('menuitem', { name: '2024', exact: true })).toBeFocused();
	});
});
