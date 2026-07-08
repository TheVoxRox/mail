// @vitest-environment jsdom
import { describe, expect, it } from 'vitest';
import { isMailHtml, mailHtmlToPlainText, sanitizeMailHtml } from './content-sanitizer.js';

describe('mailHtmlToPlainText', () => {
	it('unwraps the backend mail-content-wrapper to plain text', () => {
		const html =
			"<div class='mail-content-wrapper' style='all: revert;'>this is an email sending test.</div>";
		expect(mailHtmlToPlainText(html)).toBe('this is an email sending test.');
	});

	it('turns <br> into newlines', () => {
		expect(mailHtmlToPlainText('first<br>second')).toBe('first\nsecond');
	});

	it('separates paragraphs with a blank line', () => {
		expect(mailHtmlToPlainText('<p>one</p><p>two</p>')).toBe('one\n\ntwo');
	});

	it('decodes HTML entities', () => {
		expect(mailHtmlToPlainText('<div>a &lt; b &amp; c</div>')).toBe('a < b & c');
	});

	it('returns empty string for empty input', () => {
		expect(mailHtmlToPlainText('')).toBe('');
	});

	it('drops script and style bodies instead of leaking their source', () => {
		const html = '<div>before<script>window.__x=1</script><style>.a{color:red}</style>after</div>';
		const text = mailHtmlToPlainText(html);
		expect(text).not.toContain('window.__x');
		expect(text).not.toContain('color:red');
		expect(text).toContain('before');
		expect(text).toContain('after');
	});
});

describe('isMailHtml', () => {
	it('detects HTML content', () => {
		expect(isMailHtml('<p>hello</p>')).toBe(true);
		expect(isMailHtml('plain text')).toBe(false);
		expect(isMailHtml('<custom-element>x</custom-element>')).toBe(true);
	});

	it('does not treat angle brackets in text as HTML', () => {
		expect(isMailHtml('1 < 2')).toBe(false);
		expect(isMailHtml('< not a tag')).toBe(false);
	});
});

