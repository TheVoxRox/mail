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

		if (tagName === 'img' && name === 'alt') {
			element.setAttribute('alt', value);
			continue;
		}

		if ((tagName === 'td' || tagName === 'th') && (name === 'colspan' || name === 'rowspan')) {
			element.setAttribute(name, value.replace(/[^0-9]/g, ''));
		}
	}

	if (tagName === 'img' && !element.hasAttribute('src')) {
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
