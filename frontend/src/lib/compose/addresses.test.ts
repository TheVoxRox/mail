import { describe, expect, it } from 'vitest';
import {
	parseAddressList,
	serializeAddressList,
	isValidEmailAddress,
	invalidAddressList
} from './addresses.js';

/**
 * Address-list helpers feed the To/Cc/Bcc inputs of the compose form. The
 * regex deliberately rejects `,;<>` and whitespace inside the local-part to
 * keep separator characters from leaking into a single address — a parsing
 * mistake here would let one recipient field smuggle two recipients past the
 * UI's validation and into the SMTP envelope.
 */

describe('parseAddressList — split on , ; or newline', () => {
	it('returns an empty array for an empty string', () => {
		expect(parseAddressList('')).toEqual([]);
	});

	it('returns the single address verbatim with no separator present', () => {
		expect(parseAddressList('a@b.cz')).toEqual(['a@b.cz']);
	});

	it('splits on commas', () => {
		expect(parseAddressList('a@b.cz, c@d.cz')).toEqual(['a@b.cz', 'c@d.cz']);
	});

	it('splits on semicolons (paste from Outlook-style clipboard)', () => {
		expect(parseAddressList('a@b.cz; c@d.cz')).toEqual(['a@b.cz', 'c@d.cz']);
	});

	it('splits on newlines (paste from a column)', () => {
		expect(parseAddressList('a@b.cz\nc@d.cz')).toEqual(['a@b.cz', 'c@d.cz']);
	});

	it('handles mixed separators in a single value', () => {
		expect(parseAddressList('a@b.cz,c@d.cz; e@f.cz\ng@h.cz')).toEqual([
			'a@b.cz',
			'c@d.cz',
			'e@f.cz',
			'g@h.cz'
		]);
	});

	it('collapses consecutive separators and discards empty entries', () => {
		expect(parseAddressList('a@b.cz,,, ,;\n c@d.cz')).toEqual(['a@b.cz', 'c@d.cz']);
	});

	it('trims surrounding whitespace from each entry', () => {
		expect(parseAddressList('  a@b.cz  ,  c@d.cz  ')).toEqual(['a@b.cz', 'c@d.cz']);
	});

	it('returns [] for a string that is only whitespace and separators', () => {
		expect(parseAddressList('  ,  ; \n  ')).toEqual([]);
	});
});

describe('serializeAddressList — render back into a comma-space string', () => {
	it('returns an empty string for an empty list', () => {
		expect(serializeAddressList([])).toBe('');
	});

	it('returns a single address verbatim', () => {
		expect(serializeAddressList(['a@b.cz'])).toBe('a@b.cz');
	});

	it('joins multiple addresses with ", "', () => {
		expect(serializeAddressList(['a@b.cz', 'c@d.cz'])).toBe('a@b.cz, c@d.cz');
	});

	it('serialize ∘ parse round-trip preserves the address list', () => {
		const input = 'a@b.cz, c@d.cz, e@f.cz';
		expect(serializeAddressList(parseAddressList(input))).toBe(input);
	});
});

describe('isValidEmailAddress — local@domain.tld with separator characters rejected', () => {
	it('accepts a simple address', () => {
		expect(isValidEmailAddress('user@example.com')).toBe(true);
	});

	it('accepts subdomains in the host part', () => {
		expect(isValidEmailAddress('user@mail.example.co.uk')).toBe(true);
	});

	it('trims surrounding whitespace before validating', () => {
		expect(isValidEmailAddress('  user@example.com  ')).toBe(true);
	});

	it('rejects empty input', () => {
		expect(isValidEmailAddress('')).toBe(false);
	});

	it('rejects whitespace-only input', () => {
		expect(isValidEmailAddress('   ')).toBe(false);
	});

	it('rejects a value without "@"', () => {
		expect(isValidEmailAddress('plainstring')).toBe(false);
	});

	it('rejects a leading "@" with no local-part', () => {
		expect(isValidEmailAddress('@example.com')).toBe(false);
	});

	it('rejects a trailing "@" with no domain', () => {
		expect(isValidEmailAddress('user@')).toBe(false);
	});

	it('rejects a host without a dot (no TLD)', () => {
		expect(isValidEmailAddress('user@example')).toBe(false);
	});

	it('rejects a trailing "." with empty TLD', () => {
		expect(isValidEmailAddress('user@example.')).toBe(false);
	});

	it('rejects a leading "." in the host', () => {
		expect(isValidEmailAddress('user@.com')).toBe(false);
	});

	it('rejects two consecutive "@" signs', () => {
		expect(isValidEmailAddress('user@@example.com')).toBe(false);
	});

	it('rejects whitespace inside the address', () => {
		expect(isValidEmailAddress('user name@example.com')).toBe(false);
		expect(isValidEmailAddress('user@exa mple.com')).toBe(false);
	});

	// Security boundary: the separator characters MUST be rejected, otherwise
	// they would smuggle a second recipient through the parse → validate flow.
	it('rejects "," inside the address (would smuggle a second recipient)', () => {
		expect(isValidEmailAddress('a,b@example.com')).toBe(false);
	});

	it('rejects ";" inside the address', () => {
		expect(isValidEmailAddress('a;b@example.com')).toBe(false);
	});

	it('rejects "<" or ">" (RFC 5322 angle-addr delimiters)', () => {
		expect(isValidEmailAddress('<user@example.com>')).toBe(false);
		expect(isValidEmailAddress('user<x@example.com')).toBe(false);
		expect(isValidEmailAddress('user>x@example.com')).toBe(false);
	});

	it('accepts internationalised local-part / domain (diacritics not in the deny list)', () => {
		expect(isValidEmailAddress('café@münchen.de')).toBe(true);
	});
});

describe('invalidAddressList — parse a list and return only the bad entries', () => {
	it('returns [] for an all-valid list', () => {
		expect(invalidAddressList('a@b.cz, c@d.cz')).toEqual([]);
	});

	it('returns [] for an empty input', () => {
		expect(invalidAddressList('')).toEqual([]);
	});

	it('returns all entries when none are valid', () => {
		expect(invalidAddressList('not-an-email; also-bad')).toEqual(['not-an-email', 'also-bad']);
	});

	it('separates the bad entries from the good in a mixed list', () => {
		expect(invalidAddressList('a@b.cz, plain, c@d.cz, @bad.cz')).toEqual(['plain', '@bad.cz']);
	});

	it('preserves the order in which invalid entries appear in the input', () => {
		expect(invalidAddressList('bad1, a@b.cz, bad2, c@d.cz, bad3')).toEqual([
			'bad1',
			'bad2',
			'bad3'
		]);
	});
});
