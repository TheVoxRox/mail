const ADDRESS_SEPARATOR = /[,;\n]+/;

export function parseAddressList(value: string): string[] {
	return value
		.split(ADDRESS_SEPARATOR)
		.map((item) => item.trim())
		.filter(Boolean);
}

export function serializeAddressList(addresses: string[]): string {
	return addresses.join(', ');
}

/**
 * An email address by the rule docs/DECISIONS.md records under "An email
 * address is judged by one rule, in the form and in the backend" — the
 * backend's `EmailAddresses` written out again, and the two held together by
 * the vectors both test suites run
 * (backend/src/test/resources/text/email-address-vectors.json).
 *
 * A local part of dot-separated runs of letters of any script, combining marks,
 * decimal digits and the RFC 5322 symbols, at most 64 characters; a domain of
 * at least two dot-separated labels of letters and decimal digits, a hyphen
 * inside a label, each label at most 63 characters; the whole at most 254
 * characters (`MAX_ADDRESS_LENGTH`). The `u` flag makes `\p{…}` and the counted
 * repeats work on code points, as Java's regex does.
 */
const EMAIL_ADDRESS =
	/^(?=[^@]{1,64}@)[\p{L}\p{M}\p{Nd}!#$%&'*+/=?^_`{|}~-]+(?:\.[\p{L}\p{M}\p{Nd}!#$%&'*+/=?^_`{|}~-]+)*@(?:[\p{L}\p{Nd}](?:[\p{L}\p{Nd}-]{0,61}[\p{L}\p{Nd}])?\.)+[\p{L}\p{Nd}](?:[\p{L}\p{Nd}-]{0,61}[\p{L}\p{Nd}])?$/u;

/**
 * The longest address an SMTP path carries: RFC 5321's 256 octets less the two
 * angle brackets, counted in UTF-16 units as the backend's `length()` is. The
 * backend checks it before its match, where a few thousand domain labels
 * overflowed the regex engine's stack (API surface audit, A8); here it keeps the
 * two answers the same.
 */
const MAX_ADDRESS_LENGTH = 254;

/**
 * Whether the value, without the whitespace around it, is an email address.
 * A comma, semicolon, angle bracket or space is never part of one, so a
 * separator cannot smuggle a second recipient into a single address.
 */
export function isValidEmailAddress(value: string): boolean {
	const address = value.trim();
	return address.length <= MAX_ADDRESS_LENGTH && EMAIL_ADDRESS.test(address);
}

export function invalidAddressList(value: string): string[] {
	return parseAddressList(value).filter((address) => !isValidEmailAddress(address));
}
