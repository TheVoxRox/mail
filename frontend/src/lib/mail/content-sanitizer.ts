const allowedHtmlTags = new Set([
	'a',
	'abbr',
	'b',
	'blockquote',
	'br',
	'caption',
	'code',
	'col',
	'colgroup',
	'dd',
	'del',
	'div',
	'dl',
	'dt',
	'em',
	'h1',
	'h2',
	'h3',
	'h4',
	'h5',
	'h6',
	'hr',
	'i',
	'img',
	'li',
	'ol',
	'p',
	'pre',
	'q',
	's',
	'small',
	'span',
	'strong',
	'sub',
	'sup',
	'table',
	'tbody',
	'td',
	'tfoot',
	'th',
	'thead',
	'tr',
	'u',
	'ul'
]);

const allowedUriProtocols = new Set(['http:', 'https:', 'mailto:', 'tel:']);

/**
 * Inert attribute carrying a remote https image URL that the backend sanitizer
 * preserved without ever making it a live `src` (see HtmlSanitizer). It stays
 * inert here too — the mail frame only promotes it to a real `src` under the
 * user's explicit "load remote images" gesture (content-rendering audit F2).
 */
export const REMOTE_IMAGE_ATTR = 'data-voxrox-remote-src';

function isSafeLink(value: string, origin: string): boolean {
	try {
		const url = new URL(value, origin);
		return allowedUriProtocols.has(url.protocol);
	} catch {
		return false;
	}
}

function isInlineImage(value: string): boolean {
	return /^data:image\/(?:gif|png|jpe?g|webp|bmp);base64,[a-z0-9+/=\s]+$/i.test(value);
}

/** True only for an absolute `https:` URL — the one remote scheme we ever opt into. */
function isRemoteHttpsImage(value: string): boolean {
	try {
		return new URL(value).protocol === 'https:';
	} catch {
		return false;
	}
}

function unwrapElement(element: Element): void {
	const parent = element.parentNode;
	if (!parent) return;
	while (element.firstChild) {
		parent.insertBefore(element.firstChild, element);
	}
	parent.removeChild(element);
}

function sanitizeElement(element: Element, origin: string): void {
	const tagName = element.tagName.toLowerCase();

	if (!allowedHtmlTags.has(tagName)) {
		unwrapElement(element);
		return;
	}

	for (const attr of Array.from(element.attributes)) {
		const name = attr.name.toLowerCase();
		const value = attr.value.trim();

		element.removeAttribute(attr.name);

		if (tagName === 'a' && name === 'href' && isSafeLink(value, origin)) {
			element.setAttribute('href', value);
			element.setAttribute('target', '_blank');
			element.setAttribute('rel', 'noopener noreferrer nofollow');
			continue;
		}

		if (tagName === 'img' && name === 'src' && isInlineImage(value)) {
			element.setAttribute('src', value);
			continue;
		}

		if (tagName === 'img' && name === REMOTE_IMAGE_ATTR && isRemoteHttpsImage(value)) {
			// Kept inert (no live src). The frame promotes it to a real src only
			// when the user opts into loading remote images for this message.
			element.setAttribute(REMOTE_IMAGE_ATTR, value);
			continue;
		}

		if (tagName === 'img' && name === 'alt') {
			element.setAttribute('alt', value);
			continue;
		}

		if ((tagName === 'td' || tagName === 'th') && (name === 'colspan' || name === 'rowspan')) {
			element.setAttribute(name, value.replace(/[^0-9]/g, ''));
		}
	}

	// Drop an <img> that ended up with neither a usable inline src nor a preserved
	// remote reference (e.g. a dropped remote/cid src) — it would render broken.
	if (
		tagName === 'img' &&
		!element.hasAttribute('src') &&
		!element.hasAttribute(REMOTE_IMAGE_ATTR)
	) {
		element.replaceWith(document.createTextNode(''));
	}
}

export function isMailHtml(content: string): boolean {
	return /<\w+[\s\S]*?>/.test(content);
}

/**
 * Flattens display HTML (as returned by the content endpoint, wrapped in the
 * backend's `mail-content-wrapper`) into plain text for the plain-text compose
 * editor. Mirrors the backend `MailDraftService.htmlToPlainText` used for
 * reply/forward: block elements and <br> become line breaks; entities are
 * decoded via textContent.
 */
export function mailHtmlToPlainText(html: string): string {
	if (!html) return '';
	if (typeof DOMParser === 'undefined') return html;

	const doc = new DOMParser().parseFromString(html, 'text/html');
	// Drop non-visible elements first — textContent would otherwise leak their
	// source (e.g. a <script> body) into the flattened plain text.
	doc.querySelectorAll('script, style').forEach((node) => node.remove());
	doc.querySelectorAll('br').forEach((br) => br.replaceWith('\n'));
	doc.querySelectorAll('p').forEach((p) => p.prepend('\n\n'));
	doc
		.querySelectorAll('div, tr, li, h1, h2, h3, h4, h5, h6, blockquote')
		.forEach((el) => el.append('\n'));

	const text = doc.body.textContent ?? '';
	return text
		.replace(/[ \t]+\n/g, '\n')
		.replace(/\n{3,}/g, '\n\n')
		.trim();
}

export function sanitizeMailHtml(html: string, origin?: string): string {
	if (typeof DOMParser === 'undefined') return '';

	const baseOrigin =
		origin ?? (typeof window === 'undefined' ? 'http://localhost' : window.location.origin);
	const doc = new DOMParser().parseFromString(html, 'text/html');

	doc
		.querySelectorAll(
			'script, style, link, meta, iframe, object, embed, form, input, button, textarea'
		)
		.forEach((node) => node.remove());

	Array.from(doc.body.querySelectorAll('*')).forEach((element) =>
		sanitizeElement(element, baseOrigin)
	);

	return doc.body.innerHTML;
}
