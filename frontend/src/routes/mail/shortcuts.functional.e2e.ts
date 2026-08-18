import { expect, test } from '@playwright/test';
import { bodyFrame, openApp, setPrefs, waitForFocus } from '../e2e-helpers';

/*
 * Regression cover for the message-body iframe key bridge (lib/mail/mailFrame.ts).
 * The body renders in a script-sandboxed, opaque-origin iframe; a keydown inside
 * it never reaches the parent's global shortcut handler on its own. A hash-pinned
 * forwarder postMessages real keystrokes out so Delete, Ctrl+R, … keep working
 * even while the reader's focus is inside the message body.
 */

test.beforeEach(async ({ page }) => {
	await setPrefs(page, { locale: 'cs', readingPane: 'right', messageBodyView: 'html' });
});

async function openHtmlMessage(page: import('@playwright/test').Page) {
	await openApp(page, '/mail/1/INBOX');
	await page.locator('[role="row"][data-stable-id="msg-01"]').click();
	await page.waitForURL('**/mail/1/INBOX/msg-01');
	const frame = bodyFrame(page);
	await expect(frame).toBeVisible();
	/*
	 * Visible is not settled: opening a message parks focus in the frame a beat
	 * behind the navigation, and a test that takes focus away inside that gap
	 * has it stolen back — here without failing, because the frame forwards the
	 * keystroke to the parent anyway (mailFrame.ts). Measured three runs of
	 * three: focus <main> straight after waitForURL and it ends up on the frame
	 * regardless, so the "focus outside the body" test below silently became a
	 * copy of the one above it. waitForFocus is where that mechanism is written
	 * down, for every suite that opens a message.
	 */
	await waitForFocus(frame);
	return frame;
}

test('Delete s focusem uvnitř těla zprávy smaže otevřenou zprávu', async ({ page }) => {
	const deleteRequests: string[] = [];
	page.on('request', (r) => {
		if (r.method() === 'DELETE' && /\/api\/v1\/messages\/msg-01$/.test(r.url())) {
			deleteRequests.push(r.url());
		}
	});

	// No focus() call of its own: openHtmlMessage waits for the app to park focus
	// inside the frame, which is precisely the precondition this test is about.
	await openHtmlMessage(page);

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
	const main = page.locator('#main-content');
	await main.focus();
	// Confirm the app really gave focus up before the key goes out, so a future
	// theft fails here rather than passing as the wrong test.
	await waitForFocus(main);

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

	await openApp(page, '/mail/1/INBOX');

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
