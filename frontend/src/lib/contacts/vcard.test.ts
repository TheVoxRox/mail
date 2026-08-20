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
