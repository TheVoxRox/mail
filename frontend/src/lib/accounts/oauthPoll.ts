/**
 * Polls the backend for the account created by an external-browser OAuth login.
 *
 * The OAuth callback lands on the backend (loopback redirect), so the wizard has
 * no direct completion signal — it polls the account list until the account
 * appears. First-time consent can be slow (Testing-mode apps show extra
 * "unverified app" warning screens before the Gmail scope consent), so the
 * budget is generous and a final reconcile catches an account that is created
 * right as the budget expires — otherwise the user is told the login failed for
 * an account that actually exists.
 *
 * The account must be one the sign-in made, measured against the list as it
 * stood before the sign-in started: new, or a password account the sign-in took
 * over (the backend's upgrade). Matching by address alone found an account that
 * already had the address on the first poll, and the wizard reported it added
 * before the user had signed in at all.
 *
 * The address the user typed is preferred, not required: the browser lets them
 * sign in as anyone, and the backend adds the account of the identity they
 * signed in with. Waiting for the typed address alone ran the poll out for an
 * account that had been added, and told the user the sign-in was taking long.
 */

export const OAUTH_POLL_FAST_INTERVAL_MS = 2000;
export const OAUTH_POLL_SLOW_INTERVAL_MS = 5000;
export const OAUTH_POLL_FAST_ATTEMPTS = 15;
// ~10 minutes: 15 × 2s (30s) + 114 × 5s (570s). Was 69 (~5 min), which a slow
// first-time consent could narrowly outlast — see the final reconcile below.
export const OAUTH_POLL_MAX_ATTEMPTS = 129;

/** The account fields the poll reads. */
export interface OAuthPollAccount {
	id: number;
	email: string;
	/** Set once a sign-in owns the account; null for a password account. */
	oauth2Provider: string | null;
}

export interface OAuthPollOptions<A extends OAuthPollAccount> {
	/**
	 * Address the user typed; an account the sign-in made under it is preferred,
	 * matched case-insensitively, over one it made under another identity.
	 */
	email: string;
	/** The account list from before the sign-in started. */
	baseline: readonly A[];
	/** Fetches the current account list (one poll). Network errors are ignored. */
	listAccounts: () => Promise<A[]>;
	/** Resolves after the given delay; injected so tests can run instantly. */
	sleep: (ms: number) => Promise<void>;
	/** Returns false to abort polling (user cancelled / page left). Optional. */
	shouldContinue?: () => boolean;
}

/**
 * Resolves with the matching account once it appears, or `null` if the budget
 * (plus a final grace reconcile) is exhausted or polling is aborted.
 */
export async function pollForOAuthAccount<A extends OAuthPollAccount>(
	options: OAuthPollOptions<A>
): Promise<A | null> {
	const { email, baseline, listAccounts, sleep, shouldContinue } = options;
	const active = () => (shouldContinue ? shouldContinue() : true);
	const signedInBefore = new Set(baseline.filter(isSignedIn).map((a) => a.id));

	const findMatch = async (): Promise<A | null> => {
		try {
			const made = (await listAccounts()).filter((a) => isSignedIn(a) && !signedInBefore.has(a.id));
			return made.find((a) => hasAddress(a, email)) ?? made[0] ?? null;
		} catch {
			// Network blip — treat as "not yet" and keep polling.
			return null;
		}
	};

	for (let attempt = 0; attempt < OAUTH_POLL_MAX_ATTEMPTS; attempt++) {
		if (!active()) return null;
		const interval =
			attempt < OAUTH_POLL_FAST_ATTEMPTS
				? OAUTH_POLL_FAST_INTERVAL_MS
				: OAUTH_POLL_SLOW_INTERVAL_MS;
		await sleep(interval);
		if (!active()) return null;
		const match = await findMatch();
		if (match) return match;
	}

	// Final reconcile: the account may have been created in the window right as
	// the budget expired. Wait one more interval and check once more before
	// reporting a timeout.
	if (!active()) return null;
	await sleep(OAUTH_POLL_SLOW_INTERVAL_MS);
	return active() ? await findMatch() : null;
}

/**
 * The account a sign-in already owns under this address, if any. Signing in
 * with it again would only renew that account's sign-in, not add one, so the
 * wizard refuses it before opening the browser; renewing has its own control in
 * the account list.
 */
export function signedInAccountFor<A extends OAuthPollAccount>(
	accounts: readonly A[],
	email: string
): A | undefined {
	return accounts.find((a) => hasAddress(a, email) && isSignedIn(a));
}

/**
 * Whether the sign-in added the account under another address than the one the
 * user typed, which the wizard then says, since nothing else on screen would.
 */
export function isOtherIdentity(account: OAuthPollAccount, email: string): boolean {
	return !hasAddress(account, email);
}

function hasAddress(account: OAuthPollAccount, email: string): boolean {
	return account.email.trim().toLowerCase() === email.trim().toLowerCase();
}

function isSignedIn(account: OAuthPollAccount): boolean {
	return account.oauth2Provider != null;
}
