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
	isMailFrameKeyMessage,
	mailFrameKeyToEvent,
	type MailFrameKeyMessage
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
