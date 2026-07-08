// @vitest-environment jsdom
import { createHash } from 'node:crypto';
import { describe, expect, it } from 'vitest';
import {
	MAIL_FRAME_CSP,
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

describe('MAIL_FRAME_SCRIPT_SHA256', () => {
	it('matches the actual SHA-256 of the forwarder script', () => {
		// If this fails, the CSP hash no longer pins the script and the body
		// frame would silently block ALL scripts — including the forwarder —
		// so every shortcut would break. Update the constant to the value below.
		const actual = createHash('sha256').update(MAIL_FRAME_SCRIPT, 'utf8').digest('base64');
		expect(actual).toBe(MAIL_FRAME_SCRIPT_SHA256);
	});

	it('is referenced by the frame CSP script-src', () => {
		expect(MAIL_FRAME_CSP).toContain(`script-src 'sha256-${MAIL_FRAME_SCRIPT_SHA256}'`);
		expect(MAIL_FRAME_CSP).toContain("default-src 'none'");
		expect(MAIL_FRAME_CSP).toContain('img-src data:');
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
		expect(MAIL_FRAME_CSP).toContain(`style-src 'sha256-${MAIL_FRAME_STYLE_SHA256}'`);
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
	it.each(['http://example.test', 'https://example.test/x', 'mailto:a@b.test', 'tel:+420123'])(
		'allows the safe scheme %s',
		(href) => {
			expect(isOpenableMailLink(href)).toBe(true);
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
		expect(isOpenableMailLink(href)).toBe(false);
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
