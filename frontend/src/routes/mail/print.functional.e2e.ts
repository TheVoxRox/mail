import { expect, test, type Page } from '@playwright/test';
import { openApp } from '../e2e-helpers';

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
		await expect(page.locator('[data-print="document"]')).toBeVisible();
		await countPrints(page);

		await page.keyboard.press('Control+p');

		await expect.poll(async () => printed(page)).toBe(1);
	});

	test('Ctrl+P bez otevřené zprávy nic nevytiskne a řekne proč', async ({ page }) => {
		// Printing is an action on a message. Printing the screen put the folder
		// list on paper, and a silent no-op would leave a screen-reader user
		// unsure the key landed.
		await openApp(page, '/mail/1/INBOX');
		await countPrints(page);

		await page.keyboard.press('Control+p');

		// The announcement is what proves the handler ran, so the zero below is
		// "refused", not "not yet".
		await expect(page.locator('#live-region')).toContainText('No message is open to print.');
		expect(await printed(page)).toBe(0);
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
