import { describe, expect, it } from 'vitest';
import { parseVCard, type ParsedVCard } from './vcard.js';

/**
 * The vCard reader is the one place untrusted third-party text becomes contact
 * data, and CATEGORIES is the part with real parsing in it: the commas that
 * separate categories and the commas that belong inside a name look the same
 * until you honour the backslash escape (RFC 6350 §6.7.1). These tests pin that
 * boundary plus the card-level behaviour the importer relies on.
 */

function card(body: string): string {
	return `BEGIN:VCARD\r\nVERSION:4.0\r\n${body}\r\nEND:VCARD\r\n`;
}

/** The importable cards of a file; the skip count has its own describe below. */
function parse(text: string): ParsedVCard[] {
	return parseVCard(text).cards;
}

describe('parseVCard — contact basics', () => {
	it('reads N into surname/given and keeps the e-mail', () => {
		const [parsed] = parse(card('N:Novak;Jan;;;\r\nEMAIL:jan@x.cz'));

		expect(parsed.contact.name).toBe('Jan');
		expect(parsed.contact.surname).toBe('Novak');
		expect(parsed.contact.emails).toEqual([{ email: 'jan@x.cz', label: null }]);
	});

	it('drops a card with no e-mail — the backend requires at least one', () => {
		expect(parse(card('FN:Nikdo'))).toEqual([]);
	});

	it('counts the cards it dropped so the importer can report them', () => {
		// Dropping them is right; dropping them silently is what let an import
		// that lost half a file still toast a clean success.
		const file = parseVCard(
			card('FN:Jan\r\nEMAIL:jan@x.cz') + card('FN:Nikdo') + card('FN:Nikdo Druhy')
		);

		expect(file.cards).toHaveLength(1);
		expect(file.skippedWithoutEmail).toBe(2);
	});

	it('counts no skip when every card carries an address', () => {
		expect(parseVCard(card('FN:Jan\r\nEMAIL:jan@x.cz')).skippedWithoutEmail).toBe(0);
	});

	it('reads the address type from the TYPE parameter', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL;TYPE=work:jan@x.cz'));

		expect(parsed.contact.emails[0].label).toBe('WORK');
	});
});

describe('parseVCard — CATEGORIES', () => {
	it('is an empty list when the property is absent', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz'));

		expect(parsed.categories).toEqual([]);
	});

	it('splits on commas and trims the names', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodina, Klienti'));

		expect(parsed.categories).toEqual(['Rodina', 'Klienti']);
	});

	it('keeps an escaped comma inside a single category', () => {
		// Without honouring the escape this would come back as two categories.
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Klienti\\, VIP,Rodina'));

		expect(parsed.categories).toEqual(['Klienti, VIP', 'Rodina']);
	});

	it('unescapes semicolons and backslashes in a name', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:A\\;B,C\\\\D'));

		expect(parsed.categories).toEqual(['A;B', 'C\\D']);
	});

	it('an escaped backslash before n stays a backslash, not a newline', () => {
		// `\\n` is an escaped backslash followed by the letter n. Unescaping the
		// two rules in the wrong order turns it into a real newline, which would
		// then be persisted as part of a label name.
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:C:\\\\new'));

		expect(parsed.categories).toEqual(['C:\\new']);
	});

	it('drops empty entries from stray separators', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodina,,'));

		expect(parsed.categories).toEqual(['Rodina']);
	});

	it('deduplicates case-insensitively, keeping the first spelling', () => {
		// Mirrors the backend's uniqueness rule, so the importer never asks for
		// two labels that the server would consider the same one.
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodina,RODINA,rodina'));

		expect(parsed.categories).toEqual(['Rodina']);
	});

	it('accumulates a repeated CATEGORIES property instead of overwriting', () => {
		const [parsed] = parse(
			card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodina\r\nCATEGORIES:Klienti')
		);

		expect(parsed.categories).toEqual(['Rodina', 'Klienti']);
	});

	it('does not leak categories between cards', () => {
		const parsed = parse(
			card('FN:Jan\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodina') + card('FN:Petr\r\nEMAIL:petr@x.cz')
		);

		expect(parsed).toHaveLength(2);
		expect(parsed[0].categories).toEqual(['Rodina']);
		expect(parsed[1].categories).toEqual([]);
	});

	it('survives line folding, which splits a long value across lines', () => {
		const folded =
			'BEGIN:VCARD\r\nVERSION:4.0\r\nEMAIL:jan@x.cz\r\nCATEGORIES:Rodi\r\n na,Klienti\r\nEND:VCARD\r\n';

		const [parsed] = parse(folded);

		expect(parsed.categories).toEqual(['Rodina', 'Klienti']);
	});
});

/**
 * A property line may carry a group prefix — `group "." name` (RFC 6350 §3.3).
 * Apple Contacts and iCloud write every property that way, and those cards
 * reach us through any address book a phone syncs into, so this is the shape a
 * real export has rather than an exotic one. Matching the property name without
 * stripping the group dropped the EMAIL, and a card with no address is dropped
 * whole — the import lost the contact, not just its group.
 */
