/**
 * Async provider resolver keyed by e-mail, with debounce and a cancel token.
 *
 * Extracted out of `AccountForm.svelte`, where the component used to trigger
 * a `state_referenced_locally` warning 13×. The resolver is a pure TS unit,
 * testable without a DOM — the component just registers callbacks and calls
 * `schedule(email)`.
 */
import { resolveProviderByEmail } from '$lib/api/providers.js';
import { isValidEmailAddress } from '$lib/compose/addresses.js';
import type { MailProviderResponse } from '$lib/types.js';

interface ProviderResolverCallbacks {
	/** Provider was successfully resolved from the given e-mail address. */
	onResolved: (provider: MailProviderResponse, normalizedEmail: string) => void;
	/**
	 * Resolver forgot the previous outcome: a provider it had resolved (the
	 * address changed and the lookup failed, or it stopped being valid), or a
	 * lookup that had failed (the address stopped being valid).
	 */
	onCleared?: () => void;
	/**
	 * The lookup for a valid address failed — no template for its domain (a
	 * 404), or the request itself. Called after onCleared when a resolved
	 * provider is being replaced.
	 */
	onFailed?: (error: unknown, normalizedEmail: string) => void;
}

interface ProviderResolverOptions extends ProviderResolverCallbacks {
	/** Debounce in ms. Default 500. */
	debounceMs?: number;
	/** Loader injection for tests. */
	resolveFn?: (email: string) => Promise<MailProviderResponse>;
}

export interface ProviderResolver {
	/** Schedules a debounced resolve; subsequent calls reset the wait. */
	schedule(email: string): void;
	/** Runs resolve immediately (cancels the debounce timer). Returns a promise for tests. */
	resolveNow(email: string): Promise<void>;
	/** Cancels the in-flight/scheduled request. */
	cancel(): void;
	/** Forgets the last resolved state (e.g. after manual provider change). */
	reset(): void;
}

export function createProviderResolver(options: ProviderResolverOptions): ProviderResolver {
	const debounceMs = options.debounceMs ?? 500;
	const resolveFn = options.resolveFn ?? resolveProviderByEmail;
	let lastResolvedEmail: string | null = null;
	let token = 0;
	let timer: ReturnType<typeof setTimeout> | null = null;
	let resolved = false;
	// The last lookup for a valid address failed; an invalid address clears it.
	let failed = false;

	function cancel(): void {
		token++;
		if (timer) {
			clearTimeout(timer);
			timer = null;
		}
	}

	function reset(): void {
		cancel();
		lastResolvedEmail = null;
		resolved = false;
		failed = false;
	}

	async function doResolve(email: string): Promise<void> {
		const normalizedEmail = email.trim().toLowerCase();
		if (!normalizedEmail || !isValidEmailAddress(normalizedEmail)) {
			if (resolved || failed) {
				resolved = false;
				failed = false;
				lastResolvedEmail = null;
				options.onCleared?.();
			}
			return;
		}
		if (resolved && normalizedEmail === lastResolvedEmail) return;

		const myToken = ++token;
		timer = null;
		try {
			const provider = await resolveFn(normalizedEmail);
			if (myToken !== token) return;
			resolved = true;
			failed = false;
			lastResolvedEmail = normalizedEmail;
			options.onResolved(provider, normalizedEmail);
		} catch (error) {
			if (myToken !== token) return;
			if (resolved) {
				resolved = false;
				lastResolvedEmail = null;
				options.onCleared?.();
			}
			failed = true;
			options.onFailed?.(error, normalizedEmail);
		}
	}

	function schedule(email: string): void {
		cancel();
		timer = setTimeout(() => void doResolve(email), debounceMs);
	}

	function resolveNow(email: string): Promise<void> {
		cancel();
		return doResolve(email);
	}

	return { schedule, resolveNow, cancel, reset };
}
