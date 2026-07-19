import type { ContactCreateRequest, EmailLabel } from '$lib/types.js';

const KNOWN_LABELS: readonly EmailLabel[] = ['HOME', 'WORK', 'OTHER'];

function unfoldLines(text: string): string[] {
	return text.replace(/\r?\n[ \t]/g, '').split(/\r?\n/);
}

function unescapeVCard(value: string): string {
	return value
		.replace(/\\n/gi, '\n')
		.replace(/\\,/g, ',')
		.replace(/\\;/g, ';')
		.replace(/\\\\/g, '\\');
}

function parseEmailLabel(params: string[]): EmailLabel | null {
	const types = params
		.map((p) => p.toUpperCase())
		.filter((p) => p.startsWith('TYPE='))
		.flatMap((p) => p.slice(5).split(','));
	for (const t of types) {
		const upper = t.toUpperCase().trim();
		if ((KNOWN_LABELS as readonly string[]).includes(upper)) {
			return upper as EmailLabel;
		}
	}
	return null;
}

interface CardBuffer {
	nValue: string | null;
	fnValue: string | null;
	note: string | null;
	emails: { email: string; label: EmailLabel | null }[];
}

function finalizeCard(buf: CardBuffer): ContactCreateRequest | null {
	if (buf.emails.length === 0) return null;

	let name: string | null = null;
	let surname: string | null = null;

	if (buf.nValue) {
		const parts = buf.nValue.split(';');
		surname = parts[0] ? unescapeVCard(parts[0]).trim() || null : null;
		name = parts[1] ? unescapeVCard(parts[1]).trim() || null : null;
	} else if (buf.fnValue) {
		const fn = unescapeVCard(buf.fnValue).trim();
		const idx = fn.lastIndexOf(' ');
		if (idx > 0) {
			name = fn.slice(0, idx);
			surname = fn.slice(idx + 1);
		} else if (fn) {
			name = fn;
		}
	}

	return {
		name,
		surname,
		note: buf.note,
		emails: buf.emails.map(({ email, label }) => ({ email, label }))
	};
}

export function parseVCard(text: string): ContactCreateRequest[] {
	const lines = unfoldLines(text);
	const cards: ContactCreateRequest[] = [];
	let buf: CardBuffer | null = null;

	for (const rawLine of lines) {
		const line = rawLine.trim();
		if (!line) continue;
		const upper = line.toUpperCase();

		if (upper === 'BEGIN:VCARD') {
			buf = { nValue: null, fnValue: null, note: null, emails: [] };
			continue;
		}
		if (upper === 'END:VCARD') {
			if (buf) {
				const card = finalizeCard(buf);
				if (card) cards.push(card);
			}
			buf = null;
			continue;
		}
		if (!buf) continue;

		const sep = line.indexOf(':');
		if (sep < 0) continue;
		const fieldPart = line.slice(0, sep);
		const value = line.slice(sep + 1);
		const [field, ...params] = fieldPart.split(';');
		const fieldUpper = field.toUpperCase();

		if (fieldUpper === 'N') {
			buf.nValue = value;
		} else if (fieldUpper === 'FN') {
			buf.fnValue = value;
		} else if (fieldUpper === 'EMAIL') {
			const email = unescapeVCard(value).trim();
			if (email && email.includes('@')) {
				buf.emails.push({ email, label: parseEmailLabel(params) });
			}
		} else if (fieldUpper === 'NOTE') {
			buf.note = unescapeVCard(value).trim() || null;
		}
	}

	return cards;
}
