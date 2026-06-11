/**
 * Pure account-form validation — extracted from `AccountForm.svelte`. Returns
 * i18n keys, not translated text, so the logic is not tied to the Svelte
 * store from `$lib/i18n` and can be unit-tested.
 */
export type CustomFieldKey = 'imapHost' | 'imapPort' | 'smtpHost' | 'smtpPort';

export interface CustomServerInput {
	imapHost: string;
	imapPort: number | null;
	smtpHost: string;
	smtpPort: number | null;
}

export interface CustomFieldError {
	field: CustomFieldKey;
	/** Key for `$_()` — the component still has to translate it. */
	messageKey: string;
}

const MAX_HOST_LENGTH = 255;
const MIN_PORT = 1;
const MAX_PORT = 65535;

function invalidPort(port: number | null): boolean {
	return port == null || !Number.isInteger(port) || port < MIN_PORT || port > MAX_PORT;
}

export function validateCustomServer(input: CustomServerInput): CustomFieldError | null {
	if (!input.imapHost.trim()) {
		return { field: 'imapHost', messageKey: 'accounts.form.errorImapHostRequired' };
	}
	if (input.imapHost.length > MAX_HOST_LENGTH) {
		return { field: 'imapHost', messageKey: 'accounts.form.errorHostTooLong' };
	}
	if (invalidPort(input.imapPort)) {
		return { field: 'imapPort', messageKey: 'accounts.form.errorPortRange' };
	}
	if (!input.smtpHost.trim()) {
		return { field: 'smtpHost', messageKey: 'accounts.form.errorSmtpHostRequired' };
	}
	if (input.smtpHost.length > MAX_HOST_LENGTH) {
		return { field: 'smtpHost', messageKey: 'accounts.form.errorHostTooLong' };
	}
	if (invalidPort(input.smtpPort)) {
		return { field: 'smtpPort', messageKey: 'accounts.form.errorPortRange' };
	}
	return null;
}

/** Maps an error field to the HTML input id (for focus after validation). */
export const FIELD_INPUT_IDS: Record<CustomFieldKey, string> = {
	imapHost: 'acc-imap-host',
	imapPort: 'acc-imap-port',
	smtpHost: 'acc-smtp-host',
	smtpPort: 'acc-smtp-port'
};
