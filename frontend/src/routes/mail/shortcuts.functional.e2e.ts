import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Regression cover for the message-body iframe key bridge (lib/mail/mailFrame.ts).
 * The body renders in a script-sandboxed, opaque-origin iframe; a keydown inside
 * it never reaches the parent's global shortcut handler on its own. A hash-pinned
 * forwarder postMessages real keystrokes out so `?`, Delete, … keep working even
 * while the reader's focus is inside the message body.
 */

test.beforeEach(async ({ page }) => {
	await page.addInitScript(() => {
		window.localStorage.setItem('mail.locale', 'cs');
		window.localStorage.setItem('mail.readingPane', 'right');
		window.localStorage.setItem('mail.messageBodyView', 'html');
	});
});

async function openHtmlMessage(page: import('@playwright/test').Page) {
	await page.goto('/mail/1/INBOX');
	await waitForShell(page);
	await page.locator('[role="row"][data-stable-id="msg-01"]').click();
	await page.waitForURL('**/mail/1/INBOX/msg-01');
	const frame = page.locator('iframe');
	await expect(frame).toBeVisible();
	return frame;
}

test('Delete s focusem uvnitř těla zprávy smaže otevřenou zprávu', async ({ page }) => {
	const deleteRequests: string[] = [];
	page.on('request', (r) => {
		if (r.method() === 'DELETE' && /\/api\/v1\/messages\/msg-01$/.test(r.url())) {
			deleteRequests.push(r.url());
		}
	});

	const frame = await openHtmlMessage(page);
	await frame.focus();
	expect(
		await page.evaluate(() => document.activeElement?.tagName.toLowerCase() === 'iframe')
	).toBe(true);

	await page.keyboard.press('Delete');

	await page.waitForURL('**/mail/1/INBOX', { timeout: 5000 });
	expect(deleteRequests).toHaveLength(1);
});

test('? s focusem uvnitř těla zprávy otevře přehled klávesových zkratek', async ({ page }) => {
	const frame = await openHtmlMessage(page);
	await frame.focus();

	await page.keyboard.press('Shift+/');

	await page.waitForURL('**/settings/shortcuts', { timeout: 5000 });
	await expect(page.locator('main h1')).toHaveText('Klávesové zkratky');
});

test('tělo zprávy: CSP zablokuje jiný než hash-připnutý skript', async ({ page }) => {
	await openHtmlMessage(page);

	// Take the exact srcdoc the app ships and graft an extra, NON-hash-pinned
	// inline script onto it. If the frame CSP is enforced, only the pinned
	// forwarder may run and the grafted script is blocked — so no message
	// arrives. This guards the "scripts blocked by hash mismatch" security claim.
	const evilRan = await page.evaluate(async () => {
		const real = document.querySelector('iframe')?.getAttribute('srcdoc') ?? '';
		const augmented = real.replace(
			'</head>',
			'<script>window.parent.postMessage("__EVIL__", "*")</scr' + 'ipt></head>'
		);
		return await new Promise<boolean>((resolve) => {
			let evil = false;
			function onMessage(event: MessageEvent) {
				if (event.data === '__EVIL__') evil = true;
			}
			window.addEventListener('message', onMessage);
			const probe = document.createElement('iframe');
			probe.setAttribute('sandbox', 'allow-scripts');
			probe.srcdoc = augmented;
			probe.style.position = 'fixed';
			probe.style.left = '-9999px';
			probe.addEventListener('load', () => {
				setTimeout(() => {
					window.removeEventListener('message', onMessage);
					probe.remove();
					resolve(evil);
				}, 300);
			});
			document.body.appendChild(probe);
		});
	});

	expect(evilRan).toBe(false);
});

test('Delete s focusem na aplikaci (mimo tělo) zprávu stále smaže', async ({ page }) => {
	const deleteRequests: string[] = [];
	page.on('request', (r) => {
		if (r.method() === 'DELETE' && /\/api\/v1\/messages\/msg-01$/.test(r.url())) {
			deleteRequests.push(r.url());
		}
	});

	await openHtmlMessage(page);
	await page.locator('#main-content').focus();

	await page.keyboard.press('Delete');

	await page.waitForURL('**/mail/1/INBOX', { timeout: 5000 });
	expect(deleteRequests).toHaveLength(1);
});
