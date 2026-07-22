import { expect, test } from '@playwright/test';
import { waitForShell } from '../e2e-helpers';

/*
 * Regression cover for the message-body iframe key bridge (lib/mail/mailFrame.ts).
 * The body renders in a script-sandboxed, opaque-origin iframe; a keydown inside
 * it never reaches the parent's global shortcut handler on its own. A hash-pinned
 * forwarder postMessages real keystrokes out so Delete, Ctrl+R, … keep working
 * even while the reader's focus is inside the message body.
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
				// The probe is a sandboxed, allow-scripts frame → opaque origin.
				if (event.origin !== 'null') return;
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

/*
 * Counterpart to the open-message Delete tests above: on a focused list row a
 * MODIFIED Delete (Shift/Ctrl+Delete) must be a no-op, matching the plain-Delete
 * guard in the open-message handler (globalShortcuts.ts). Uses two rows so the
 * assertion is deterministic without a timeout — a plain Delete on a second row
 * both proves the handler is wired and flushes the queued modified keystrokes.
 */
test('Shift/Ctrl+Delete na fokusovaném řádku seznamu zprávu nesmaže', async ({ page }) => {
	const deletedIds: string[] = [];
	page.on('request', (r) => {
		const match = /\/api\/v1\/messages\/(msg-\d+)$/.exec(r.url());
		if (r.method() === 'DELETE' && match) deletedIds.push(match[1]);
	});

	await page.goto('/mail/1/INBOX');
	await waitForShell(page);

	// Focus the row directly (a click would open the message and hand Delete to
	// the open-message handler instead of the grid row handler under test).
	const guarded = page.locator('[role="row"][data-stable-id="msg-02"]');
	await expect(guarded).toBeVisible();
	await guarded.focus();
	await page.keyboard.press('Shift+Delete');
	await page.keyboard.press('Control+Delete');

	// Plain Delete on another row deletes it — and completing that round-trip is a
	// deterministic signal that the earlier modified keystrokes were processed.
	const control = page.locator('[role="row"][data-stable-id="msg-01"]');
	await control.focus();
	await page.keyboard.press('Delete');
	await expect(control).toHaveCount(0);

	await expect(guarded).toBeVisible();
	expect(deletedIds).toEqual(['msg-01']);
});
