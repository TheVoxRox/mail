import { expect, test, type Page } from '@playwright/test';
import { openApp, setPrefs, waitForFocus } from '../e2e-helpers';

/*
 * What reaches the paper. The app is a fixed-viewport layout, so before these
 * rules existed a print of a message was one screenful of the whole window —
 * rail, sidebar, folder list, toolbar, and whichever paragraph of the mail
 * happened to be on screen. Print media is emulated rather than a PDF compared,
 * except where only the sheet count can answer the question.
 */
test.describe('Tisk zprávy', () => {
	const PAGES = /\/Type\s*\/Page[^s]/g;

	const countPrints = async (page: Page) =>
		page.evaluate(() => {
			(window as unknown as { __printed: number }).__printed = 0;
			window.print = () => {
				(window as unknown as { __printed: number }).__printed += 1;
			};
		});
	const printed = async (page: Page) =>
		page.evaluate(() => (window as unknown as { __printed: number }).__printed);

	test('Ctrl+P vytiskne otevřenou zprávu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX/msg-01');
		// The body, not just the pane: until it arrives the key refuses to print.
		await expect(page.locator('[data-print="document"] iframe')).toBeVisible();
		await countPrints(page);

		await page.keyboard.press('Control+p');

		await expect.poll(async () => printed(page)).toBe(1);
	});

	/*
	 * Until the body arrives the pane shows a placeholder, and after a failed
	 * load an error; either used to go onto paper as if it were the mail. A
	 * message that no longer exists is the state an e2e test can hold still —
	 * "still loading" is a race here, so the unit suite covers it.
	 */
	test('Ctrl+P na zprávě, kterou se nepodařilo načíst, nic nevytiskne a řekne proč', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX/no-such-message');
		await expect(page.getByText('This message is no longer available')).toBeVisible();
		await countPrints(page);

		await page.keyboard.press('Control+p');

		// The announcement proves the handler ran, so the zero is "refused", not "not yet".
		await expect(page.locator('#live-region')).toContainText(
			'The message could not be loaded, so there is nothing to print.'
		);
		expect(await printed(page)).toBe(0);
	});

	test('Ctrl+P bez otevřené i zaškrtnuté zprávy nic nevytiskne a řekne proč', async ({ page }) => {
		// Printing is an action on a message. Printing the screen put the folder
		// list on paper, and a silent no-op would leave a screen-reader user
		// unsure the key landed.
		await openApp(page, '/mail/1/INBOX');
		await countPrints(page);

		await page.keyboard.press('Control+p');

		// The announcement is what proves the handler ran, so the zero below is
		// "refused", not "not yet".
		await expect(page.locator('#live-region')).toContainText(
			'No message is open or selected to print.'
		);
		expect(await printed(page)).toBe(0);
	});

	/*
	 * Dialogs, menus and toasts are fixed, and a fixed element prints on every
	 * sheet. The palette's "Print message" prints with the palette still open —
	 * it closes once the command returns — so without the chrome marking the
	 * palette went onto paper over the mail.
	 */
	test('paleta ani upozornění se na papír nedostanou', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.keyboard.press('Control+p');
		const toast = page
			.getByRole('region', { name: 'Notifications' })
			.getByText('No message is open or selected to print.');
		await expect(toast).toBeVisible();
		await page.keyboard.press('Control+k');
		const palette = page.getByRole('dialog');
		await expect(palette).toBeVisible();

		await page.emulateMedia({ media: 'print' });
		await expect(palette).toBeHidden();
		await expect(toast).toBeHidden();
		await page.emulateMedia({ media: 'screen' });
	});

	/*
	 * The other half of printing from the palette: when the dialog closes the
	 * palette is gone and focus is back where it was opened. Held as a guard, not
	 * as a fix — the dialog's own close already returns focus there.
	 */
	test('Vytisknout zprávu z palety vrátí fokus tam, odkud se paleta otevřela', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX/msg-01');
		const message = page.locator('[data-print="document"]');
		await expect(message).toBeVisible();
		await countPrints(page);
		// Opening a message parks focus in the body frame a frame later; a key
		// sent before that lands can end up in a frame not yet listening.
		await waitForFocus(message.locator('iframe'));
		await message.focus();
		await waitForFocus(message);

		await page.keyboard.press('Control+k');
		const input = page.locator('#command-palette-input');
		await expect(input).toBeFocused();
		await input.fill('Print message');
		await input.press('Enter');

		await expect.poll(async () => printed(page)).toBe(1);
		await expect(page.getByRole('dialog')).toHaveCount(0);
		await expect(message).toBeFocused();
	});

	test('tisk skryje chrome aplikace a nechá jen zprávu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX/msg-01');
		await expect(page.locator('[data-print="document"]')).toBeVisible();

		const chrome = page.locator('[data-print="chrome"]');
		expect(await chrome.count()).toBeGreaterThan(0);
		await expect(chrome.first()).toBeVisible();

		await page.emulateMedia({ media: 'print' });

		for (let i = 0; i < (await chrome.count()); i += 1) {
			await expect(chrome.nth(i)).toBeHidden();
		}
		await expect(page.locator('[data-print="document"]')).toBeVisible();
	});

	test('tisk uvolní ořez celé cesty ke zprávě, jinak by se vešla jedna obrazovka', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX/msg-01');
		await expect(page.locator('[data-print="document"]')).toBeVisible();

		const clipping = async () =>
			page.evaluate(() => {
				const doc = document.querySelector('[data-print="document"]');
				let clipped = 0;
				for (let n = doc?.parentElement ?? null; n; n = n.parentElement) {
					if (getComputedStyle(n).overflow !== 'visible') clipped += 1;
				}
				return clipped;
			});

		expect(await clipping()).toBeGreaterThan(0);
		await page.emulateMedia({ media: 'print' });
		expect(await clipping()).toBe(0);
	});

	test('dlouhé tělo zprávy se vytiskne na víc listů a v panelu po sobě nenechá stopu', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX/msg-01');
		const frame = page.locator('iframe').first();
		await expect(frame).toBeVisible();

		// A body longer than the frame's window onto it, delivered the way a real
		// one is: the pinned forwarder measures the document and posts the height.
		await page.evaluate(() => {
			const el = document.querySelector('iframe') as HTMLIFrameElement;
			const lines = Array.from({ length: 200 }, (_, i) => `<p>Řádek ${i}</p>`).join('');
			const reporter =
				'var _h=0;function _r(){var n=Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);' +
				'if(n===_h)return;_h=n;window.parent.postMessage({__voxroxMailFrameHeight:true,height:n},"*");}' +
				'window.addEventListener("load",function(){_r();if(window.ResizeObserver)new ResizeObserver(_r).observe(document.documentElement);});';
			el.setAttribute(
				'srcdoc',
				`<!doctype html><html><head><script>${reporter}</script></head>` +
					`<body style="font:14px sans-serif">${lines}</body></html>`
			);
		});
		await expect
			.poll(async () => page.evaluate(() => document.querySelector('iframe')?.clientHeight ?? 0))
			.toBeGreaterThan(0);
		await page.waitForTimeout(600);

		const pdf = await page.pdf();
		const sheets = (pdf.toString('latin1').match(PAGES) ?? []).length;
		expect(sheets).toBeGreaterThan(1);

		// The height is borrowed for the print and handed back, so the reading
		// pane is exactly what it was.
		expect(
			await page.evaluate(
				() => (document.querySelector('iframe') as HTMLIFrameElement).style.height
			)
		).toBe('');
	});
});

