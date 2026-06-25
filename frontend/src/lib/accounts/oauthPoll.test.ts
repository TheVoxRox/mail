import { describe, expect, it, vi } from 'vitest';
import {
	OAUTH_POLL_MAX_ATTEMPTS,
	pollForOAuthAccount,
	type OAuthPollOptions
} from './oauthPoll.js';

type TestAccount = { id: number; email: string };

const ACCOUNT: TestAccount = { id: 1, email: 'luke.lacina@gmail.com' };

/** sleep is injected and resolves instantly so the ~10-min budget runs in ms. */
function baseOptions(
	listAccounts: () => Promise<TestAccount[]>,
	overrides: Partial<OAuthPollOptions<TestAccount>> = {}
): OAuthPollOptions<TestAccount> {
	return {
		email: 'luke.lacina@gmail.com',
		listAccounts,
		sleep: async () => {},
		...overrides
	};
}

describe('pollForOAuthAccount', () => {
	it('resolves the account once it appears within budget (case-insensitive)', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			// Appears on the 3rd poll, with different casing than the query.
			return calls >= 3 ? [{ id: 1, email: 'Luke.Lacina@Gmail.com' }] : [];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual({ id: 1, email: 'Luke.Lacina@Gmail.com' });
		expect(calls).toBe(3);
	});

	it('recovers via the final reconcile when the account is created as the budget expires', async () => {
		let calls = 0;
		// Empty for every in-budget poll; only the post-budget reconcile sees it.
		const listAccounts = vi.fn(async () => {
			calls += 1;
			return calls > OAUTH_POLL_MAX_ATTEMPTS ? [ACCOUNT] : [];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		// This is the near-miss the fix targets: budget exhausted, account still
		// returned instead of a false timeout.
		expect(result).toEqual(ACCOUNT);
		expect(calls).toBe(OAUTH_POLL_MAX_ATTEMPTS + 1);
	});

	it('returns null when the account never appears (genuine timeout)', async () => {
		const listAccounts = vi.fn(async () => [] as TestAccount[]);

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toBeNull();
		expect(listAccounts).toHaveBeenCalledTimes(OAUTH_POLL_MAX_ATTEMPTS + 1);
	});

	it('ignores transient list errors and keeps polling', async () => {
		let calls = 0;
		const listAccounts = vi.fn(async () => {
			calls += 1;
			if (calls < 3) throw new Error('network down');
			return [ACCOUNT];
		});

		const result = await pollForOAuthAccount(baseOptions(listAccounts));

		expect(result).toEqual(ACCOUNT);
	});

	it('aborts early when shouldContinue turns false (cancel / leave page)', async () => {
		const listAccounts = vi.fn(async () => [] as TestAccount[]);
		let checks = 0;
		const result = await pollForOAuthAccount(
			baseOptions(listAccounts, {
				// Stay active for a few checks, then simulate the user cancelling.
				shouldContinue: () => {
					checks += 1;
					return checks <= 6;
				}
			})
		);

		expect(result).toBeNull();
		// Far fewer than the full budget — it bailed out, not timed out.
		expect(listAccounts.mock.calls.length).toBeLessThan(OAUTH_POLL_MAX_ATTEMPTS);
	});
});
