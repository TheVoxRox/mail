/**
 * Whether saving an account has to prove the login first. Extracted from
 * `AccountForm.svelte`, where the rule was reachable only by saving the form.
 *
 * Before the login was checked on save, a wrong password produced a green
 * "account added" toast and an "Active" badge in Settings, and the first sign
 * of trouble was the mail view failing minutes later. The check is a live IMAP
 * and SMTP login, so it runs only for changes that can affect the login.
 */
import type { AccountResponse } from '#lib/types.js';

/** The stored account an edit starts from, as far as the login is concerned. */
export type StoredLogin = Pick<
	AccountResponse,
	| 'email'
	| 'username'
	| 'providerId'
	| 'imapHost'
	| 'imapPort'
	| 'imapUseSsl'
	| 'smtpHost'
	| 'smtpPort'
	| 'smtpUseSsl'
>;

/** What the form is about to save. */
export interface EditedLogin {
	active: boolean;
	/** The password as it will be sent; empty keeps the stored one. */
	password: string;
	email: string;
	username: string;
	serverMode: 'provider' | 'custom';
	providerId: number | null;
	imapHost: string;
	imapPort: number | null;
	imapUseSsl: boolean;
	smtpHost: string;
	smtpPort: number | null;
	smtpUseSsl: boolean;
}

/**
 * Only changes that can affect the login qualify — a new password, a different
 * address or username, or another server. A new account (`stored` is `null`)
 * always does. Two deliberate exemptions:
 *
 * - Unticking "account is active": switching a broken account off is exactly
 *   what a user does when it keeps failing, and a check that must succeed
 *   would make the failure impossible to switch off.
 * - Everything unrelated (signature, display name, account label): an
 *   unreachable server must not hold a signature edit hostage.
 */
export function shouldVerifyOnSave(stored: StoredLogin | null, edited: EditedLogin): boolean {
	if (!edited.active) return false;
	if (!stored) return true;
	if (edited.password) return true;
	if (edited.email.trim() !== stored.email || edited.username.trim() !== stored.username) {
		return true;
	}

	const wasCustom = stored.providerId == null;
	if ((edited.serverMode === 'custom') !== wasCustom) return true;
	if (edited.serverMode === 'provider') return edited.providerId !== stored.providerId;
	return (
		edited.imapHost.trim() !== stored.imapHost ||
		edited.imapPort !== stored.imapPort ||
		edited.imapUseSsl !== stored.imapUseSsl ||
		edited.smtpHost.trim() !== stored.smtpHost ||
		edited.smtpPort !== stored.smtpPort ||
		edited.smtpUseSsl !== stored.smtpUseSsl
	);
}
