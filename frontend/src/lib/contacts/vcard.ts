import { isValidEmailAddress } from '#lib/compose/addresses.js';
import { splitDisplayName } from '#lib/contacts/displayName.js';
import { strip, stripBlankLinesToNull, stripToNull } from '#lib/storedText.js';
import type { ContactCreateRequest, EmailLabel } from '#lib/types.js';

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

/**
 * Strips the optional group prefix from a property name — `group "." name`
 * (RFC 6350 §3.3). Apple Contacts and iCloud write every property grouped
 * (`item1.EMAIL:…`), and those cards reach us through any address book a phone
 * syncs into. Without this the name never matches, the address is lost, and a
 * card with no address is dropped whole.
 *
 * Applied to the name segment only. The field part may hold a parameter with a
 * dot in its value (`EMAIL;X-SERVICE=a.b:…`), which is not a group separator.
 */
function stripGroup(field: string): string {
	const dot = field.indexOf('.');
	return dot < 0 ? field : field.slice(dot + 1);
}

/**
 * True when the property declares quoted-printable. vCard 2.1 allows a bare
 * parameter value as well as the `ENCODING=` form, and exporters use both.
 */
function isQuotedPrintable(params: string[]): boolean {
	return params.some((p) => {
		const upper = p.toUpperCase().trim();
		return upper === 'QUOTED-PRINTABLE' || upper === 'ENCODING=QUOTED-PRINTABLE';
	});
}

/** The declared CHARSET, or UTF-8 when the property names none. */
function charsetOf(params: string[]): string {
	const declared = params.find((p) => p.toUpperCase().trim().startsWith('CHARSET='));
	if (!declared) return 'utf-8';
	return (
		declared
			.slice(declared.indexOf('=') + 1)
			.replace(/^"|"$/g, '')
			.trim() || 'utf-8'
	);
}

/**
 * Decodes a quoted-printable value (RFC 2045 §6.7).
 *
 * Bytes are collected first and decoded as one buffer, because a single
 * character can span several escapes — `=C3=A1` is one code point in UTF-8, and
 * decoding pair by pair would yield two replacement characters. The charset
 * matters for Czech data in particular: old Outlook exports declare
 * windows-1250, where the same `=E1` byte is a different letter than it would
 * be in UTF-8.
 *
 * An `=` that is not followed by two hex digits keeps its characters, the same
 * convention {@link unescapeVCard} follows for undefined vCard escapes — a
 * malformed card should lose as little as possible, not be swallowed.
 */
