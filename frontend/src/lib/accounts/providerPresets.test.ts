import { describe, expect, it } from 'vitest';
import { KNOWN_PRESETS, passwordForPreset, presetForProvider } from './providerPresets.js';
import type { MailProviderResponse } from '#lib/types.js';

function provider(overrides: Partial<MailProviderResponse>): MailProviderResponse {
	return {
		id: 1,
		name: 'Example',
		imapHost: 'imap.example.com',
		imapPort: 993,
		imapSsl: true,
		smtpHost: 'smtp.example.com',
		smtpPort: 465,
		smtpSsl: true,
		domains: 'example.com',
		...overrides
	};
}

describe('presetForProvider', () => {
	it('pairs OAuth providers on oauth2RegistrationId, not the display name', () => {
		expect(
			presetForProvider(provider({ name: 'Google', oauth2RegistrationId: 'google' }))?.key
		).toBe('gmail');
		expect(
			presetForProvider(provider({ name: 'Microsoft', oauth2RegistrationId: 'microsoft' }))?.key
		).toBe('outlook');
	});

	it('ignores the display name for OAuth matches (rename-proof)', () => {
		// A renamed/relabelled template still resolves via the stable registration id.
		expect(
			presetForProvider(provider({ name: 'Anything At All', oauth2RegistrationId: 'google' }))?.key
		).toBe('gmail');
	});

	it('falls back to the template name for password-only providers', () => {
		expect(presetForProvider(provider({ name: 'Seznam', oauth2RegistrationId: null }))?.key).toBe(
			'seznam'
		);
		expect(presetForProvider(provider({ name: 'seznam' }))?.key).toBe('seznam');
	});

	it('returns null for unknown providers and nullish input', () => {
		expect(presetForProvider(provider({ name: 'Some Custom Host' }))).toBeNull();
		expect(presetForProvider(null)).toBeNull();
		expect(presetForProvider(undefined)).toBeNull();
	});
});

describe('passwordForPreset', () => {
	const preset = (key: string) => KNOWN_PRESETS.find((p) => p.key === key)!;

	it('drops the spaces Google shows its app password with, and any other whitespace', () => {
		expect(passwordForPreset(preset('gmail'), 'abcd efgh ijkl mnop')).toBe('abcdefghijklmnop');
		expect(
			passwordForPreset(preset('gmail'), ` abcd\tefgh${String.fromCharCode(0xa0)}ijkl mnop\n`)
		).toBe('abcdefghijklmnop');
	});

	it('keeps the password as typed where it may be an account password', () => {
		expect(passwordForPreset(preset('seznam'), 'moje heslo ')).toBe('moje heslo ');
		expect(passwordForPreset(preset('custom'), 'moje heslo ')).toBe('moje heslo ');
		expect(passwordForPreset(null, 'moje heslo ')).toBe('moje heslo ');
	});
});
