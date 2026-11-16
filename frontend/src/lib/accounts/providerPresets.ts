/**
 * Mail provider classification for the wizard UX on `/settings/accounts/new`.
 *
 * The backend seeds named templates (Google, Seznam, Microsoft). The wizard
 * decides:
 *   - what flow to create the account with (`oauth` vs `imap`),
 *   - what the password over IMAP is, if there is one: an App Password always
 *     for Google, one only with two-factor authentication for Seznam, and none
 *     at all for Microsoft, which signs in through OAuth only,
 *   - how to match the provider from `/accounts/providers` to a preset:
 *     OAuth providers pair on the stable `oauth2RegistrationId`, others
 *     fall back to the template `name`. The display name is never the key.
 *
 * The order of `KNOWN_PRESETS` drives the order of cards in the wizard.
 */

import type { MailProviderResponse } from '#lib/types.js';

type ProviderFlow = 'oauth' | 'imap';

export interface ProviderPreset {
	/** Stable key used as an i18n token and in URL fallback. */
	key: 'gmail' | 'outlook' | 'seznam' | 'custom';
	/** Value of `MailProviderResponse.name` that pairs the preset. `null` for `custom`. */
	backendName: string | null;
	/** Spring Security OAuth2 registration id for the backend login flow. */
	oauth2RegistrationId?: string;
	/** How the account is created. `custom` always uses `imap`. */
	flow: ProviderFlow;
	/**
	 * What the password field takes when the account signs in over IMAP:
	 * `account` (the account password), `app` (always an App Password — Gmail),
	 * `appWithTwoFactor` (an App Password only once two-factor authentication is
	 * on — Seznam, whose plain accounts sign in with the account password) or
	 * `none`. `none` means the provider has no password sign-in at all, so the
	 * wizard does not offer IMAP with a password as an alternative to its OAuth
	 * sign-in: Microsoft switched basic authentication off for personal accounts
	 * on 2024-09-16, and for IMAP in every Microsoft 365 tenant by the end of
	 * 2022, where no admin can switch it back on (docs/DECISIONS.md).
	 */
	imapPassword: 'account' | 'app' | 'appWithTwoFactor' | 'none';
	/** URL of the guide for creating an App Password (anchor in provider docs). */
	appPasswordHelpUrl?: string;
}

export const KNOWN_PRESETS: ProviderPreset[] = [
	{
		key: 'gmail',
		backendName: 'Google',
		oauth2RegistrationId: 'google',
		flow: 'oauth',
		imapPassword: 'app',
		appPasswordHelpUrl: 'https://support.google.com/accounts/answer/185833'
	},
	{
		key: 'outlook',
		backendName: 'Microsoft',
		oauth2RegistrationId: 'microsoft',
		flow: 'oauth',
		imapPassword: 'none'
	},
	{
		key: 'seznam',
		backendName: 'Seznam',
		flow: 'imap',
		imapPassword: 'appWithTwoFactor',
		appPasswordHelpUrl: 'https://o-seznam.cz/napoveda/ucet/dvoufazove-overeni/postovni-programy/'
	},
	{
		key: 'custom',
		backendName: null,
		flow: 'imap',
		imapPassword: 'account'
	}
];

/**
 * Finds the preset for a backend `MailProviderResponse`. OAuth providers pair on
 * the stable `oauth2RegistrationId` (display-name independent); password-only
 * templates (e.g. Seznam) fall back to a case-insensitive `name` match.
 */
export function presetForProvider(
	provider: Pick<MailProviderResponse, 'name' | 'oauth2RegistrationId'> | null | undefined
): ProviderPreset | null {
	if (!provider) return null;
	const regId = provider.oauth2RegistrationId?.trim().toLowerCase();
	if (regId) {
		const byOauth = KNOWN_PRESETS.find(
			(preset) => preset.oauth2RegistrationId?.toLowerCase() === regId
		);
		if (byOauth) return byOauth;
	}
	const target = provider.name.trim().toLowerCase();
	return (
		KNOWN_PRESETS.find(
			(preset) => preset.backendName != null && preset.backendName.toLowerCase() === target
		) ?? null
	);
}
