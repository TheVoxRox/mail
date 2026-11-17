import { describe, expect, it } from 'vitest';
import { shouldVerifyOnSave, type EditedLogin, type StoredLogin } from './verifyOnSave.js';

/*
 * When saving an account costs a live login. Inside AccountForm this table was
 * reachable only by saving the form against the mock backend, one row per run.
 */

const PROVIDER_ACCOUNT: StoredLogin = {
	email: 'jana@seznam.cz',
	username: 'jana@seznam.cz',
	providerId: 5,
	imapHost: 'imap.seznam.cz',
	imapPort: 993,
	imapUseSsl: true,
	smtpHost: 'smtp.seznam.cz',
	smtpPort: 465,
	smtpUseSsl: true
};

const CUSTOM_ACCOUNT: StoredLogin = {
	...PROVIDER_ACCOUNT,
	email: 'me@example.com',
	username: 'me@example.com',
	providerId: null,
	imapHost: 'mail.example.com',
	smtpHost: 'mail.example.com'
};

/** The form as it opens for `stored`, nothing changed yet. */
function unchanged(stored: StoredLogin): EditedLogin {
	return {
		active: true,
		password: '',
		email: stored.email,
		username: stored.username,
		serverMode: stored.providerId == null ? 'custom' : 'provider',
		providerId: stored.providerId,
		imapHost: stored.imapHost,
		imapPort: stored.imapPort,
		imapUseSsl: stored.imapUseSsl,
		smtpHost: stored.smtpHost,
		smtpPort: stored.smtpPort,
		smtpUseSsl: stored.smtpUseSsl
	};
}

describe('shouldVerifyOnSave', () => {
	it('checks a new account', () => {
		expect(shouldVerifyOnSave(null, unchanged(PROVIDER_ACCOUNT))).toBe(true);
	});

	it('does not check an edit that changes nothing the login reads', () => {
		// Signature, display name and the account label are not part of the input
		// at all: an unreachable server must not hold such an edit hostage.
		expect(shouldVerifyOnSave(PROVIDER_ACCOUNT, unchanged(PROVIDER_ACCOUNT))).toBe(false);
		expect(shouldVerifyOnSave(CUSTOM_ACCOUNT, unchanged(CUSTOM_ACCOUNT))).toBe(false);
	});

	it('never checks an account being switched off, whatever else changed', () => {
		// Switching a failing account off is what a user does about the failure.
		const edited = { ...unchanged(PROVIDER_ACCOUNT), active: false, password: 'new-secret' };
		expect(shouldVerifyOnSave(PROVIDER_ACCOUNT, edited)).toBe(false);
		expect(shouldVerifyOnSave(null, { ...unchanged(PROVIDER_ACCOUNT), active: false })).toBe(false);
	});

	it.each([
		['a new password', { password: 'new-secret' }],
		['another address', { email: 'jana.nova@seznam.cz' }],
		['another username', { username: 'jana' }],
		['another provider', { providerId: 6 }],
		['a move to custom servers', { serverMode: 'custom' as const }]
	])('checks %s', (_label, change) => {
		expect(
			shouldVerifyOnSave(PROVIDER_ACCOUNT, { ...unchanged(PROVIDER_ACCOUNT), ...change })
		).toBe(true);
	});

	it('ignores whitespace around the address and the username', () => {
		const edited = {
			...unchanged(PROVIDER_ACCOUNT),
			email: ` ${PROVIDER_ACCOUNT.email} `,
			username: `${PROVIDER_ACCOUNT.username}  `
		};
		expect(shouldVerifyOnSave(PROVIDER_ACCOUNT, edited)).toBe(false);
	});

	it('reads the server fields only on custom servers', () => {
		// On a provider the servers are the provider's; stale custom fields left
		// in the form from an earlier switch are not what gets saved.
		const edited = { ...unchanged(PROVIDER_ACCOUNT), imapHost: 'leftover.example.com' };
		expect(shouldVerifyOnSave(PROVIDER_ACCOUNT, edited)).toBe(false);
	});

	it('checks a move from custom servers to a provider', () => {
		const edited = { ...unchanged(CUSTOM_ACCOUNT), serverMode: 'provider' as const, providerId: 5 };
		expect(shouldVerifyOnSave(CUSTOM_ACCOUNT, edited)).toBe(true);
	});

	it.each([
		['IMAP host', { imapHost: 'imap.example.com' }],
		['IMAP port', { imapPort: 143 }],
		['IMAP TLS', { imapUseSsl: false }],
		['SMTP host', { smtpHost: 'smtp.example.com' }],
		['SMTP port', { smtpPort: 587 }],
		['SMTP TLS', { smtpUseSsl: false }]
	])('checks a changed %s on custom servers', (_label, change) => {
		expect(shouldVerifyOnSave(CUSTOM_ACCOUNT, { ...unchanged(CUSTOM_ACCOUNT), ...change })).toBe(
			true
		);
	});

	it('ignores whitespace around a custom host', () => {
		const edited = { ...unchanged(CUSTOM_ACCOUNT), imapHost: ` ${CUSTOM_ACCOUNT.imapHost} ` };
		expect(shouldVerifyOnSave(CUSTOM_ACCOUNT, edited)).toBe(false);
	});
});
