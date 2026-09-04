import { expect, test } from '@playwright/test';
import { openApp, setPrefs, wcagScan } from './e2e-helpers';

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right' });
});

test.describe('Compose', () => {
	test('sidebar používá tlačítko Nová zpráva a Ctrl+N otevře compose', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');

		const sidebar = page.getByRole('region', { name: 'Podokno pošty' });
		await expect(sidebar.getByRole('link', { name: /Nová zpráva/ })).toHaveCount(0);

		await sidebar.getByRole('button', { name: 'Nová zpráva Ctrl+N' }).click();
		await page.waitForURL('**/compose');
		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();

		await openApp(page, '/mail/1/INBOX');
		await page.locator('body').dispatchEvent('keydown', {
			key: 'n',
			code: 'KeyN',
			ctrlKey: true,
			bubbles: true,
			cancelable: true
		});
		await page.waitForURL('**/compose');
		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
	});

	test('composer se nepředstaví dvakrát, než se dojde ke Komu', async ({ page }) => {
		/*
		 * Ctrl+N used to say "Nová zpráva" twice before the To field: the <main>
		 * landmark is named after the route title ("Pošta – Nová zpráva") and the
		 * <form> inside it carried aria-label="Nová zpráva", which made it a second
		 * landmark whose whole name was the <h1> immediately inside it. Found by
		 * NVDA listening on 2026-09-03.
		 *
		 * The name is what makes a <form> a landmark, so dropping it removes the
		 * landmark rather than leaving an unnamed one — which is the point: the
		 * composer is the whole of main here, so a region spanning exactly main
		 * adds a navigation target that goes nowhere new. The contact form keeps
		 * its name for a reason that does not apply here: there the landmark
		 * carries an aria-describedby hint (see a11y.e2e.ts).
		 */
		await openApp(page, '/compose');

		await expect(page.getByRole('main', { name: 'Pošta – Nová zpráva' })).toBeVisible();
		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
		// No third one. Asserted over every form landmark rather than the name, so
		// re-introducing it under any wording has to be a deliberate choice.
		await expect(page.getByRole('form')).toHaveCount(0);
	});

	test('nová zpráva fokusuje Komu a reply fokusuje tělo zprávy', async ({ page }) => {
		await openApp(page, '/compose');
		await expect(page.locator('#compose-to')).toBeFocused();

		await openApp(page, '/compose?reply=msg-01&all=0');
		await expect(page.locator('#compose-body')).toBeFocused();
		await expect(page.locator('#compose-subject')).toHaveValue(/Re:/);
	});

	/*
	 * Markdown is rendered by the backend on send, so the composer never shows the
	 * formatting — this hint is the only place the feature is discoverable. It has
	 * to reach a screen reader, hence the accessible-description assertion rather
	 * than a check that the text is merely on screen.
	 */
	test('tělo zprávy má Markdown nápovědu ve své přístupné popisce', async ({ page }) => {
		await openApp(page, '/compose');

		const body = page.locator('#compose-body');
		await expect(body).toHaveAccessibleDescription(/Markdown/);
		await expect(page.locator('#compose-body-hint')).toHaveText(
			'Podporuje Markdown: **tučně**, # nadpis, - odrážka.'
		);
	});

	test('forward prefill doplní Fwd předmět a citované tělo', async ({ page }) => {
		await openApp(page, '/compose?forward=msg-01');

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

		await openApp(page, '/compose?reply=msg-01&all=0');

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

		await openApp(page, '/compose?draft=draft-42');

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
		await openApp(page, '/compose');

		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-subject').fill('E2E odeslání');
		await page.locator('#compose-body').fill('Tělo testovací zprávy.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('zmínka přílohy bez přílohy otevře potvrzení: Zrušit neodešle, potvrzení odešle', async ({
		page
	}) => {
		let sendRequests = 0;
		page.on('request', (request) => {
			if (request.method() === 'POST' && request.url().includes('/send')) sendRequests += 1;
		});

		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-subject').fill('Faktura');
		await page.locator('#compose-body').fill('Posílám fakturu v příloze.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		const dialog = page.getByRole('dialog', { name: 'Odeslat bez přílohy?' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zrušit' }).click();
		await expect(dialog).toHaveCount(0);

		// Cancel keeps the composer intact and nothing was sent.
		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
		await expect(page.locator('#compose-body')).toHaveValue('Posílám fakturu v příloze.');
		expect(sendRequests).toBe(0);

		await page.getByRole('button', { name: 'Odeslat' }).click();
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Odeslat bez přílohy' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		expect(sendRequests).toBe(1);
	});

	test('zpráva s připojenou přílohou se odešle bez potvrzovacího dialogu', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-subject').fill('Faktura');
		await page.locator('#compose-body').fill('Posílám fakturu v příloze.');
		await page.locator('input[type="file"]').setInputFiles({
			name: 'faktura.txt',
			mimeType: 'text/plain',
			buffer: Buffer.from('Obsah faktury')
		});
		await expect(page.getByText('faktura.txt')).toBeVisible();

		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('dialog', { name: 'Odeslat bez přílohy?' })).toHaveCount(0);
	});

	test('odeslání bez příjemce zobrazí chybu u pole Komu a zůstane na compose', async ({ page }) => {
		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('jana');
		const suggestions = page.getByRole('listbox', { name: 'Návrhy adres' });
		await expect(suggestions).toBeVisible();
		await suggestions.getByRole('option', { name: /jana@example\.com/ }).click();

		await expect(page.getByText('jana@example.com')).toBeVisible();
		await expect(page.locator('#compose-to')).toHaveValue('');
	});

	test('našeptávač nabídne adresu z historie a řekne, že není v kontaktech', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('jan');
		const suggestions = page.getByRole('listbox', { name: 'Návrhy adres' });
		await expect(suggestions).toBeVisible();

		// jan.dvorak je jen v historii — v adresáři žádný takový kontakt není.
		// Označení musí být součástí přístupného jména položky, ne vizuál vedle
		// ní: v listboxu odečítač čte jméno option, takže odznak mimo tlačítko
		// (nebo skrytý před stromem přístupnosti) by uživatel nikdy neslyšel.
		const historyOption = suggestions.getByRole('option', {
			name: /jan\.dvorak@example\.com.*není v kontaktech/
		});
		await expect(historyOption).toBeVisible();

		// jana@example.com má kontakt i záznam v historii — server je slučuje,
		// takže se smí objevit právě jednou a bez označení.
		await expect(suggestions.getByRole('option', { name: /jana@example\.com/ })).toHaveCount(1);
		await expect(
			suggestions.getByRole('option', { name: /jana@example\.com.*není v kontaktech/ })
		).toHaveCount(0);

		// no-reply@example.com je v historii taky, ale robotí adresy se nenabízejí.
		await expect(suggestions.getByRole('option', { name: /no-reply/ })).toHaveCount(0);

		await historyOption.click();
		await expect(page.getByText('jan.dvorak@example.com')).toBeVisible();
	});

	test('Escape v poli adresátů zavře jen našeptávač, další Escape teprve zahazuje', async ({
		page
	}) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('jana');
		const suggestions = page.getByRole('listbox', { name: 'Návrhy adres' });
		await expect(suggestions).toBeVisible();

		await page.keyboard.press('Escape');
		await expect(suggestions).toHaveCount(0);
		await expect(page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' })).toHaveCount(0);

		await page.keyboard.press('Escape');
		await expect(page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' })).toBeVisible();
	});

	test('přílohu lze přidat a odebrat přístupným tlačítkem', async ({ page }) => {
		await openApp(page, '/compose');

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

		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

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

		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

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

	test('odeslání bez předmětu zobrazí chybu u pole Předmět a zůstane na compose', async ({
		page
	}) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('recipient@example.com');
		await page.locator('#compose-body').fill('Zpráva bez předmětu.');

		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/compose');
		await expect(page.locator('#compose-subject')).toBeFocused();
		await expect(page.locator('#compose-subject')).toHaveAttribute('aria-invalid', 'true');
		await expect(page.locator('#compose-subject')).toHaveAttribute(
			'aria-describedby',
			'compose-subject-error'
		);
		await expect(page.locator('#compose-subject-error')).toContainText('Vyplňte předmět zprávy.');

		// Po doplnění předmětu chyba zmizí a odeslání projde.
		await page.locator('#compose-subject').fill('Doplněný předmět');
		await expect(page.locator('#compose-subject-error')).toHaveCount(0);
		await page.getByRole('button', { name: 'Odeslat' }).click();
		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('odeslání zobrazí průběžný toast a po send_completed potvrdí úspěch', async ({ page }) => {
		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

		await page.getByRole('button', { name: 'Odeslat' }).click();
		await page.locator('input[type="file"]').setInputFiles({
			name: 'a11y.txt',
			mimeType: 'text/plain',
			buffer: Buffer.from('A11y příloha')
		});

		// Scoped to the form element, not to a name: the composer's form is
		// deliberately unnamed, so it is no longer addressable by aria-label.
		const results = await wcagScan(page).include('#main-content form').analyze();
		expect(results.violations).toEqual([]);
	});

	test('Ctrl+Enter odešle novou zprávu z editoru', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('shortcut-send@example.com');
		await page.locator('#compose-subject').fill('E2E odeslání zkratkou');
		await page.locator('#compose-body').fill('Text poslaný přes Ctrl+Enter.');
		await page.keyboard.press('Control+Enter');

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('uloží koncept ručně přes MSW API a vrátí se do inboxu', async ({ page }) => {
		await openApp(page, '/compose');

		await expect(page.getByRole('heading', { level: 1, name: 'Nová zpráva' })).toBeVisible();
		await expect(page.getByRole('region', { name: 'Podokno pošty' })).toBeVisible();

		await page.locator('#compose-to').fill('draft-recipient@example.com');
		await page.locator('#compose-subject').fill('E2E koncept');
		await page.locator('#compose-body').fill('Rozepsaný text konceptu.');
		await page.getByRole('button', { name: 'Uložit koncept' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Ctrl+S uloží koncept z editoru', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('shortcut-draft@example.com');
		await page.locator('#compose-subject').fill('E2E koncept zkratkou');
		await page.locator('#compose-body').fill('Koncept uložený přes Ctrl+S.');
		await page.keyboard.press('Control+S');

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Gmail zkratky Ctrl+Shift+C a Ctrl+Shift+B přesunou fokus na kopie', async ({ page }) => {
		await openApp(page, '/compose');

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
		await openApp(page, '/compose');

		await expect(page.getByRole('button', { name: 'Zahodit' })).toHaveAttribute(
			'aria-keyshortcuts',
			'Control+Shift+D'
		);
		await page.locator('#compose-body').fill('Text k zahození.');
		await page.keyboard.press('Control+Shift+D');
		const dialog = page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Zahodit' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect(page.getByRole('heading', { name: 'Doručené' })).toBeVisible();
	});

	test('Escape otevře potvrzení pro zahození rozepsané zprávy', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-body').fill('Text k ověření Escape.');
		await page.keyboard.press('Escape');

		await expect(page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' })).toBeVisible();
		await page.getByRole('button', { name: 'Zůstat' }).click();
		await expect(page.locator('#compose-body')).toHaveValue('Text k ověření Escape.');
	});

	test('navigace pryč z compose nabídne zůstat a zachová rozepsaný text', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-body').fill('Text, který musí zůstat.');
		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();

		await expect(page.getByRole('dialog', { name: 'Máte neuložené změny' })).toBeVisible();
		await page.getByRole('button', { name: 'Zůstat' }).click();

		await page.waitForURL('**/compose');
		await expect(page.locator('#compose-body')).toHaveValue('Text, který musí zůstat.');
	});

	test('navigace pryč z compose umí nejdřív uložit koncept a pak pokračovat', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('leave-after-save@example.com');
		await page.locator('#compose-body').fill('Text, který se má uložit před odchodem.');
		await page.getByRole('link', { name: 'Kontakty (Ctrl+2)' }).click();

		const dialog = page.getByRole('dialog', { name: 'Máte neuložené změny' });
		await expect(dialog).toBeVisible();
		await dialog.getByRole('button', { name: 'Uložit koncept' }).click();

		await page.waitForURL('**/contacts');
		await expect(
			page.getByRole('main').getByRole('heading', { level: 1, name: 'Kontakty' })
		).toBeVisible();
	});

	test('tichý autosave zobrazí chybový stav, když uložení konceptu selže', async ({ page }) => {
		await openApp(page, '/compose');

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

		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('autosave-replaces@example.com');
		await page.locator('#compose-subject').fill('E2E autosave replaces');
		await page.locator('#compose-body').fill('První verze autosave konceptu.');

		await expect.poll(() => draftPosts.length, { timeout: 8000 }).toBeGreaterThanOrEqual(1);

		await page.locator('#compose-body').fill('Druhá verze autosave konceptu.');

		await expect.poll(() => draftPosts.length, { timeout: 8000 }).toBeGreaterThanOrEqual(2);

		expect(new URL(draftPosts[0]).searchParams.has('replaces')).toBe(false);
		expect(new URL(draftPosts[1]).searchParams.get('replaces')).toMatch(/^draft-/);
	});

	test('Zahodit smaže koncept, který se mezitím autosavnul', async ({ page }) => {
		let savedStableId: string | null = null;
		const deletedIds: string[] = [];
		page.on('response', async (response) => {
			const request = response.request();
			if (
				request.method() === 'POST' &&
				/\/api\/v1\/accounts\/1\/drafts(\?|$)/.test(response.url())
			) {
				const body = (await response.json().catch(() => null)) as { stableId?: string } | null;
				if (body?.stableId) savedStableId = body.stableId;
			}
		});
		page.on('request', (request) => {
			const match = request.url().match(/\/api\/v1\/messages\/([^/?]+)$/);
			if (request.method() === 'DELETE' && match) deletedIds.push(decodeURIComponent(match[1]));
		});

		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('smazat@example.com');
		await page.locator('#compose-subject').fill('Koncept k zahození');
		await page.locator('#compose-body').fill('Tento koncept se autosavne a pak zahodí.');

		await expect.poll(() => savedStableId, { timeout: 8000 }).not.toBeNull();
		const draftId = savedStableId!;

		await page.getByRole('button', { name: 'Zahodit', exact: true }).click();
		const dialog = page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Koncept bude smazán.');
		await dialog.getByRole('button', { name: 'Zahodit' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		// The delete runs in the session module, which outlives the composer.
		await expect.poll(() => deletedIds, { timeout: 8000 }).toContain(draftId);
	});

	test('Zahodit smaže otevřený koncept a potvrzení to říká', async ({ page }) => {
		const deletedIds: string[] = [];
		page.on('request', (request) => {
			const match = request.url().match(/\/api\/v1\/messages\/([^/?]+)$/);
			if (request.method() === 'DELETE' && match) deletedIds.push(decodeURIComponent(match[1]));
		});

		await openApp(page, '/compose?draft=draft-42');
		await expect(page.locator('#compose-subject')).toHaveValue('Rozepsaný koncept');

		await page.getByRole('button', { name: 'Zahodit', exact: true }).click();
		const dialog = page.getByRole('dialog', { name: 'Zahodit rozepsanou zprávu?' });
		await expect(dialog).toBeVisible();
		await expect(dialog).toContainText('Koncept bude smazán.');
		await dialog.getByRole('button', { name: 'Zahodit' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		await expect.poll(() => deletedIds, { timeout: 8000 }).toContain('draft-42');
	});

	test('po send_failed s obnoveným konceptem toast odkáže na Rozepsané', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('recovery@example.com');
		await page.locator('#compose-subject').fill('Selhání s obnovou');
		await page.locator('#compose-body').fill('Obsah, který se má dát obnovit.');
		await page.getByRole('button', { name: 'Odeslat' }).click();

		await page.waitForURL('**/mail/1/INBOX');
		const notifications = page.getByRole('region', { name: 'Oznámení' });
		await expect(notifications.getByText('Odesílá se příjemci recovery@example.com')).toBeVisible();

		await page.waitForFunction(() => typeof window.__MAIL_MSW__?.pushSendFailed === 'function');
		await page.evaluate(() => window.__MAIL_MSW__?.pushSendFailed());

		await expect(
			notifications.getByText(
				'Zprávu se nepodařilo odeslat příjemci recovery@example.com. Obsah je uložen ve složce Rozepsané.'
			)
		).toBeVisible();
	});

	test('Bcc přežije uložení konceptu a jeho znovuotevření', async ({ page }) => {
		await openApp(page, '/compose');

		await page.locator('#compose-to').fill('viditelny@example.com');
		await page.locator('#compose-bcc').fill('skryta-kopie@example.com');
		await page.locator('#compose-subject').fill('Koncept se skrytou kopií');
		await page.locator('#compose-body').fill('Tělo konceptu s Bcc.');
		await page.getByRole('button', { name: 'Uložit koncept' }).click();
		await page.waitForURL('**/mail/1/INBOX');

		// Reopen the runtime-created draft via in-app (SPA) navigation — a full
		// page.goto would reload the MSW worker and reset its fixtures, wiping it.
		await page.getByRole('link', { name: 'Rozepsané' }).click();
		await page.waitForURL('**/mail/1/DRAFTS');
		// A click on the draft opens it in the composer (the web-mail model).
		await page.getByText('Koncept se skrytou kopií').click();
		await page.waitForURL(/\/compose\?draft=/);

		// Reopened recipients render as token chips (the input itself is empty).
		await expect(page.getByText('skryta-kopie@example.com')).toBeVisible();
	});
});