function decodeQuotedPrintable(value: string, charset: string): string {
	const encoder = new TextEncoder();
	const bytes: number[] = [];
	for (let i = 0; i < value.length; i++) {
		const pair = value.slice(i + 1, i + 3);
		if (value[i] === '=' && /^[0-9a-fA-F]{2}$/.test(pair)) {
			bytes.push(Number.parseInt(pair, 16));
			i += 2;
			continue;
		}
		for (const byte of encoder.encode(value[i])) bytes.push(byte);
	}
	const buffer = new Uint8Array(bytes);
	try {
		return new TextDecoder(charset).decode(buffer);
	} catch {
		// An unknown charset label must not cost us the value.
		return new TextDecoder('utf-8').decode(buffer);
	}
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
 * many it held that carry no valid e-mail address at all, and how many
 * addresses the cards it kept had to leave out.
 *
 * The counts are not bookkeeping for their own sake. A card without an address
 * cannot become a contact (the backend requires at least one), and dropping
 * those silently let an import that lost half a file still report a clean
 * success — the caller has to be able to say so.
 */
export interface ParsedVCardFile {
	cards: ParsedVCard[];
	/** Cards that ended without an EMAIL property holding a valid address. */
	skippedWithoutEmail: number;
	/** Addresses left out of the cards in `cards`, as not valid. */
	invalidAddresses: number;
}

interface CardBuffer {
	/**
	 * N split into its structural components, still escaped. Split at read time
	 * rather than in {@link finalizeCard} so the semicolons that separate the
	 * components are the ones the file wrote — a quoted-printable `=3B` inside a
	 * component decodes to a semicolon, and decoding before the split would turn
	 * it into a separator.
	 */
	nParts: string[] | null;
	fnValue: string | null;
	note: string | null;
	emails: { email: string; label: EmailLabel | null }[];
	/** EMAIL values left out as not valid addresses. */
	invalidAddresses: number;
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
	return out.map((item) => strip(unescapeVCard(item))).filter((item) => item.length > 0);
}

function finalizeCard(buf: CardBuffer): ParsedVCard | null {
	if (buf.emails.length === 0) return null;

	let name: string | null = null;
	let surname: string | null = null;

	if (buf.nParts) {
		const parts = buf.nParts;
		surname = parts[0] ? stripToNull(unescapeVCard(parts[0])) : null;
		name = parts[1] ? stripToNull(unescapeVCard(parts[1])) : null;
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
	let invalidAddresses = 0;
	let buf: CardBuffer | null = null;

	for (let index = 0; index < lines.length; index++) {
		const line = lines[index].trim();
		if (!line) continue;
		const upper = line.toUpperCase();

		if (upper === 'BEGIN:VCARD') {
			buf = {
				nParts: null,
				fnValue: null,
				note: null,
				emails: [],
				invalidAddresses: 0,
				categories: []
			};
			continue;
		}
		if (upper === 'END:VCARD') {
			if (buf) {
				const card = finalizeCard(buf);
				// A card that lost every address is reported once, as skipped, not
				// also by the addresses it lost.
				if (card) {
					cards.push(card);
					invalidAddresses += buf.invalidAddresses;
				} else skippedWithoutEmail++;
			}
			buf = null;
			continue;
		}
		if (!buf) continue;

		const sep = line.indexOf(':');
		if (sep < 0) continue;
		const fieldPart = line.slice(0, sep);
		let value = line.slice(sep + 1);
		const [field, ...params] = fieldPart.split(';');
		const fieldUpper = stripGroup(field).toUpperCase();

		/*
		 * Quoted-printable wraps a long value with a trailing `=` and continues on
		 * the next line (RFC 2045 §6.7). Joining is gated on the property actually
		 * declaring the encoding: base64 pads with `=` at a line end too, and
		 * joining there would swallow the property that follows.
		 */
		const quotedPrintable = isQuotedPrintable(params);
		if (quotedPrintable) {
			while (value.endsWith('=') && index + 1 < lines.length) {
				value = value.slice(0, -1) + lines[++index].trim();
			}
		}
		const charset = charsetOf(params);
		const decode = (raw: string) => (quotedPrintable ? decodeQuotedPrintable(raw, charset) : raw);

		if (fieldUpper === 'N') {
			buf.nParts = value.split(';').map(decode);
		} else if (fieldUpper === 'FN') {
			buf.fnValue = decode(value);
		} else if (fieldUpper === 'EMAIL') {
			const email = strip(unescapeVCard(decode(value)));
			/*
			 * An address the backend would refuse is left out here, by the rule it
			 * validates with: the bulk endpoint refuses the whole request over one
			 * invalid field, so a single `jan@moje_firma.cz` in a file cost every
			 * contact of its batch. An empty EMAIL is no address at all and is not
			 * counted.
			 */
			if (email === '') continue;
			if (!isValidEmailAddress(email)) {
				buf.invalidAddresses++;
				continue;
			}
			/*
			 * The backend stores an address lower-cased and refuses a contact that
			 * lists one twice, which costs the whole card. A card naming the same
			 * mailbox twice (repeated, or as Jan@X.cz and jan@x.cz) keeps the first.
			 */
			const key = email.toLowerCase();
			if (!buf.emails.some((known) => known.email.toLowerCase() === key)) {
				buf.emails.push({ email, label: parseEmailLabel(params) });
			}
		} else if (fieldUpper === 'NOTE') {
			// A note runs over several lines (\n in the card), so it is stored as a block.
			buf.note = stripBlankLinesToNull(unescapeVCard(decode(value)));
		} else if (fieldUpper === 'CATEGORIES') {
			// A card may repeat the property; accumulate rather than overwrite.
			buf.categories.push(...splitCategories(decode(value)));
		}
	}

	return { cards, skippedWithoutEmail, invalidAddresses };
}
