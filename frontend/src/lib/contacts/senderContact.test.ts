import { describe, expect, it } from 'vitest';
import { splitDisplayName } from './displayName.js';
import { senderContactSeed } from './senderContact.js';

/*
 * The seed decides what a contact form opened from a message starts with, and
 * its input is a header the sender wrote, so the cases that matter are the ones
 * where the header is not the tidy `"Name" <address>` shape.
 */

describe('splitDisplayName', () => {
	it('splits on the last space', () => {
		expect(splitDisplayName('Jan Novak')).toEqual({ name: 'Jan', surname: 'Novak' });
	});

	it('keeps several given names together', () => {
		expect(splitDisplayName('Jan Evangelista Novak')).toEqual({
			name: 'Jan Evangelista',
			surname: 'Novak'
		});
	});

	it('reads a single word as the given name', () => {
		expect(splitDisplayName('Jana')).toEqual({ name: 'Jana', surname: null });
	});

	it('is empty for an empty string', () => {
		expect(splitDisplayName('   ')).toEqual({ name: null, surname: null });
	});
});

describe('senderContactSeed', () => {
	it('takes the address from the angle brackets and the name from the rest', () => {
		expect(senderContactSeed('Jana Novak <jana@example.com>')).toEqual({
			email: 'jana@example.com',
			name: 'Jana',
			surname: 'Novak'
		});
	});

	it('accepts a bare address and leaves both name fields empty', () => {
		// The local part must not become a given name — "jana" is not what the
		// sender is called, it is half of their address.
		expect(senderContactSeed('jana@example.com')).toEqual({
			email: 'jana@example.com',
			name: null,
			surname: null
		});
	});

	it('handles a single-word display name', () => {
		expect(senderContactSeed('Podpora <podpora@example.com>')).toEqual({
			email: 'podpora@example.com',
			name: 'Podpora',
			surname: null
		});
	});

	it('tolerates missing spacing around the brackets', () => {
		expect(senderContactSeed('Jana Novak<jana@example.com>')?.email).toBe('jana@example.com');
	});

	it('refuses a sender that is not an address', () => {
		// What `MessageMapper.displaySender` puts there when the header is blank.
		expect(senderContactSeed('(unknown sender)')).toBeNull();
		expect(senderContactSeed('')).toBeNull();
		expect(senderContactSeed(null)).toBeNull();
	});

	it('refuses a malformed address inside the brackets', () => {
		expect(senderContactSeed('Jana Novak <jana@>')).toBeNull();
	});
});
