import { describe, expect, it } from 'vitest';
import { contactCreateHref, contactEditHref, readContactPrefill, readReturnTo } from './prefill.js';

/*
 * These params travel in the URL, so they are user input in the same sense a
 * bookmark is: hand-editable, and pointed at a form that then navigates
 * somewhere on their say-so. What the reader must do is drop anything it cannot
 * vouch for rather than pass it on.
 */

function params(query: string): URLSearchParams {
	return new URLSearchParams(query);
}

describe('readContactPrefill', () => {
	it('reads the address and both name fields', () => {
		expect(readContactPrefill(params('email=jana@example.com&name=Jana&surname=Novak'))).toEqual({
			email: 'jana@example.com',
			name: 'Jana',
			surname: 'Novak'
		});
	});

	it('is null without a usable address — the form then opens empty', () => {
		expect(readContactPrefill(params('name=Jana'))).toBeNull();
		expect(readContactPrefill(params('email=not-an-address&name=Jana'))).toBeNull();
	});

	it('keeps the address but drops blank names', () => {
		expect(readContactPrefill(params('email=jana@example.com&name=%20&surname='))).toEqual({
			email: 'jana@example.com',
			name: null,
			surname: null
		});
	});

	it('truncates a name to what the contact DTO accepts', () => {
		const long = 'a'.repeat(400);
		expect(readContactPrefill(params(`email=jana@example.com&name=${long}`))?.name).toHaveLength(
			255
		);
	});
});

describe('readReturnTo', () => {
	it('accepts a message route', () => {
		expect(readReturnTo(params('returnTo=/mail/1/INBOX/msg-01'))).toBe('/mail/1/INBOX/msg-01');
	});

	it('refuses anything else, including an off-site target', () => {
		expect(readReturnTo(params('returnTo=/settings'))).toBeNull();
		expect(readReturnTo(params('returnTo=//example.com'))).toBeNull();
		expect(readReturnTo(params('returnTo=https://example.com'))).toBeNull();
		expect(readReturnTo(params(''))).toBeNull();
	});
});

describe('href builders', () => {
	it('carries the seed and the way back into the create form', () => {
		const href = contactCreateHref(
			{ email: 'jana@example.com', name: 'Jana', surname: 'Novak' },
			'/mail/1/INBOX/msg-01'
		);
		const round = new URL(href, 'http://localhost');

		expect(round.pathname).toBe('/contacts');
		expect(readContactPrefill(round.searchParams)).toEqual({
			email: 'jana@example.com',
			name: 'Jana',
			surname: 'Novak'
		});
		expect(readReturnTo(round.searchParams)).toBe('/mail/1/INBOX/msg-01');
		expect(round.searchParams.get('create')).toBe('1');
	});

	it('omits the name fields the sender did not carry', () => {
		const href = contactCreateHref({ email: 'jana@example.com', name: null, surname: null });
		const round = new URL(href, 'http://localhost');

		expect(round.searchParams.has('name')).toBe(false);
		expect(round.searchParams.has('surname')).toBe(false);
		expect(round.searchParams.has('returnTo')).toBe(false);
	});

	it('points at the existing contact with the same way back', () => {
		const round = new URL(contactEditHref(7, '/mail/1/INBOX/msg-01'), 'http://localhost');

		expect(round.searchParams.get('edit')).toBe('7');
		expect(readReturnTo(round.searchParams)).toBe('/mail/1/INBOX/msg-01');
	});
});
