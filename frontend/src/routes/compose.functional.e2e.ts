import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { waitForShell } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
	});
});

test.describe('Compose', () => {
	test('sidebar používá tlačítko Nová zpráva a Ctrl+N otevře compose', async ({ page }) => {
		await page.goto('/mail/1/INBOX');
		await waitForShell(page);

		const sidebar = page.getByRole('region', { name: 'Podokno pošty' });
		await expect(sidebar.getByRole('link', { name: /Nová zpráva/ })).toHaveCount(0);

		await sidebar.getByRole('button', { name: 'Nová zpráva Ctrl+N' }).click();
		await page.waitForURL('**/compose');
		await expect(page.getByRole('form', { name: 'Nová zpráva' })).toBeVisible();

		await page.goto('/mail/1/INBOX');
		await waitForShell(page);
		await page.locator('body').dispatchEvent('keydown', {
			key: 'n',
			code: 'KeyN',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/compose');
		await expect(page.getByRole('form', { name: 'Nová zpráva' })).toBeVisible();
	});

	test('nová zpráva fokusuje Komu a reply fokusuje tělo zprávy', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);
		await expect(page.locator('#compose-to')).toBeFocused();

		await page.goto('/compose?reply=msg-01&all=0');
		await waitForShell(page);
		await expect(page.locator('#compose-body')).toBeFocused();
		await expect(page.locator('#compose-subject')).toHaveValue(/Re:/);
	});

	test('forward prefill doplní Fwd předmět a citované tělo', async ({ page }) => {
		await page.goto('/compose?forward=msg-01');
		await waitForShell(page);

		await expect(page.locator('#compose-body')).toBeFocused();
		await expect(page.locator('#compose-subject')).toHaveValue('Fwd: Projektové podklady');
		await expect(page.locator('#compose-body')).toHaveValue(
			/\n\n--- Přeposlaná zpráva ---\nText zprávy Projektové podklady/
		);
	});

	test('reply prefill doplní adresáta, Re, citaci a reply hlavičky do draftu', async ({ page }) => {
		const draftBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/api/v1/accounts/1/drafts')) {
				draftBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/compose?reply=msg-01&all=0');
		await waitForShell(page);

		await expect(page.getByText('Jana Novak <jana@example.com>')).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue('Re: Projektové podklady');
		await expect(page.locator('#compose-body')).toHaveValue(
			/\n\n--- Původní zpráva ---\nText zprávy Projektové podklady/
		);

		await page.getByRole('button', { name: 'Uložit koncept' }).click();
		await page.waitForURL('**/mail/1/INBOX');

		expect(draftBodies).toHaveLength(1);
		expect(draftBodies[0]).toMatchObject({
			to: 'Jana Novak <jana@example.com>',
			subject: 'Re: Projektové podklady',
			body: expect.stringContaining('--- Původní zpráva ---\nText zprávy Projektové podklady'),
			inReplyTo: '<msg-01@example.com>',
			references: '<msg-01@example.com>'
		});
	});

	test('draft prefill načte existující koncept a při uložení ho nahradí', async ({ page }) => {
		const draftPosts: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/api/v1/accounts/1/drafts')) {
				draftPosts.push(request.url());
			}
		});

		await page.goto('/compose?draft=draft-42');
		await waitForShell(page);

		await expect(page.locator('#compose-body')).toBeFocused();
		await expect(page.getByText('tester@example.com', { exact: true })).toBeVisible();
		await expect(page.locator('#compose-subject')).toHaveValue('Rozepsaný koncept');
		// The composer is a plain-text editor; the HTML draft body is flattened on prefill.
		await expect(page.locator('#compose-body')).toHaveValue('HTML obsah pro Rozepsaný koncept.');

		await page.locator('#compose-body').fill('Upravený text existujícího konceptu.');
		await page.getByRole('button', { name: 'Uložit koncept' }).click();
		await page.waitForURL('**/mail/1/INBOX');

		expect(draftPosts).toHaveLength(1);
		expect(new URL(draftPosts[0]).searchParams.get('replaces')).toBe('draft-42');
	});

	test('odešle novou zprávu přes MSW API a vrátí se do inboxu', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await expect(page.getByRole('form', { name: 'Nová zpráva' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-subject').fill('E2E odeslání');
		await page.locator('#compose-body').fill('Tělo testovací zprávy.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('odeslání bez příjemce zobrazí chybu u pole Komu a zůstane na compose', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-subject').fill('Bez příjemce');
		await page.locator('#compose-body').fill('Tato zpráva nesmí odejít.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/compose');
		await expect(page.locator('#compose-to')).toHaveAttribute('aria-invalid', 'true');
		await expect(page.locator('#compose-to')).toHaveAttribute(
			'aria-describedby',
			'compose-to-error'
		);
		await expect(page.locator('#compose-to-error')).toContainText('Vyplňte adresu příjemce.');
		await expect(page.locator('#compose-body')).toHaveValue('Tato zpráva nesmí odejít.');
	});

	test('neplatná adresa v Komu se označí u adresního pole', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('neplatna-adresa');
		await page.locator('#compose-subject').fill('Neplatný příjemce');
		await page.locator('#compose-body').fill('Tato zpráva nesmí odejít.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/compose');
		await expect(page.locator('#compose-to')).toHaveAttribute('aria-invalid', 'true');
		await expect(page.locator('#compose-to-error')).toContainText(
			'Neplatná adresa: neplatna-adresa'
		);
		await expect(page.locator('#compose-body')).toHaveValue('Tato zpráva nesmí odejít.');
	});

	test('autocomplete adresátů vloží e-mail kontaktu do pole Komu', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('jana');
		const suggestions = page.getByRole('listbox', { name: 'Návrhy kontaktů' });
		await expect(suggestions).toBeVisible();
		await suggestions.getByRole('option', { name: /jana@example\.com/ }).click();

		await expect(page.getByText('jana@example.com')).toBeVisible();
		await expect(page.locator('#compose-to')).toHaveValue('');
	});

	test('Escape v poli adresátů zavře jen našeptávač, další Escape teprve zahazuje', async ({
		page
	}) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('jana');
		const suggestions = page.getByRole('listbox', { name: 'Návrhy kontaktů' });
		await expect(suggestions).toBeVisible();

		await page.keyboard.press('Escape');
		await expect(suggestions).toHaveCount(0);
		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toHaveCount(0);

		await page.keyboard.press('Escape');
		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toBeVisible();
	});

	test('přílohu lze přidat a odebrat přístupným tlačítkem', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('input[type="file"]').setInputFiles({
			name: 'poznamka.txt',
			mimeType: 'text/plain',
			buffer: Buffer.from('Text přílohy')
		});

		await expect(page.getByText('poznamka.txt')).toBeVisible();
		await page.getByRole('button', { name: 'Odebrat přílohu poznamka.txt' }).click();
		await expect(page.getByText('poznamka.txt')).toHaveCount(0);
	});

	test('odeslání během načítání přílohy se zablokuje, ať se příloha neztratí', async ({ page }) => {
		// Čtení souboru drží otevřená brána — test ji uvolní, žádné časování.
		await page.addInitScript(() => {
			const original = FileReader.prototype.readAsDataURL;
			const gate = new Promise<void>((resolve) => {
				(window as { __releaseFileRead?: () => void }).__releaseFileRead = resolve;
			});
			FileReader.prototype.readAsDataURL = function (blob: Blob) {
				void gate.then(() => original.call(this, blob));
			};
		});
		const sendBodies: unknown[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/messages/account/1/send')) {
				sendBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-subject').fill('Zpráva s pomalou přílohou');
		await page.locator('input[type="file"]').setInputFiles({
			name: 'pomala-priloha.txt',
			mimeType: 'text/plain',
			buffer: Buffer.from('Obsah pomalé přílohy')
		});

		await page.getByRole('button', { name: 'Odeslat' }).click();
		await expect(
			page.getByText('Příloha se ještě načítá. Počkejte na dokončení a zkuste to znovu.')
		).toBeVisible();
		expect(sendBodies).toHaveLength(0);

		// Uvolni čtení; příloha se objeví v seznamu a odeslání už projde — i s přílohou.
		await page.evaluate(() => (window as { __releaseFileRead?: () => void }).__releaseFileRead?.());
		await expect(
			page.getByRole('list', { name: 'Přílohy' }).getByText('pomala-priloha.txt')
		).toBeVisible();
		await page.getByRole('button', { name: 'Odeslat' }).click();
		await page.waitForURL('**/mail/1/INBOX');
		expect(sendBodies).toHaveLength(1);
		expect(sendBodies[0]).toMatchObject({
			attachments: [expect.objectContaining({ fileName: 'pomala-priloha.txt' })]
		});
	});

	test('přílohu lze přidat přetažením souboru do compose', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		const dataTransfer = await page.evaluateHandle(() => {
			const transfer = new DataTransfer();
			transfer.items.add(
				new File(['Obsah přetažené přílohy'], 'pretazena-priloha.txt', {
					type: 'text/plain'
				})
			);
			return transfer;
		});

		const dropTarget = page.getByRole('group', { name: 'Přílohy zprávy' });
		await dropTarget.dispatchEvent('dragenter', { dataTransfer });
		await expect(page.getByText('Pusťte soubory pro přidání do zprávy.')).toBeVisible();
		await dropTarget.dispatchEvent('drop', { dataTransfer });

		await expect(page.getByText('pretazena-priloha.txt')).toBeVisible();
	});

	test('přílohu ze schránky lze přidat přes Ctrl+V a odeslat v payloadu', async ({ page }) => {
		const sendBodies: unknown[] = [];
		page.on('request', (request) => {
			if (
				request.method() === 'POST' &&
				request.url().includes('/api/v1/messages/account/1/send')
			) {
				sendBodies.push(request.postDataJSON());
			}
		});

		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-body').focus();
		await page.evaluate(() => {
			const transfer = new DataTransfer();
			transfer.items.add(
				new File(['Obsah vložené přílohy'], 'vlozena-priloha.txt', {
					type: 'text/plain'
				})
			);
			const event = new ClipboardEvent('paste', { bubbles: true, cancelable: true });
			Object.defineProperty(event, 'clipboardData', { value: transfer });
			document.activeElement?.dispatchEvent(event);
		});

		await expect(page.getByText('vlozena-priloha.txt')).toBeVisible();

		await page.locator('#compose-to').fill('clipboard-attachment@example.com');
		await page.locator('#compose-subject').fill('Příloha ze schránky');
		await page.locator('#compose-body').fill('Zpráva s přílohou ze schránky.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		expect(sendBodies).toHaveLength(1);
		expect(sendBodies[0]).toMatchObject({
			attachments: [
				{
					fileName: 'vlozena-priloha.txt',
					contentType: 'text/plain',
					base64Data: Buffer.from('Obsah vložené přílohy').toString('base64')
				}
			]
		});
	});

	test('příliš velká příloha zobrazí lokalizovanou chybu a nepřidá se do payloadu', async ({
		page
	}) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('input[type="file"]').setInputFiles({
			name: 'velka-priloha.bin',
			mimeType: 'application/octet-stream',
			buffer: Buffer.alloc(11 * 1024 * 1024)
		});

		await expect(
			page.getByText('Příloha velka-priloha.bin je větší než limit 10,0 MB.')
		).toBeVisible();
		await expect(page.getByText('velka-priloha.bin')).toHaveCount(1);
		await expect(
			page.getByRole('button', { name: 'Odebrat přílohu velka-priloha.bin' })
		).toHaveCount(0);
	});

	test('odeslání bez předmětu vyžaduje potvrzení', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-body').fill('Zpráva bez předmětu.');

		await page.getByRole('button', { name: 'Odeslat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Zpráva bez předmětu' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Zpráva nemá předmět. Odeslat přesto?');
		await dialog.getByRole('button', { name: 'Odeslat přesto' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('odeslání zobrazí průběžný toast a po send_completed potvrdí úspěch', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('vysledek@example.com');
		await page.locator('#compose-subject').fill('Sledování odeslání');
		await page.locator('#compose-body').fill('Tělo zprávy.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		// Pending indicator survives the navigation (toast region lives in the layout).
		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(notifications.getByText('Odesílá se příjemci vysledek@example.com')).toBeVisible();

		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.pushSendCompleted === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.pushSendCompleted());

		await expect(
			notifications.getByText('Zpráva odeslána příjemci vysledek@example.com')
		).toBeVisible();
		await expect(notifications.getByText('Odesílá se příjemci vysledek@example.com')).toHaveCount(
			0
		);
	});

	test('po send_failed zobrazí chybu odeslání', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('chyba@example.com');
		await page.locator('#compose-subject').fill('Selhání odeslání');
		await page.locator('#compose-body').fill('Tělo zprávy.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(notifications.getByText('Odesílá se příjemci chyba@example.com')).toBeVisible();

		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.pushSendFailed === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.pushSendFailed());

		await expect(
			notifications.getByText('Zprávu se nepodařilo odeslat příjemci chyba@example.com')
		).toBeVisible();
		await expect(notifications.getByText('Odesílá se příjemci chyba@example.com')).toHaveCount(0);
	});

	test('compose formulář nemá axe porušení po validaci a přidání přílohy', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.getByRole('button', { name: 'Odeslat' }).click();
		await page.locator('input[type="file"]').setInputFiles({
			name: 'a11y.txt',
			mimeType: 'text/plain',
			buffer: Buffer.from('A11y příloha')
		});

		const results = await new AxeBuilder({ page })
			.include('form[aria-label="Nová zpráva"]')
			.withTags(['wcag2a', 'wcag2aa'])
			.analyze();
		expect(results.violations).toEqual([]);
	});

	test('Ctrl+Enter odešle novou zprávu z editoru', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('shortcut-send@example.com');
		await page.locator('#compose-subject').fill('E2E odeslání zkratkou');
		await page.locator('#compose-body').fill('Text poslaný přes Ctrl+Enter.');
		await page.keyboard.press('Control+Enter');

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('uloží koncept ručně přes MSW API a vrátí se do inboxu', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await expect(page.getByRole('form', { name: 'Nová zpráva' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		await page.locator('#compose-to').fill('draft-recipient@example.com');
		await page.locator('#compose-subject').fill('E2E koncept');
		await page.locator('#compose-body').fill('Rozepsaný text konceptu.');
		await page.getByRole('button', { name: 'Uložit koncept' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Ctrl+S uloží koncept z editoru', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('shortcut-draft@example.com');
		await page.locator('#compose-subject').fill('E2E koncept zkratkou');
		await page.locator('#compose-body').fill('Koncept uložený přes Ctrl+S.');
		await page.keyboard.press('Control+S');

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Gmail zkratky Ctrl+Shift+C a Ctrl+Shift+B přesunou fokus na kopie', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-body').focus();
		await page.keyboard.press('Control+Shift+C');
		await expect(page.locator('#compose-cc')).toBeFocused();

		await page.locator('#compose-body').focus();
		await page.keyboard.press('Control+Shift+B');
		await expect(page.locator('#compose-bcc')).toBeFocused();
	});

	test('Gmail zkratka Ctrl+Shift+D otevře potvrzení a umí zahodit rozepsanou zprávu', async ({
		page
	}) => {
		await page.goto('/compose');
		await waitForShell(page);

		await expect(page.getByRole('button', { name: 'Zahodit' })).toHaveAttribute(
			'aria-keyshortcuts',
			'Control+Shift+D'
		);
		await page.locator('#compose-body').fill('Text k zahození.');
		await page.keyboard.press('Control+Shift+D');
		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toBeVisible();
		await page.getByRole('button', { name: 'Zahodit změny' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Escape otevře potvrzení pro zahození rozepsané zprávy', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-body').fill('Text k ověření Escape.');
		await page.keyboard.press('Escape');

		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toBeVisible();
		await page.getByRole('button', { name: 'Zůstat' }).click();
		await expect(page.locator('#compose-body')).toHaveValue('Text k ověření Escape.');
	});

	test('navigace pryč z compose nabídne zůstat a zachová rozepsaný text', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-body').fill('Text, který musí zůstat.');
		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();

		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toBeVisible();
		await page.getByRole('button', { name: 'Zůstat' }).click();

		await page.waitForURL('**/compose');
		await expect(page.locator('#compose-body')).toHaveValue('Text, který musí zůstat.');
	});

	test('navigace pryč z compose umí nejdřív uložit koncept a pak pokračovat', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('leave-after-save@example.com');
		await page.locator('#compose-body').fill('Text, který se má uložit před odchodem.');
		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();

		const dialog = page.getByRole('dialog', { name: 'Máte neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Uložit koncept' }).click();

		await page.waitForURL('**/contacts/1');
		await expect(
			page.getByRole('main').getByRole('heading', { level: 1, name: 'Kontakty' })
		).toBeVisible();
	});

	test('tichý autosave zobrazí chybový stav, když uložení konceptu selže', async ({ page }) => {
		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('autosave@example.com');
		await page.locator('#compose-subject').fill('__FAIL_DRAFT__');
		await page.locator('#compose-body').fill('Tento text spustí autosave.');

		await expect(page.getByText('Koncept se nepodařilo uložit.')).toBeVisible({
			timeout: 7000
		});
		await expect(page.getByRole('button', { name: 'Odeslat' })).toBeEnabled();
	});

	test('autosave nové zprávy po prvním uložení používá replaces a nevytváří další nový koncept', async ({
		page
	}) => {
		const draftPosts: string[] = [];
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/api/v1/accounts/1/drafts')) {
				draftPosts.push(request.url());
			}
		});

		await page.goto('/compose');
		await waitForShell(page);

		await page.locator('#compose-to').fill('autosave-replaces@example.com');
		await page.locator('#compose-subject').fill('E2E autosave replaces');
		await page.locator('#compose-body').fill('První verze autosave konceptu.');

		await expect.poll(() => draftPosts.length, { timeout: 8000 }).toBeGreaterThanOrEqual(1);

		await page.locator('#compose-body').fill('Druhá verze autosave konceptu.');

		await expect.poll(() => draftPosts.length, { timeout: 8000 }).toBeGreaterThanOrEqual(2);

		expect(new URL(draftPosts[0]).searchParams.has('replaces')).toBe(false);
		expect(new URL(draftPosts[1]).searchParams.get('replaces')).toMatch(/^draft-/);
	});
});
