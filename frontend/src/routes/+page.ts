/**
 * Redirect out of `/` before it can be landed on.
 *
 * `/` decides which folder Mail opens on, and the decision used to be made by
 * the page component — so the app navigated *to* `/`, rendered it, moved focus
 * into its `<main>`, and only then navigated on. A screen reader read the stop
 * out: `<main>` is named after the route title, which is the accountless
 * welcome screen's ("Posta - Vitejte"), so the user heard a screen they were
 * not on and were never going to see. Measured on 2026-09-03 over an NVDA
 * session, on the ordinary return from Settings to Mail as much as on Esc out
 * of the composer; eight call sites navigate here meaning "go to Mail", which
 * is why the fix belongs to `/` and not to any one of them.
 *
 * `redirect` from a load runs *during* the navigation: there is no second
 * navigation, no `afterNavigate` for `/`, and nothing to announce but the
 * folder the user asked for.
 *
 * Deliberately synchronous, and deliberately cache-only. Fetching the folder
 * list here would be the more thorough redirect, but a load that awaits leaves
 * the user on the previous page with no feedback for as long as the request
 * takes - measured at up to 6.1 s while a sync pass held the IMAP lock. When
 * the cache is cold the component below keeps the job, because then the wait is
 * real and saying so beats saying nothing. Every in-app return to Mail finds it
 * warm; a cold one only happens before any folder list has been loaded, which
 * is the boot the shell is already narrating.
 */
import { redirect } from '@sveltejs/kit';
import { get } from 'svelte/store';
import { accountsState, resolvedActiveAccountId, setActiveAccount } from '$lib/stores/accounts.js';
import { folders } from '$lib/stores/folders.js';
import { folderHref, pickEntryFolder } from '$lib/mail/entryFolder.js';
import type { PageLoad } from './$types.js';

export const load: PageLoad = () => {
	const accounts = get(accountsState);
	// Still booting, or genuinely nowhere to go: the welcome card is a real
	// destination and has to be rendered, not redirected past.
	if (accounts.status !== 'ready' || accounts.accounts.length === 0) return {};

	const accountId = get(resolvedActiveAccountId) ?? accounts.accounts[0].id;
	// Before reading `folders`, which is derived on the active account - and so
	// the rest of the app agrees on which mailbox this is.
	setActiveAccount(accountId);

	const entry = pickEntryFolder(get(folders));
	if (!entry) return {};

	throw redirect(307, folderHref(accountId, entry));
};
