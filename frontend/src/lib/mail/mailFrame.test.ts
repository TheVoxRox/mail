// @vitest-environment jsdom
import { createHash } from 'node:crypto';
import { beforeAll, describe, expect, it } from 'vitest';
import {
	MAIL_FRAME_SCRIPT,
	MAIL_FRAME_SCRIPT_SHA256,
	MAIL_FRAME_STYLE,
	MAIL_FRAME_STYLE_SHA256,
	buildMailFrameSrcdoc,
	countRemoteImages,
	isMailFrameKeyMessage,
	isMailFrameLinkMessage,
	isOpenableMailLink,
	mailFrameCsp,
	mailFrameKeyToEvent,
	type MailFrameKeyMessage,
	type MailFrameLinkMessage
} from './mailFrame.js';

// The frame renders with remote images blocked until the user opts in, so the
// default flag is what the pinned hashes below have to hold for.
const defaultCsp = mailFrameCsp(false);

describe('MAIL_FRAME_SCRIPT_SHA256', () => {
	it('matches the actual SHA-256 of the forwarder script', () => {
		// If this fails, the CSP hash no longer pins the script and the body
		// frame would silently block ALL scripts — including the forwarder —
		// so every shortcut would break. Update the constant to the value below.
		const actual = createHash('sha256').update(MAIL_FRAME_SCRIPT, 'utf8').digest('base64');
		expect(actual).toBe(MAIL_FRAME_SCRIPT_SHA256);
	});

	it('is referenced by the frame CSP script-src', () => {
		expect(defaultCsp).toContain(`script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'`);
		expect(defaultCsp).toContain("default-src 'none'");
		expect(defaultCsp).toContain('img-src data:');
	});
});

describe('MAIL_FRAME_STYLE_SHA256', () => {
	it('matches the actual SHA-256 of the base stylesheet', () => {
		// If this fails, the CSP hash no longer pins the base style and the frame
		// would block it — mail bodies would render unstyled (default black text,
		// no light background). Update the constant to the value below.
		const actual = createHash('sha256').update(MAIL_FRAME_STYLE, 'utf8').digest('base64');
		expect(actual).toBe(MAIL_FRAME_STYLE_SHA256);
	});

	it('is referenced by the frame CSP style-src', () => {
		expect(defaultCsp).toContain(`style-src 'sha256-${MAIL_FRAME_STYLE_SHA256}'`);
	});

	it('keeps the mail surface light regardless of app theme', () => {
		expect(MAIL_FRAME_STYLE).toContain('color-scheme:light');
		expect(MAIL_FRAME_STYLE).toContain('background:#ffffff');
	});
});

describe('buildMailFrameSrcdoc', () => {
	it('embeds the meta CSP, the hash-pinned forwarder and the base style', () => {
		const doc = buildMailFrameSrcdoc('<p>hello</p>');
		expect(doc).toContain('<meta http-equiv="Content-Security-Policy"');
		expect(doc).toContain(`script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'`);
		expect(doc).toContain(`<script>${MAIL_FRAME_SCRIPT}</script>`);
		expect(doc).toContain(`<style>${MAIL_FRAME_STYLE}</style>`);
		expect(doc).toContain('<p>hello</p>');
	});

	it('still sanitizes hostile mail HTML before embedding it', () => {
		const doc = buildMailFrameSrcdoc(
			'<p onclick="steal()">hi</p><script>window.__xss=1</script><img src="https://t.test/p.png">'
		);
		// The only <script> in the document is the trusted forwarder. Counted by
		// string split (not a tag-matching regex) — the srcdoc is built by us and
		// the sanitizer removes script elements wholesale, so casing never varies.
		expect(doc.split('<script>').length - 1).toBe(1);
		expect(doc).not.toContain('window.__xss');
		expect(doc).not.toContain('onclick');
		expect(doc).not.toContain('https://t.test');
		expect(doc).toContain('hi');
	});

	it('carries the no-referrer meta so a loaded image leaks no referrer', () => {
		expect(buildMailFrameSrcdoc('<p>hi</p>')).toContain(
			'<meta name="referrer" content="no-referrer">'
		);
	});
});

