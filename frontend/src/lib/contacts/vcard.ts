import { splitDisplayName } from '$lib/contacts/displayName.js';
import type { ContactCreateRequest, EmailLabel } from '$lib/types.js';

const KNOWN_LABELS: readonly EmailLabel[] = ['HOME', 'WORK', 'OTHER'];

function unfoldLines(text: string): string[] {
	return text.replace(/\r?\n[ \t]/g, '').split(/\r?\n/);
}

/**
 * Reverses the RFC 6350 §3.4 text escape in a single left-to-right pass.
 *
 * Sequential `.replace()` calls cannot do this correctly: whichever rule runs
 * first also rewrites the escaped backslashes that were meant for the last one,
 * so `\\n` (an escaped backslash followed by the letter n) came out as a real
 * newline instead of `\n`. Scanning once and consuming both characters of every
 * escape pair removes the ordering question entirely.
 */
function unescapeVCard(value: string): string {
	let out = '';
	for (let i = 0; i < value.length; i++) {
		if (value[i] !== '\\' || i + 1 >= value.length) {
			out += value[i];
			continue;
		}
		const next = value[i + 1];
		if (next === 'n' || next === 'N') out += '\n';
		else if (next === ',' || next === ';' || next === '\\') out += next;
		// An unknown escape keeps both characters — the spec does not define it,
		// and silently dropping the backslash would corrupt the value.
		else out += '\\' + next;
		i++;
	}
	return out;
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

/**
 * A parsed card. The contact labels come out as CATEGORIES *names*, not ids —
 * the file has no way to know this account's label ids, so resolving (and
 * creating the missing ones) is the importer's job.
 */
export interface ParsedVCard {
	contact: ContactCreateRequest;
	categories: string[];
}

/**
 * Everything one file yielded: the cards that can become contacts, plus how
 * many it held that carry no e-mail address at all.
 *
 * The count is not bookkeeping for its own sake. A card without an address
 * cannot become a contact (the backend requires at least one), and dropping
 * those silently let an import that lost half a file still report a clean
 * success — the caller has to be able to say so.
 */
export interface ParsedVCardFile {
	cards: ParsedVCard[];
	/** Cards that ended without a usable EMAIL property. */
	skippedWithoutEmail: number;
}

interface CardBuffer {
	nValue: string | null;
	fnValue: string | null;
	note: string | null;
	emails: { email: string; label: EmailLabel | null }[];
	categories: string[];
}

/**
 * Splits a CATEGORIES value on its unescaped commas (RFC 6350 §6.7.1) — a
 * comma written as `\,` is part of a single category name, not a separator.
 */
function splitCategories(value: string): string[] {
	const out: string[] = [];
	let current = '';
	for (let i = 0; i < value.length; i++) {
		const c = value[i];
		if (c === '\\' && i + 1 < value.length) {
			current += value[i] + value[i + 1];
			i++;
		} else if (c === ',') {
			out.push(current);
			current = '';
		} else {
			current += c;
		}
	}
	out.push(current);
	return out.map((item) => unescapeVCard(item).trim()).filter((item) => item.length > 0);
}

function finalizeCard(buf: CardBuffer): ParsedVCard | null {
	if (buf.emails.length === 0) return null;

	let name: string | null = null;
	let surname: string | null = null;

	if (buf.nValue) {
		const parts = buf.nValue.split(';');
		surname = parts[0] ? unescapeVCard(parts[0]).trim() || null : null;
		name = parts[1] ? unescapeVCard(parts[1]).trim() || null : null;
	} else if (buf.fnValue) {
		// FN is one display string, the same shape a message's From line has, so
		// the split is the shared one rather than a second rule.
		({ name, surname } = splitDisplayName(unescapeVCard(buf.fnValue)));
	}

	return {
		contact: {
			name,
			surname,
			note: buf.note,
			emails: buf.emails.map(({ email, label }) => ({ email, label }))
		},
		// Deduplicated case-insensitively, the same rule the backend applies to
		// label names — a card listing "Rodina,rodina" must not ask for two.
		categories: buf.categories.filter(
			(name, index, all) =>
				all.findIndex((other) => other.toLowerCase() === name.toLowerCase()) === index
		)
	};
}

export function parseVCard(text: string): ParsedVCardFile {
	const lines = unfoldLines(text);
	const cards: ParsedVCard[] = [];
	let skippedWithoutEmail = 0;
	let buf: CardBuffer | null = null;

	for (const rawLine of lines) {
		const line = rawLine.trim();
		if (!line) continue;
		const upper = line.toUpperCase();

		if (upper === 'BEGIN:VCARD') {
			buf = { nValue: null, fnValue: null, note: null, emails: [], categories: [] };
			continue;
		}
		if (upper === 'END:VCARD') {
			if (buf) {
				const card = finalizeCard(buf);
				if (card) cards.push(card);
				else skippedWithoutEmail++;
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
		} else if (fieldUpper === 'CATEGORIES') {
			// A card may repeat the property; accumulate rather than overwrite.
			buf.categories.push(...splitCategories(value));
		}
	}

	return { cards, skippedWithoutEmail };
}
