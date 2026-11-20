import { strip, stripBlankLinesToNull } from '#lib/storedText.js';

/** The free-text fields of the account form, as the form holds them. */
export interface AccountTextFields {
	accountName: string;
	email: string;
	displayName: string;
	username: string;
	signature: string;
	imapHost: string;
	smtpHost: string;
}

/**
 * The account form's text in the form the backend stores it
 * (`#lib/storedText.js`), still as the form holds it, with an empty string for
 * none: the single-line fields stripped, the signature a block that keeps the
 * indentation of its first line. The password is not among them, as a space in
 * it is part of it.
 */
export function stripAccountText(fields: AccountTextFields): AccountTextFields {
	return {
		accountName: strip(fields.accountName),
		email: strip(fields.email),
		displayName: strip(fields.displayName),
		username: strip(fields.username),
		signature: stripBlankLinesToNull(fields.signature) ?? '',
		imapHost: strip(fields.imapHost),
		smtpHost: strip(fields.smtpHost)
	};
}
