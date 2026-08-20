import { expect, test } from '@playwright/test';
import { openApp, setPrefs } from '../e2e-helpers';

/*
 * Capturing the sender of an open message into the address book. The sender
 * line offers exactly one of two things — add this address, or open the contact
 * it already belongs to — and whichever it offers, the way back is the message
 * the reader came from.
 *
 * Fixtures: msg-01 is from jana@example.com, which contact 1 already holds;
 * msg-02 is from sender2@example.com, which no contact does.
 */

const known = {
	stableId: 'msg-01',
	email: 'jana@example.com',
	contactId: 1
};

const unknown = {
	stableId: 'msg-02',
	email: 'sender2@example.com',
	name: 'Odesílatel',
	surname: '2'
};

function messageUrl(stableId: string): string {
	return `/mail/1/INBOX/${stableId}`;
}

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'off' });
});

test.describe('Kontakt z otevřené zprávy', () => {
	test('neznámý odesílatel se přidá předvyplněným formulářem a vrátí zpět do zprávy', async ({
		page
	}) => {
		await openApp(page, messageUrl(unknown.stableId));

		const add = page.getByRole('link', { name: `Přidat do kontaktů, ${unknown.email}` });
		await expect(add).toBeVisible();
		await add.click();

		await page.waitForURL(/\/contacts\?.*create=1/);
		// Still the new-contact form, not the edit one: a seed is not an identity.
		await expect(page.getByRole('heading', { name: 'Nový kontakt' })).toBeVisible();
		await expect(page.locator('#contact-name')).toHaveValue(unknown.name);
		await expect(page.locator('#contact-surname')).toHaveValue(unknown.surname);
		await expect(page.getByLabel('Adresa 1')).toHaveValue(unknown.email);

		await page.getByRole('button', { name: 'Uložit' }).click();

		await page.waitForURL(`**${messageUrl(unknown.stableId)}`);
		// The same line now offers the contact instead of offering to add it.
		await expect(
			page.getByRole('link', { name: `Zobrazit kontakt, ${unknown.email}` })
		).toBeVisible();
	});

	test('formulář se otevře s kurzorem v poli Jméno', async ({ page }) => {
		await openApp(page, messageUrl(unknown.stableId));

		await page.getByRole('link', { name: `Přidat do kontaktů, ${unknown.email}` }).click();
		await page.waitForURL(/\/contacts\?.*create=1/);

		// The whole reason the form opens instead of the contact being written
		// straight away: whatever the header did not carry is typed here.
		await expect(page.locator('#contact-name')).toBeFocused();
	});

	test('zrušení formuláře se vrátí do zprávy, ne do seznamu kontaktů', async ({ page }) => {
		await openApp(page, messageUrl(unknown.stableId));

		await page.getByRole('link', { name: `Přidat do kontaktů, ${unknown.email}` }).click();
		await page.waitForURL(/\/contacts\?.*create=1/);

		await page.getByRole('button', { name: 'Zrušit' }).click();

		await page.waitForURL(`**${messageUrl(unknown.stableId)}`);
		// Nothing was saved, so the offer is unchanged.
		await expect(
			page.getByRole('link', { name: `Přidat do kontaktů, ${unknown.email}` })
		).toBeVisible();
	});

	test('známý odesílatel nabízí jen svůj kontakt', async ({ page }) => {
		await openApp(page, messageUrl(known.stableId));

		await expect(
			page.getByRole('link', { name: `Zobrazit kontakt, ${known.email}` })
		).toBeVisible();
		await expect(page.getByRole('link', { name: /^Přidat do kontaktů/ })).toHaveCount(0);

		await page.getByRole('link', { name: `Zobrazit kontakt, ${known.email}` }).click();

		await page.waitForURL(new RegExp(`/contacts\\?.*edit=${known.contactId}`));
		await expect(page.getByRole('heading', { name: 'Upravit kontakt' })).toBeVisible();

		await page.getByRole('button', { name: 'Zrušit' }).click();
		await page.waitForURL(`**${messageUrl(known.stableId)}`);
	});
});
