import { expect, test, type Page } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

// First line is unique and ASCII, so it works as a plain RegExp probe against the
// textarea value regardless of the delimiter the composer prepends.
const SIGNATURE_FIRST_LINE = 'Tester z VoxRoxu';
const SIGNATURE = `${SIGNATURE_FIRST_LINE}\ntester@example.com`;

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

/**
 * Give account 1 (the default compose From account) a signature with the given
 * auto-insert state via the real edit form, then return to the account list.
 */
async function setAccountSignature(page: Page, autoInsert: boolean): Promise<void> {
	await page.goto('/settings/accounts/1');
	await waitForShell(page);
	await expect(page.getByRole('heading', { level: 1, name: /Upravit účet/ })).toBeVisible();

	await page.locator('#acc-signature').fill(SIGNATURE);
	await page
		.getByRole('checkbox', { name: 'Vkládat podpis automaticky do nových zpráv' })
		.setChecked(autoInsert);

	await page.getByRole('button', { name: 'Uložit změny' }).click();
	await page.waitForURL('**/settings/accounts');
}

/**
 * Open the composer by client-side navigation only. A full page reload re-runs the
 * MSW bootstrap (worker.ts → resetFixtures()), which would wipe the signature set
 * by {@link setAccountSignature}; routing through the in-app links preserves it.
 */
async function openComposeClientSide(page: Page): Promise<void> {
	await page.getByRole('link', { name: 'Pošta (Ctrl+1)' }).click();
	await page
		.getByRole('navigation', { name: 'Pošta' })
		.getByRole('button', { name: 'Nová zpráva Ctrl+N' })
		.click();
	await page.waitForURL('**/compose');
	await waitForShell(page);
	await expect(page.getByRole('form', { name: 'Nová zpráva' })).toBeVisible();
}

test.describe('Podpisy v compose', () => {
	test('auto-vkládání ZAP připojí podpis účtu do nové zprávy', async ({ page }) => {
		await setAccountSignature(page, true);
		await openComposeClientSide(page);

		await expect(page.locator('#compose-body')).toHaveValue(new RegExp(SIGNATURE_FIRST_LINE));
	});

	test('auto-vkládání VYP nepřipojí podpis, ale tlačítko Vložit podpis ho vloží', async ({
		page
	}) => {
		await setAccountSignature(page, false);
		await openComposeClientSide(page);

		const body = page.locator('#compose-body');
		await expect(body).not.toHaveValue(new RegExp(SIGNATURE_FIRST_LINE));

		await page.getByRole('button', { name: 'Vložit podpis' }).click();
		await expect(body).toHaveValue(new RegExp(SIGNATURE_FIRST_LINE));
	});
});