describe('sanitizeMailHtml — XSS hardening', () => {
	it('removes <script> tags entirely', () => {
		const result = sanitizeMailHtml('<p>hi</p><script>alert(1)</script>');
		expect(result).toBe('<p>hi</p>');
		expect(result).not.toContain('alert');
	});

	it('removes <style> tags entirely', () => {
		const result = sanitizeMailHtml('<style>body{display:none}</style><p>hi</p>');
		expect(result).toBe('<p>hi</p>');
		expect(result).not.toContain('display:none');
	});

	it('removes <iframe>, <object>, <embed>', () => {
		const result = sanitizeMailHtml(
			'<iframe src="https://evil.test"></iframe><object data="x"></object><embed src="y"><p>safe</p>'
		);
		expect(result).toBe('<p>safe</p>');
	});

	it('removes form-related elements', () => {
		const result = sanitizeMailHtml(
			'<form><input name="pw"><button>Go</button><textarea></textarea></form><p>safe</p>'
		);
		expect(result).toBe('<p>safe</p>');
	});

	it('removes <link> and <meta>', () => {
		const result = sanitizeMailHtml(
			'<link rel="stylesheet" href="evil.css"><meta http-equiv="refresh" content="0;url=evil"><p>safe</p>'
		);
		expect(result).toBe('<p>safe</p>');
	});

	it('strips on* event handlers from allowed elements', () => {
		const result = sanitizeMailHtml('<p onclick="alert(1)" onmouseover="alert(2)">hi</p>');
		expect(result).toBe('<p>hi</p>');
		expect(result).not.toContain('onclick');
		expect(result).not.toContain('alert');
	});

	it('strips style attribute (CSS-based XSS / data exfiltration via background-image)', () => {
		const result = sanitizeMailHtml('<p style="background:url(javascript:alert(1))">hi</p>');
		expect(result).toBe('<p>hi</p>');
		expect(result).not.toContain('style');
	});

	it('blocks javascript: in <a href>', () => {
		const result = sanitizeMailHtml('<a href="javascript:alert(1)">click</a>');
		expect(result).toBe('<a>click</a>');
		expect(result).not.toContain('javascript');
	});

	it('blocks data: in <a href>', () => {
		const result = sanitizeMailHtml('<a href="data:text/html,<script>alert(1)</script>">click</a>');
		expect(result).toBe('<a>click</a>');
	});

	it('keeps http/https/mailto/tel hrefs and adds rel/target safety', () => {
		const result = sanitizeMailHtml(
			'<a href="https://example.test">a</a><a href="http://example.test">b</a><a href="mailto:x@y.test">c</a><a href="tel:+1">d</a>'
		);
		expect(result).toContain('href="https://example.test"');
		expect(result).toContain('href="http://example.test"');
		expect(result).toContain('href="mailto:x@y.test"');
		expect(result).toContain('href="tel:+1"');
		// Every kept <a> gets target="_blank" + rel="noopener noreferrer nofollow"
		const targetCount = (result.match(/target="_blank"/g) ?? []).length;
		const relCount = (result.match(/rel="noopener noreferrer nofollow"/g) ?? []).length;
		expect(targetCount).toBe(4);
		expect(relCount).toBe(4);
	});

	it('drops javascript: in <img src>', () => {
		const result = sanitizeMailHtml('<img src="javascript:alert(1)" alt="x">');
		expect(result).not.toContain('javascript');
		expect(result).not.toContain('<img');
	});

	it('drops <img> when src is removed (empty img)', () => {
		const result = sanitizeMailHtml('<img src="http://tracker.test/1.gif">');
		// http: is not an inline image data URL and is not allowed for <img src>
		expect(result).not.toContain('<img');
	});

	it('keeps inline data:image/* base64 src on <img>', () => {
		const tinyPng =
			'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';
		const result = sanitizeMailHtml(`<img src="${tinyPng}" alt="dot">`);
		expect(result).toContain(`src="${tinyPng}"`);
		expect(result).toContain('alt="dot"');
	});

	it('keeps allowed inline image formats only (no SVG)', () => {
		const svgDataUrl = 'data:image/svg+xml;base64,PHN2ZyBvbmxvYWQ9ImFsZXJ0KDEpIj48L3N2Zz4=';
		const result = sanitizeMailHtml(`<img src="${svgDataUrl}">`);
		// SVG data URLs can carry onload handlers → must be rejected
		expect(result).not.toContain('svg+xml');
	});

	it('preserves a remote https image inertly (data-voxrox-remote-src, no live src)', () => {
		const result = sanitizeMailHtml(
			'<img data-voxrox-remote-src="https://cdn.test/logo.png" alt="logo">'
		);
		expect(result).toContain('data-voxrox-remote-src="https://cdn.test/logo.png"');
		expect(result).toContain('alt="logo"');
		// A live src is space-separated from the tag; the data-*-remote-src attr is not.
		expect(result).not.toContain(' src="https://cdn.test/logo.png"');
	});

	it('drops a non-https remote reference (http / javascript are never opted into)', () => {
		expect(
			sanitizeMailHtml('<img data-voxrox-remote-src="http://cdn.test/logo.png">')
		).not.toContain('<img');
		expect(sanitizeMailHtml('<img data-voxrox-remote-src="javascript:alert(1)">')).not.toContain(
			'<img'
		);
	});

	it('strips srcset, srcdoc, formaction and other unknown attributes', () => {
		const result = sanitizeMailHtml(
			'<a srcset="x" srcdoc="<script>alert(1)</script>" formaction="javascript:alert(1)">click</a>'
		);
		expect(result).toBe('<a>click</a>');
	});

	it('unwraps unknown tags (does not delete their children)', () => {
		const result = sanitizeMailHtml('<custom-element>text inside</custom-element>');
		expect(result).toBe('text inside');
	});

	it('unwraps <svg> (with hostile onload) while preserving text', () => {
		const result = sanitizeMailHtml('<svg onload="alert(1)">caption</svg>');
		// <svg> is not in the allow-list → unwrapped to its text content,
		// and onload is dropped as part of unwrap (no attributes survive).
		expect(result).not.toContain('alert');
		expect(result).not.toContain('onload');
		expect(result).toContain('caption');
	});

	it('preserves allowed block + inline elements', () => {
		const result = sanitizeMailHtml(
			'<p>plain</p><strong>bold</strong><em>em</em><blockquote>q</blockquote><h1>h</h1><table><tr><td>cell</td></tr></table>'
		);
		expect(result).toContain('<p>plain</p>');
		expect(result).toContain('<strong>bold</strong>');
		expect(result).toContain('<em>em</em>');
		expect(result).toContain('<blockquote>q</blockquote>');
		expect(result).toContain('<h1>h</h1>');
		expect(result).toContain('<td>cell</td>');
	});

	it('sanitises colspan/rowspan to digits only', () => {
		const result = sanitizeMailHtml(
			'<table><tr><td colspan="2; evil" rowspan="1">x</td></tr></table>'
		);
		expect(result).toContain('colspan="2"');
		expect(result).toContain('rowspan="1"');
		expect(result).not.toContain('evil');
	});

	it('returns empty string when DOMParser is unavailable', () => {
		const original = globalThis.DOMParser;
		// @ts-expect-error — simulating server-side / non-DOM environment
		delete globalThis.DOMParser;
		try {
			expect(sanitizeMailHtml('<p>hi</p>')).toBe('');
		} finally {
			globalThis.DOMParser = original;
		}
	});

	it('uses the provided origin to resolve relative hrefs', () => {
		const result = sanitizeMailHtml('<a href="/page">a</a>', 'https://app.example.test');
		// Relative URLs resolve to a safe protocol → href is kept verbatim.
		expect(result).toContain('href="/page"');
	});

	it('handles deeply nested malicious payload', () => {
		const evil =
			'<div><span><p><img src="javascript:alert(1)"><script>alert(2)</script></p><a href="javascript:alert(3)" onclick="alert(4)">x</a></span></div>';
		const result = sanitizeMailHtml(evil);
		expect(result).not.toMatch(/javascript|alert|onclick|script/i);
	});
});
