/**
 * Mail account store.
 *
 * The account list is loaded from `/accounts`. The active account is
 * persisted in localStorage (just the ID; details are derived from the
 * list).
 */

import { derived, get, writable } from 'svelte/store';
import { listAccounts } from '$lib/api/accounts.js';
import { toError } from '$lib/api/errors.js';
import type { AccountResponse } from '$lib/types.js';

const ACTIVE_ACCOUNT_KEY = 'mail.activeAccountId';

export type AccountsState =
	| { status: 'idle' }
	| { status: 'loading' }
	| { status: 'ready'; accounts: AccountResponse[] }
	| { status: 'error'; error: Error };

export const accountsState = writable<AccountsState>({ status: 'idle' });

function readStoredActiveId(): number | null {
	if (typeof localStorage === 'undefined') return null;
	const raw = localStorage.getItem(ACTIVE_ACCOUNT_KEY);
	if (!raw) return null;
	const parsed = Number(raw);
	return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}

export const activeAccountId = writable<number | null>(readStoredActiveId());

activeAccountId.subscribe((id) => {
	if (typeof localStorage === 'undefined') return;
	if (id == null) {
		localStorage.removeItem(ACTIVE_ACCOUNT_KEY);
	} else {
		localStorage.setItem(ACTIVE_ACCOUNT_KEY, String(id));
	}
});

export const activeAccount = derived(
	[accountsState, activeAccountId],
	([$accountsState, $activeAccountId]): AccountResponse | null => {
		if ($accountsState.status !== 'ready' || $activeAccountId == null) return null;
		return $accountsState.accounts.find((a) => a.id === $activeAccountId) ?? null;
	}
);

export const resolvedActiveAccountId = derived(
	[accountsState, activeAccountId],
	([$accountsState, $activeAccountId]): number | null => {
		if ($accountsState.status !== 'ready') return null;
		const stored = $accountsState.accounts.find((account) => account.id === $activeAccountId);
		return stored?.id ?? $accountsState.accounts[0]?.id ?? null;
	}
);

/** Loads accounts from the API, stores them and picks the default active account. */
export async function loadAccounts(): Promise<AccountResponse[]> {
	accountsState.set({ status: 'loading' });
	try {
		const accounts = await listAccounts();
		accountsState.set({ status: 'ready', accounts });

		const current = get(activeAccountId);
		const stillValid = current != null && accounts.some((a) => a.id === current);
		if (!stillValid) {
			activeAccountId.set(accounts[0]?.id ?? null);
		}

		return accounts;
	} catch (err) {
		const error = toError(err);
		accountsState.set({ status: 'error', error });
		throw error;
	}
}

export function setActiveAccount(id: number | null): void {
	if (id != null) {
		const state = get(accountsState);
		if (state.status === 'ready' && !state.accounts.some((account) => account.id === id)) {
			activeAccountId.set(state.accounts[0]?.id ?? null);
			return;
		}
	}
	activeAccountId.set(id);
}