describe('remote-image opt-in', () => {
	const remote =
		'<div><p>hi</p><img data-voxrox-remote-src="https://cdn.test/logo.png" alt="l"></div>';

	it('CSP relaxes img-src to https only when opted in', () => {
		expect(mailFrameCsp(false)).toContain('img-src data:;');
		expect(mailFrameCsp(false)).not.toContain('https:');
		expect(mailFrameCsp(true)).toContain('img-src data: https:');
		// The rest of the policy is unchanged in both modes.
		expect(mailFrameCsp(true)).toContain(`script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'`);
		expect(mailFrameCsp(true)).toContain("default-src 'none'");
	});

	it('keeps remote images inert by default (no live src, attr preserved)', () => {
		const doc = buildMailFrameSrcdoc(remote);
		expect(doc).toContain('data-voxrox-remote-src="https://cdn.test/logo.png"');
		// A live src is space-separated from the tag; the data-*-remote-src attr is not.
		expect(doc).not.toContain(' src="https://cdn.test/logo.png"');
		expect(doc).toContain('img-src data:;');
	});

	it('promotes remote images to a real src and relaxes CSP when opted in', () => {
		const doc = buildMailFrameSrcdoc(remote, { loadRemoteImages: true });
		expect(doc).toContain('src="https://cdn.test/logo.png"');
		expect(doc).not.toContain('data-voxrox-remote-src');
		expect(doc).toContain('img-src data: https:');
	});

	it('countRemoteImages counts preserved remote images on the sanitized body', () => {
		expect(countRemoteImages(remote)).toBe(1);
		expect(countRemoteImages('<p>no images</p>')).toBe(0);
		// A live http/https src is not a preserved remote image (sanitizer drops it).
		expect(countRemoteImages('<img src="https://cdn.test/x.png">')).toBe(0);
	});
});

describe('isMailFrameKeyMessage', () => {
	const valid: MailFrameKeyMessage = {
		__voxroxMailFrameKey: true,
		key: 'Delete',
		code: 'Delete',
		ctrlKey: false,
		metaKey: false,
		altKey: false,
		shiftKey: false
	};

	it('accepts a well-formed forwarder message', () => {
		expect(isMailFrameKeyMessage(valid)).toBe(true);
	});

	it.each([
		['null', null],
		['a string', 'Delete'],
		['missing marker', { key: 'Delete', code: 'Delete' }],
		['wrong marker', { ...valid, __voxroxMailFrameKey: false }],
		['non-boolean modifier', { ...valid, ctrlKey: 'yes' }],
		['missing key', { ...valid, key: undefined }]
	])('rejects %s', (_label, data) => {
		expect(isMailFrameKeyMessage(data)).toBe(false);
	});
});

describe('MAIL_FRAME_SCRIPT link forwarding', () => {
	it('relays body-link clicks and blocks their default navigation', () => {
		// The sandbox has no allow-popups, so a target="_blank" link is dead
		// unless the forwarder preventDefaults the click and posts the href out.
		expect(MAIL_FRAME_SCRIPT).toContain('addEventListener("click"');
		expect(MAIL_FRAME_SCRIPT).toContain('preventDefault');
		expect(MAIL_FRAME_SCRIPT).toContain('__voxroxMailFrameLink');
		// Only genuine user clicks are relayed (a synthetic click can never loop).
		expect(MAIL_FRAME_SCRIPT).toContain('e.isTrusted');
	});
});

