/**
 * Mail provider classification for the wizard UX on `/settings/accounts/new`.
 *
 * The backend seeds named templates (Google, Seznam, Microsoft). The wizard
 * decides:
 *   - what flow to create the account with (`oauth` vs `imap`),
 *   - whether to show the App Password warning (Google/Microsoft),
 *   - how to match the provider from `/accounts/providers` to a preset:
 *     OAuth providers pair on the stable `oauth2RegistrationId`, others
 *     fall back to the template `name`. The display name is never the key.
 *
 * The order of `KNOWN_PRESETS` drives the order of cards in the wizard.
 */

import type { MailProviderResponse } from '$lib/types.js';

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
	/** Requires an App Password (e.g. Gmail/Outlook in IMAP mode). */
	requiresAppPassword: boolean;
	/** URL of the guide for creating an App Password (anchor in provider docs). */
	appPasswordHelpUrl?: string;
}

export const KNOWN_PRESETS: ProviderPreset[] = [
	{
		key: 'gmail',
		backendName: 'Google',
		oauth2RegistrationId: 'google',
		flow: 'oauth',
		requiresAppPassword: true,
		appPasswordHelpUrl: 'https://support.google.com/accounts/answer/185833'
	},
	{
		key: 'outlook',
		backendName: 'Microsoft',
		oauth2RegistrationId: 'microsoft',
		flow: 'oauth',
		requiresAppPassword: true,
		appPasswordHelpUrl:
			'https://support.microsoft.com/en-us/account-billing/5896ed9b-4263-e681-128a-a6f2979a7944'
	},
	{
		key: 'seznam',
		backendName: 'Seznam',
		flow: 'imap',
		requiresAppPassword: true,
		appPasswordHelpUrl: 'https://napoveda.seznam.cz/cz/email/heslo-aplikace/'
	},
	{
		key: 'custom',
		backendName: null,
		flow: 'imap',
		requiresAppPassword: false
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
