import { describe, expect, it } from 'vitest';
import { KNOWN_PRESETS, presetForProvider } from './providerPresets.js';
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

describe('KNOWN_PRESETS', () => {
	it('gives Microsoft no password sign-in, as the backend catalogue says', () => {
		const outlook = KNOWN_PRESETS.find((preset) => preset.key === 'outlook');
		expect(outlook?.flow).toBe('oauth');
		expect(outlook?.imapPassword).toBe('none');
	});

	it('links an app-password guide only where the password is one', () => {
		for (const preset of KNOWN_PRESETS) {
			const appPassword =
				preset.imapPassword === 'app' || preset.imapPassword === 'appWithTwoFactor';
			expect(preset.appPasswordHelpUrl != null, preset.key).toBe(appPassword);
		}
	});

	it('leaves no provider without a way in', () => {
		// A preset with no password sign-in has to have the other one.
		for (const preset of KNOWN_PRESETS.filter((p) => p.imapPassword === 'none')) {
			expect(preset.oauth2RegistrationId, preset.key).toBeTruthy();
		}
	});
});