describe('MAIL_FRAME_SCRIPT keyboard forwarding', () => {
	/*
	 * The forwarder is executed here rather than string-matched: what matters is
	 * which keystrokes it cancels, and a containment check would pass just as
	 * happily on an inverted condition or a wrong key list.
	 *
	 * Its keydown listener is captured instead of dispatching real events,
	 * because the forwarder ignores anything with `isTrusted === false` and
	 * jsdom makes `isTrusted` a non-configurable own property — a dispatched
	 * synthetic event can never look genuine, so every case would vacuously
	 * "pass" by being skipped.
	 */
	type FrameKeyEvent = {
		isTrusted: boolean;
		key: string;
		code: string;
		ctrlKey: boolean;
		metaKey: boolean;
		altKey: boolean;
		shiftKey: boolean;
		preventDefault: () => void;
	};
	let onKeydown: (event: FrameKeyEvent) => void;

	beforeAll(() => {
		const original = window.addEventListener;
		window.addEventListener = ((type: string, handler: unknown) => {
			if (type === 'keydown') onKeydown = handler as (event: FrameKeyEvent) => void;
		}) as typeof window.addEventListener;
		new Function(MAIL_FRAME_SCRIPT)();
		window.addEventListener = original;
	});

	function press(init: Partial<FrameKeyEvent>): boolean {
		let prevented = false;
		onKeydown({
			isTrusted: true,
			key: '',
			code: '',
			ctrlKey: false,
			metaKey: false,
			altKey: false,
			shiftKey: false,
			...init,
			preventDefault: () => {
				prevented = true;
			}
		});
		return prevented;
	}

	// The reported case: with focus in the body, this both flagged the message
	// and opened the webview's find bar, and the find bar is what got spoken.
	it('cancels Ctrl+Shift+G so the webview does not also find-previous', () => {
		expect(press({ key: 'G', code: 'KeyG', ctrlKey: true, shiftKey: true })).toBe(true);
	});

	it.each([
		['k', 'KeyK'],
		['r', 'KeyR'],
		['f', 'KeyF'],
		['q', 'KeyQ'],
		['u', 'KeyU']
	])('cancels Ctrl+%s, which the app claims', (key, code) => {
		expect(press({ key, code, ctrlKey: true })).toBe(true);
	});

	/*
	 * Ctrl+N and the workspace digits are matched by `code` in
	 * handleGlobalKeydown so they survive layouts whose top row types letters;
	 * the frame has to mirror that or it cancels the wrong keys on those
	 * layouts. The Czech layout types punctuation and accented letters across
	 * the top row, so `key` there is nothing like the digit.
	 */
	it.each([
		['n', 'KeyN'],
		['+', 'Digit1'],
		[String.fromCodePoint(0x011b), 'Digit2'],
		[String.fromCodePoint(0x0161), 'Digit3']
	])('cancels the app claim on code %s regardless of the typed character', (key, code) => {
		expect(press({ key, code, ctrlKey: true })).toBe(true);
	});

	it.each([
		['c', 'KeyC'],
		['a', 'KeyA'],
		['x', 'KeyX'],
		['z', 'KeyZ']
	])('leaves the editing shortcut Ctrl+%s alone so the body stays copyable', (key, code) => {
		expect(press({ key, code, ctrlKey: true })).toBe(false);
	});

	it('leaves an unmodified letter alone', () => {
		expect(press({ key: 'g', code: 'KeyG' })).toBe(false);
	});

	// Ctrl+Shift+R is the reply-all claim, so Shift does not simply disqualify a
	// keystroke — it selects a different, shorter list.
	it('cancels Ctrl+Shift+R, which the app claims as reply all', () => {
		expect(press({ key: 'R', code: 'KeyR', ctrlKey: true, shiftKey: true })).toBe(true);
	});

	/*
	 * The other half of that rule. handleGlobalKeydown matches Ctrl+K, Ctrl+F,
	 * Ctrl+Q, Ctrl+U and the code-matched Ctrl+N / Ctrl+1-3 under
	 * `!event.shiftKey`, so with Shift held the app claims none of them and the
	 * frame has nothing to keep the webview away from. Cancelling them anyway
	 * left the keystroke doing nothing at all: the webview default gone and no
	 * app action in its place.
	 */
	it.each([
		['k', 'KeyK'],
		['f', 'KeyF'],
		['q', 'KeyQ'],
		['u', 'KeyU'],
		['n', 'KeyN'],
		['1', 'Digit1']
	])('leaves Ctrl+Shift+%s alone, which the app claims only without Shift', (key, code) => {
		expect(press({ key, code, ctrlKey: true, shiftKey: true })).toBe(false);
	});

	// Ctrl+G is find-next in the webview; the app claims Ctrl+Shift+G and not it.
	it('leaves Ctrl+G alone so find-next keeps working in the body', () => {
		expect(press({ key: 'g', code: 'KeyG', ctrlKey: true })).toBe(false);
	});

	// The workspace and new-item keys are Ctrl-only: handleWorkspaceShortcut
	// bails on metaKey, unlike handleMessageShortcut, which folds Meta into Ctrl.
	it('leaves Meta+N alone, since the workspace claim is Ctrl-only', () => {
		expect(press({ key: 'n', code: 'KeyN', metaKey: true })).toBe(false);
	});

	it('leaves Ctrl+Alt combinations alone — the app claims none of them', () => {
		expect(press({ key: 'f', code: 'KeyF', ctrlKey: true, altKey: true })).toBe(false);
	});

	it('ignores an untrusted keystroke entirely', () => {
		expect(press({ isTrusted: false, key: 'G', code: 'KeyG', ctrlKey: true, shiftKey: true })).toBe(
			false
		);
	});
});

