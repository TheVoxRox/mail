/**
 * Pure account-form validation — extracted from `AccountForm.svelte`. Returns
 * i18n keys, not translated text, so the logic is not tied to the Svelte
 * store from `#lib/i18n/index.js` and can be unit-tested.
 *
 * Every field the user can get wrong is checked here, before anything goes to
 * the backend. The backend checks the same fields, but its answer is one line
 * naming them by their Java names ("Chyba ve vstupech: accountName: …"), with
 * no field marked invalid and focus left on the button (WCAG 3.3.1).
 */
import { isValidEmailAddress } from '#lib/compose/addresses.js';

export type CustomFieldKey = 'imapHost' | 'imapPort' | 'smtpHost' | 'smtpPort';

/** Every field the form can mark invalid, custom server fields included. */
export type AccountFieldKey =
	'email' | 'accountName' | 'provider' | CustomFieldKey | 'username' | 'password';

export interface CustomServerInput {
	imapHost: string;
	imapPort: number | null;
	smtpHost: string;
	smtpPort: number | null;
}

export interface AccountFieldError {
	field: AccountFieldKey;
	/** Key for `$_()` — the component still has to translate it. */
	messageKey: string;
}

export interface AccountFormInput extends CustomServerInput {
	/** Saving checks every field; a connection test what it sends, so not the account name. */
	purpose: 'save' | 'test';
	/** The wizard's compact form shows the password and nothing else to fill in. */
	compact: boolean;
	/** An edit keeps the stored password when the field is left empty. */
	passwordRequired: boolean;
	serverMode: 'provider' | 'custom';
	providerId: number | null;
	email: string;
	accountName: string;
	username: string;
	password: string;
}

const MAX_HOST_LENGTH = 255;
const MIN_PORT = 1;
const MAX_PORT = 65535;

function invalidPort(port: number | null): boolean {
	return port == null || !Number.isInteger(port) || port < MIN_PORT || port > MAX_PORT;
}

function hostError(host: string, requiredKey: string): string | null {
	if (!host.trim()) return requiredKey;
	if (host.length > MAX_HOST_LENGTH) return 'accounts.form.errorHostTooLong';
	return null;
}

/** One error per custom server field at most, in the order the fields appear. */
export function validateCustomServer(input: CustomServerInput): AccountFieldError[] {
	const checks: [CustomFieldKey, string | null][] = [
		['imapHost', hostError(input.imapHost, 'accounts.form.errorImapHostRequired')],
		['imapPort', invalidPort(input.imapPort) ? 'accounts.form.errorPortRange' : null],
		['smtpHost', hostError(input.smtpHost, 'accounts.form.errorSmtpHostRequired')],
		['smtpPort', invalidPort(input.smtpPort) ? 'accounts.form.errorPortRange' : null]
	];
	return checks.flatMap(([field, messageKey]) => (messageKey ? [{ field, messageKey }] : []));
}

/**
 * Every error the form can tell the user about now, at most one per field, in
 * the order the fields appear, so the first is the one to focus. The compact
 * form leaves out what it does not show: the wizard has already checked the
 * address and the provider comes with the detected preset.
 */
export function validateAccountForm(input: AccountFormInput): AccountFieldError[] {
	const errors: AccountFieldError[] = [];
	const add = (field: AccountFieldKey, messageKey: string) => errors.push({ field, messageKey });

	if (!input.compact) {
		if (!input.email.trim()) add('email', 'accounts.form.errorEmailRequired');
		else if (!isValidEmailAddress(input.email)) add('email', 'accounts.form.errorEmailInvalid');
		if (input.purpose === 'save' && !input.accountName.trim()) {
			add('accountName', 'accounts.form.errorAccountNameRequired');
		}
	}
	if (input.serverMode === 'provider') {
		if (input.providerId == null) add('provider', 'accounts.form.errorSelectProvider');
	} else {
		errors.push(...validateCustomServer(input));
	}
	if (!input.compact && !input.username.trim()) {
		add('username', 'accounts.form.errorUsernameRequired');
	}
	if (input.passwordRequired && !input.password) {
		add(
			'password',
			input.purpose === 'test'
				? 'accounts.form.errorPasswordRequiredForTest'
				: 'accounts.form.errorPasswordRequired'
		);
	}
	return errors;
}

/** The id of the control each field key marks, for focus and for its error. */
export const FIELD_INPUT_IDS: Record<AccountFieldKey, string> = {
	email: 'acc-email',
	accountName: 'acc-accountName',
	provider: 'acc-provider',
	imapHost: 'acc-imap-host',
	imapPort: 'acc-imap-port',
	smtpHost: 'acc-smtp-host',
	smtpPort: 'acc-smtp-port',
	username: 'acc-username',
	password: 'acc-password'
};