/*
 * The ticked rows. They have nothing on screen, so MessagePrintSheet renders
 * them off screen and opens the dialog once every body has measured itself.
 * window.print is replaced by a recorder that notes what would have gone on
 * paper at the moment the dialog opened, and closing the dialog is the
 * afterprint event the browser sends for a print and a cancel alike.
 */
test.describe('Tisk vybraných zpráv', () => {
	type PrintRecord = { printing: string | null; ids: string[] };

	const recordPrints = async (page: Page) =>
		page.evaluate(() => {
			const w = window as unknown as { __prints: PrintRecord[] };
			w.__prints = [];
			window.print = () => {
				w.__prints.push({
					printing: document.documentElement.dataset.printing ?? null,
					ids: [...document.querySelectorAll<HTMLElement>('[data-print="selection"] article')].map(
						(article) => article.dataset.stableId ?? ''
					)
				});
			};
		});
	const prints = async (page: Page) =>
		page.evaluate(() => (window as unknown as { __prints: PrintRecord[] }).__prints);
	const closePrintDialog = async (page: Page) =>
		page.evaluate(() => window.dispatchEvent(new Event('afterprint')));
	const sheet = (page: Page) => page.locator('[data-print="selection"]');

	test('tlačítko v hromadném panelu vytiskne zaškrtnuté zprávy v pořadí seznamu, každou na svůj list', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX');
		await recordPrints(page);
		await page.getByRole('checkbox', { name: 'Select message Projektové podklady' }).check();
		await page
			.getByRole('checkbox', { name: 'Select message Testovací zpráva 2', exact: true })
			.check();
		const listOrder = await page
			.locator('[role="row"][data-stable-id]')
			.evaluateAll((rows) =>
				rows
					.filter((row) => row.querySelector('input[type="checkbox"]:checked'))
					.map((row) => row.getAttribute('data-stable-id'))
			);

		await page
			.getByRole('toolbar', { name: 'Bulk actions', exact: true })
			.getByRole('button', { name: 'Print selected' })
			.click();

		await expect.poll(async () => (await prints(page)).length).toBe(1);
		const [printed] = await prints(page);
		expect(printed?.printing).toBe('selection');
		expect(printed?.ids).toEqual(listOrder);
		// Out of reach while it exists: no focus, nothing for a screen reader.
		await expect(sheet(page)).toHaveAttribute('inert', '');
		await expect(sheet(page)).toHaveAttribute('aria-hidden', 'true');

		await page.emulateMedia({ media: 'print' });
		await expect(sheet(page).locator('article').nth(1)).toHaveCSS('break-before', 'page');
		await expect(page.getByRole('toolbar', { name: 'Bulk actions', exact: true })).toBeHidden();
		await page.emulateMedia({ media: 'screen' });

		await closePrintDialog(page);
		await expect(sheet(page)).toHaveCount(0);
		expect(await page.evaluate(() => document.documentElement.dataset.printing ?? null)).toBeNull();
		// The selection stays, as it does after Outlook prints.
		await expect(page.getByText('2 selected messages')).toBeVisible();
	});

	/*
	 * The bar makes itself unavailable while an action runs, and the button that
	 * started it is holding focus. Disabled, it dropped focus to <body> for the
	 * fetch, the dialog and after it, and no navigation happened to bring it
	 * back. aria-disabled keeps it where it is, so the bar refuses presses
	 * itself: the list does not know a print is running.
	 */
	test('tlačítko Vytisknout vybrané drží fokus po celý tisk a panel mezitím nic nespustí', async ({
		page
	}) => {
		await openApp(page, '/mail/1/INBOX');
		await recordPrints(page);
		await page.getByRole('checkbox', { name: 'Select message Projektové podklady' }).check();
		const bar = page.getByRole('toolbar', { name: 'Bulk actions', exact: true });

		await bar.getByRole('button', { name: 'Print selected' }).press('Enter');

		await expect.poll(async () => (await prints(page)).length).toBe(1);
		const busy = bar.getByRole('button', { name: 'Preparing to print…' });
		await expect(busy).toBeFocused();
		await expect(busy).toHaveAttribute('aria-disabled', 'true');
		await expect(busy).toHaveAttribute('aria-busy', 'true');
		const remove = bar.getByRole('button', { name: 'Delete selected' });
		await expect(remove).toHaveAttribute('aria-disabled', 'true');
		await remove.dispatchEvent('click');

		await closePrintDialog(page);
		await expect(bar.getByRole('button', { name: 'Print selected' })).toBeFocused();
		// The refused Delete deleted nothing: the row and the selection are both still there.
		await expect(page.getByText('1 selected message')).toBeVisible();
		await expect(
			page.getByRole('checkbox', { name: 'Select message Projektové podklady' })
		).toBeChecked();
	});

	/*
	 * Only the sheet's afterprint used to release a print job, so a sheet that
	 * never arrived held it for the rest of the session: the bar stuck on
	 * "Preparing to print…" and every later print refused. The sheet's chunk is
	 * loaded on the first print, so going offline just before it fails exactly
	 * that request: the messages still come, from the mock service worker, and
	 * page.route would not see a chunk the worker fetches anyway.
	 */
	test('když se tiskový list nenačte, tisk se uvolní a řekne, že se nic nevytisklo', async ({
		page,
		context
	}) => {
		await openApp(page, '/mail/1/INBOX');
		await recordPrints(page);
		await page.getByRole('checkbox', { name: 'Select message Projektové podklady' }).check();
		const bar = page.getByRole('toolbar', { name: 'Bulk actions', exact: true });

		await context.setOffline(true);
		try {
			await bar.getByRole('button', { name: 'Print selected' }).press('Enter');

			await expect(
				page
					.getByRole('region', { name: 'Notifications' })
					.getByText('Printing could not be prepared, so nothing was printed. Please try again.')
			).toBeVisible();
		} finally {
			await context.setOffline(false);
		}
		await expect(bar.getByRole('button', { name: 'Print selected' })).toBeEnabled();
		expect(await prints(page)).toEqual([]);
	});

	test('Ctrl+P tiskne zaškrtnuté, když fokus není v otevřené zprávě, a otevřenou, když je', async ({
		page
	}) => {
		await setPrefs(page, { readingPane: 'right' });
		await openApp(page, '/mail/1/INBOX/msg-01');
		// The body, not just the pane: until it arrives the open message refuses to print.
		await expect(page.locator('[data-print="document"] iframe')).toBeVisible();
		await recordPrints(page);

		const box = page.getByRole('checkbox', {
			name: 'Select message Testovací zpráva 2',
			exact: true
		});
		await box.check();
		await box.press('Control+p');
		await expect.poll(async () => (await prints(page)).length).toBe(1);
		expect((await prints(page))[0]?.printing).toBe('selection');
		await closePrintDialog(page);
		await expect(sheet(page)).toHaveCount(0);

		await page.locator('[data-print="document"]').press('Control+p');
		await expect.poll(async () => (await prints(page)).length).toBe(2);
		expect((await prints(page))[1]?.printing).toBeNull();
	});

	/*
	 * A menu renders in a portal on <body>, away from the toolbar that opened
	 * it, so focus in the message's Move menu counted as focus outside the
	 * message, and with rows ticked Ctrl+P printed those instead.
	 */
	test('Ctrl+P z menu v nástrojové liště otevřené zprávy tiskne tu zprávu, ne zaškrtnuté', async ({
		page
	}) => {
		await setPrefs(page, { readingPane: 'right' });
		await openApp(page, '/mail/1/INBOX/msg-01');
		await expect(page.locator('[data-print="document"] iframe')).toBeVisible();
		await recordPrints(page);
		await page
			.getByRole('checkbox', { name: 'Select message Testovací zpráva 2', exact: true })
			.check();

		await page
			.getByRole('toolbar', { name: 'Message actions' })
			.getByRole('button', { name: 'Move' })
			.click();
		await page.getByRole('menu', { name: 'Move' }).getByRole('menuitem').first().press('Control+p');

		await expect.poll(async () => (await prints(page)).length).toBe(1);
		expect((await prints(page))[0]?.printing).toBeNull();
	});

	test('zaškrtnutá konverzace se vytiskne celá, od nejstarší zprávy', async ({ page }) => {
		await setPrefs(page, { messageGrouping: 'grouped' });
		await openApp(page, '/mail/1/ARCHIVE');
		await recordPrints(page);

		await page
			.getByRole('checkbox', { name: /^Select conversation/ })
			.first()
			.check();
		await page.getByRole('button', { name: 'Print selected' }).click();

		await expect.poll(async () => (await prints(page)).length).toBe(1);
		expect((await prints(page))[0]?.ids).toEqual(['arch-01', 'arch-02', 'arch-03']);
	});

	test('paleta nabídne tisk výběru se souhrnem z hromadného panelu', async ({ page }) => {
		await openApp(page, '/mail/1/INBOX');
		await page.getByRole('checkbox', { name: 'Select message Projektové podklady' }).check();

		await page.keyboard.press('Control+k');
		const input = page.locator('#command-palette-input');
		await expect(input).toBeFocused();
		await input.fill('print');
		await expect(
			page.getByRole('option', { name: /Print selection: 1 selected message/ })
		).toBeVisible();
	});
});