describe('isMailFrameLinkMessage', () => {
	const valid: MailFrameLinkMessage = {
		__voxroxMailFrameLink: true,
		href: 'https://example.test/path'
	};

	it('accepts a well-formed link message', () => {
		expect(isMailFrameLinkMessage(valid)).toBe(true);
	});

	it.each([
		['null', null],
		['a string', 'https://example.test'],
		['missing marker', { href: 'https://example.test' }],
		['wrong marker', { ...valid, __voxroxMailFrameLink: false }],
		['non-string href', { ...valid, href: 42 }],
		['missing href', { __voxroxMailFrameLink: true }]
	])('rejects %s', (_label, data) => {
		expect(isMailFrameLinkMessage(data)).toBe(false);
	});
});

describe('isOpenableMailLink', () => {
	/*
	 * Passed explicitly everywhere below: the app origin decides half of these
	 * answers, and reading it from the test harness's own URL would make the
	 * cases depend on which port vitest happened to serve from.
	 */
	const appOrigin = 'https://app.test';

	it.each(['http://example.test', 'https://example.test/x', 'mailto:a@b.test', 'tel:+420123'])(
		'allows the safe scheme %s',
		(href) => {
			expect(isOpenableMailLink(href, appOrigin)).toBe(true);
		}
	);

	it.each([
		'javascript:alert(1)',
		'file:///etc/passwd',
		'about:srcdoc#section',
		'data:text/html,<script>alert(1)</script>',
		'vbscript:msgbox',
		'not a url',
		'#fragment'
	])('rejects the unsafe or non-openable value %s', (href) => {
		expect(isOpenableMailLink(href, appOrigin)).toBe(false);
	});

	/*
	 * What the frame actually posts for a relative href. A srcdoc document
	 * inherits the container's base URL rather than resolving against
	 * `about:srcdoc`, so these arrive already pointing at the app itself —
	 * verified in Chromium, and the reason the check is not protocol-only.
	 */
	it.each([
		['a bare placeholder anchor', 'https://app.test/mail/1/INBOX/msg-01#'],
		['an in-message anchor', 'https://app.test/mail/1/INBOX/msg-01#section'],
		['a relative href', 'https://app.test/foo'],
		['the app root', 'https://app.test/']
	])('refuses %s, which resolved onto the app origin', (_label, href) => {
		expect(isOpenableMailLink(href, appOrigin)).toBe(false);
	});

	it('still allows a real link to another host on the same scheme', () => {
		expect(isOpenableMailLink('https://example.test/app.test', appOrigin)).toBe(true);
	});

	it('falls back to the window origin when none is given', () => {
		expect(isOpenableMailLink(`${window.location.origin}/foo`)).toBe(false);
		expect(isOpenableMailLink('https://example.test/x')).toBe(true);
	});
});

describe('mailFrameKeyToEvent', () => {
	it('reconstructs a keydown carrying the forwarded modifiers', () => {
		const event = mailFrameKeyToEvent({
			__voxroxMailFrameKey: true,
			key: 'r',
			code: 'KeyR',
			ctrlKey: true,
			metaKey: false,
			altKey: false,
			shiftKey: true
		});
		expect(event.type).toBe('keydown');
		expect(event.key).toBe('r');
		expect(event.code).toBe('KeyR');
		expect(event.ctrlKey).toBe(true);
		expect(event.shiftKey).toBe(true);
		expect(event.altKey).toBe(false);
		// Synthetic events are never trusted, so they cannot loop back through
		// the frame forwarder (which only relays isTrusted keystrokes).
		expect(event.isTrusted).toBe(false);
	});
});
