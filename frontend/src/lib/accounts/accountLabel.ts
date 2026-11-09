import type { AccountResponse } from '#lib/types.js';

/**
 * Builds the label shown for an account in the sidebar switcher
 * (AccountSwitcher.svelte) and anywhere else a one-line account identity is
 * needed.
 *
 * The account name is a free-text, user-editable field, so it may legitimately
 * contain the e-mail address or look like the old "<Provider>: <email>" naming
 * that pre-dates the domain-based default (see AccountForm.svelte and
 * AccountService#accountNameFromEmail). To avoid showing the address twice, the
 * name is normalised before being paired with the e-mail:
 *   - any embedded e-mail address is removed,
 *   - leading/trailing separator punctuation (`: , - – —`) is trimmed.
 *
 * If what remains is empty or merely repeats the e-mail (full address or local
 * part), only the e-mail is shown. Otherwise the name is paired with the e-mail
 * as "<name>: <email>" (e.g. "Outlook: info@outlook.com"). Clean domain-based
 * names ("Post", "Outlook") keep their stored value — the stripping is a no-op
 * for them but still normalises free-text and legacy "<Provider>: <email>"
 * names, so it is intended behaviour, not dead code.
 */
export function accountOptionLabel(accountName: string, email: string): string {
	const name = meaningfulAccountName(accountName, email);
	return name ? `${name}: ${email}` : email;
}

/**
 * The account as a sentence about new mail names it — the in-app toast and the
 * Windows notification: by its name alone, "Work", because the address read
 * out with every notice is noise and the user chose the name to tell their
 * accounts apart.
 *
 * The name alone is not always enough. One that only repeats the address says
 * nothing beyond it, so the address stands in. And the default name is the
 * capitalised domain (AccountForm.svelte), so two Gmail accounts both start
 * as "Gmail": when another account carries the same name, this one is named
 * the way the account switcher names it, "Gmail: jana@gmail.com".
 */
export function accountShortLabel(
	account: Pick<AccountResponse, 'id' | 'accountName' | 'email'>,
	accounts: readonly Pick<AccountResponse, 'id' | 'accountName' | 'email'>[]
): string {
	const name = meaningfulAccountName(account.accountName, account.email);
	if (!name) return account.email;
	const key = name.toLowerCase();
	const shared = accounts.some(
		(other) =>
			other.id !== account.id &&
			meaningfulAccountName(other.accountName, other.email)?.toLowerCase() === key
	);
	return shared ? `${name}: ${account.email}` : name;
}

/**
 * The account name with any embedded address and dangling separators removed,
 * or null when nothing is left that the address does not already say.
 */
function meaningfulAccountName(accountName: string, email: string): string | null {
	const stripped = accountName
		.replace(/\S+@\S+\.\S+/g, '')
		.replace(/[\s:,\-–—]+$/u, '')
		.replace(/^[\s:,\-–—]+/u, '')
		.trim();
	const localPart = email.split('@')[0]?.toLowerCase() ?? '';
	if (
		!stripped ||
		stripped.toLowerCase() === email.toLowerCase() ||
		stripped.toLowerCase() === localPart
	) {
		return null;
	}
	return stripped;
}
