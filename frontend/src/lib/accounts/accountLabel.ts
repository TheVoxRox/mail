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
		return email;
	}
	return `${stripped}: ${email}`;
}