describe('parseVCard — property groups', () => {
	it('reads an e-mail off a grouped property', () => {
		const [parsed] = parse(card('FN:Jan\r\nitem1.EMAIL:jan@x.cz'));

		expect(parsed.contact.emails).toEqual([{ email: 'jan@x.cz', label: null }]);
	});

	it('still reads the TYPE parameter of a grouped property', () => {
		const [parsed] = parse(card('FN:Jan\r\nitem1.EMAIL;TYPE=WORK:jan@x.cz'));

		expect(parsed.contact.emails[0].label).toBe('WORK');
	});

	it('reads grouped N, NOTE and CATEGORIES', () => {
		const [parsed] = parse(
			card(
				'item1.N:Novak;Jan;;;\r\nitem2.EMAIL:jan@x.cz\r\nitem3.NOTE:Pozn\r\nitem4.CATEGORIES:Rodina'
			)
		);

		expect(parsed.contact.surname).toBe('Novak');
		expect(parsed.contact.note).toBe('Pozn');
		expect(parsed.categories).toEqual(['Rodina']);
	});

	it('is case-insensitive about the property name after the group', () => {
		const [parsed] = parse(card('FN:Jan\r\nitem1.email:jan@x.cz'));

		expect(parsed.contact.emails).toHaveLength(1);
	});

	it('does not treat a dot inside a parameter as a group separator', () => {
		// The group prefix belongs to the property name alone. Stripping up to the
		// first dot of the whole field part would eat the property name here and
		// leave `b` — no property matches that, and the address vanishes.
		const [parsed] = parse(card('FN:Jan\r\nEMAIL;X-SERVICE=a.b:jan@x.cz'));

		expect(parsed.contact.emails).toEqual([{ email: 'jan@x.cz', label: null }]);
	});
});

/**
 * vCard 2.1 encodes non-ASCII with quoted-printable (RFC 2045 §6.7) and names
 * its charset in a parameter. Both matter for Czech data specifically: an
 * undecoded card imports a contact literally called `Nov=C3=A1k`, and old
 * Outlook exports still declare windows-1250 rather than UTF-8.
 *
 * Expected values are built from code points rather than written as letters:
 * the assertion is precisely about which code point the decoder produces, and
 * `check:translations` scans source for literal Czech diacritics.
 */
describe('parseVCard — quoted-printable', () => {
	/** `Novak` with an acute over the a. */
	const NOVAK = `Nov${String.fromCodePoint(0xe1)}k`;
	/** `Pratel` with a caron over the r and an acute over the a. */
	const PRATEL = `P${String.fromCodePoint(0x159)}${String.fromCodePoint(0xe1)}tel`;

	function v21(body: string): string {
		return `BEGIN:VCARD\r\nVERSION:2.1\r\n${body}\r\nEND:VCARD\r\n`;
	}

	it('decodes a UTF-8 multi-byte sequence in N', () => {
		const [parsed] = parse(
			v21('N;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:Nov=C3=A1k;Jan;;;\r\nEMAIL:jan@x.cz')
		);

		expect(parsed.contact.surname).toBe(NOVAK);
	});

	it('decodes FN, which is what the display-name split then reads', () => {
		const [parsed] = parse(v21('FN;ENCODING=QUOTED-PRINTABLE:Jan Nov=C3=A1k\r\nEMAIL:jan@x.cz'));

		expect(parsed.contact.surname).toBe(NOVAK);
	});

	it('decodes a NOTE', () => {
		const [parsed] = parse(
			v21('FN:Jan\r\nEMAIL:jan@x.cz\r\nNOTE;ENCODING=QUOTED-PRINTABLE:P=C5=99=C3=A1tel')
		);

		expect(parsed.contact.note).toBe(PRATEL);
	});

	it('honours a non-UTF-8 CHARSET', () => {
		// The same byte is a different letter per charset: 0xE1 is U+00E1 in
		// windows-1250 but an incomplete sequence in UTF-8.
		const [parsed] = parse(
			v21('N;CHARSET=windows-1250;ENCODING=QUOTED-PRINTABLE:Nov=E1k;Jan;;;\r\nEMAIL:jan@x.cz')
		);

		expect(parsed.contact.surname).toBe(NOVAK);
	});

	it('joins a soft line break, which wraps long encoded values', () => {
		const [parsed] = parse(
			v21('FN:Jan\r\nEMAIL:jan@x.cz\r\nNOTE;ENCODING=QUOTED-PRINTABLE:Pratel=\r\nsky pozdrav')
		);

		expect(parsed.contact.note).toBe('Pratelsky pozdrav');
	});

	it('leaves an invalid escape verbatim rather than dropping it', () => {
		// Same convention as the RFC 6350 text unescape: an undefined sequence
		// keeps its characters instead of being silently swallowed.
		const [parsed] = parse(
			v21('FN:Jan\r\nEMAIL:jan@x.cz\r\nNOTE;ENCODING=QUOTED-PRINTABLE:100=ZZ%')
		);

		expect(parsed.contact.note).toBe('100=ZZ%');
	});

	it('leaves a bare equals alone when the property declares no encoding', () => {
		const [parsed] = parse(card('FN:Jan\r\nEMAIL:jan@x.cz\r\nNOTE:a=C3=A1b'));

		expect(parsed.contact.note).toBe('a=C3=A1b');
	});

	it('does not join the line after base64 padding', () => {
		// Base64 pads with `=` at the end of a line too. Treating that as a soft
		// break would swallow the property that follows — here, the only address.
		const [parsed] = parse(card('FN:Jan\r\nPHOTO;ENCODING=BASE64:AAAA=\r\nEMAIL:jan@x.cz'));

		expect(parsed.contact.emails).toEqual([{ email: 'jan@x.cz', label: null }]);
	});
});
