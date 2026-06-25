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
 */

export const OAUTH_POLL_FAST_INTERVAL_MS = 2000;
export const OAUTH_POLL_SLOW_INTERVAL_MS = 5000;
export const OAUTH_POLL_FAST_ATTEMPTS = 15;
// ~10 minutes: 15 × 2s (30s) + 114 × 5s (570s). Was 69 (~5 min), which a slow
// first-time consent could narrowly outlast — see the final reconcile below.
export const OAUTH_POLL_MAX_ATTEMPTS = 129;

export interface OAuthPollOptions<A extends { id: number; email: string }> {
	/** Address the user is signing in with; matched case-insensitively. */
	email: string;
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
export async function pollForOAuthAccount<A extends { id: number; email: string }>(
	options: OAuthPollOptions<A>
): Promise<A | null> {
	const { email, listAccounts, sleep, shouldContinue } = options;
	const target = email.trim().toLowerCase();
	const active = () => (shouldContinue ? shouldContinue() : true);

	const findMatch = async (): Promise<A | null> => {
		try {
			const accounts = await listAccounts();
			return accounts.find((a) => a.email.trim().toLowerCase() === target) ?? null;
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
