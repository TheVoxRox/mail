/**
 * The conversation grid's per-view cache of thread members: which threads are
 * expanded, the messages each one holds as the API returned them (unfiltered —
 * the view-dependent filtering happens at render time), and the fetches in
 * flight.
 *
 * Three mechanisms make this more than a Map, and all three are races:
 *
 *  - **A generation counter.** Every invalidation bumps it; a fetch snapshots it
 *    at the start and writes its response only if the numbers still match, so a
 *    request that started before a folder switch cannot land on top of the view
 *    that replaced it.
 *  - **Shared in-flight requests.** A second ArrowRight, or a bulk action
 *    resolving while a row expands, joins the request already running instead of
 *    firing a second one.
 *  - **Invalidation by page identity.** Every load returns a fresh page object,
 *    so a changed reference means the folder was refetched (sync_completed, a
 *    bulk action) and the cached members may no longer match the row's badge.
 *
 * The invariant that ties them together: an expanded thread always has a members
 * entry. `expand` enforces it rather than trusting call sites — a fetch whose
 * write the generation check rejected still resolves with messages, and adding
 * the thread to the expanded set on that answer would leave a row rendered open
 * with no children under it.
 *
 * Kept out of ConversationList.svelte so it can be unit tested: interleaving a
 * fetch with an invalidation is a few lines with a deferred promise here, and
 * not reliably reachable by clicking through an e2e run at all.
 */
import { SvelteMap, SvelteSet } from 'svelte/reactivity';
import type { MailSummaryResponse } from '$lib/types.js';

/** The view a cached member list belongs to. */
export interface ThreadMemberContext {
	accountId: number;
	folderName: string;
}

/**
 * What `syncToView` did:
 *  - `switched` — a different folder or page; expansion and members both dropped.
 *  - `reloaded` — the same view refetched; members dropped, expansion kept.
 *  - `unchanged` — nothing to do.
 */
export type ViewTransition = 'switched' | 'reloaded' | 'unchanged';

export interface ThreadMemberCacheOptions {
	/** The view in scope, or null while the list is not ready. A thunk: it changes under the cache. */
	context: () => ThreadMemberContext | null;
	/** Fetches one thread's members, scoped to the view's folder. */
	fetchMembers: (
		context: ThreadMemberContext,
		threadId: string
	) => Promise<readonly MailSummaryResponse[]>;
	/** Reports a failed fetch; the caller owns the wording and the announcement. */
	onLoadError: (error: unknown) => void;
}

export interface ThreadMemberCache {
	/** How many fetches are in flight — a restore that must wait for rows to arrive. */
	readonly loadingCount: number;
	/** The members loaded for a thread, or undefined while it has none cached. */
	loaded(threadId: string | null): MailSummaryResponse[] | undefined;
	/** The members this view shows under a thread — the loaded list, or empty. */
	membersOf(threadId: string | null): MailSummaryResponse[];
	isExpanded(threadId: string | null): boolean;
	isLoading(threadId: string | null): boolean;
	/**
	 * The thread's members, from the cache, from the request already in flight, or
	 * from a fresh fetch. Resolves to null when there is no view or the fetch
	 * failed — the caller must not read that as "the thread has no other members".
	 */
	load(threadId: string | null): Promise<MailSummaryResponse[] | null>;
	/**
	 * Marks a thread expanded. Returns false — and expands nothing — when its
	 * members are not in the cache, which is what keeps a row from rendering open
	 * and empty after an invalidation overtook its fetch.
	 */
	expand(threadId: string): boolean;
	collapse(threadId: string): void;
	/** Applies a view change; see {@link ViewTransition}. */
	syncToView(viewKey: string, page: unknown): ViewTransition;
	/**
	 * Re-fetches the threads still expanded after a same-view reload, and
	 * collapses the ones that no longer have a row (moved out of the folder) or
	 * whose refetch failed — an expanded id must always have a members entry.
	 */
	refreshExpanded(stillExpandable: (threadId: string) => boolean): Promise<void>;
}

export function createThreadMemberCache({
	context,
	fetchMembers,
	onLoadError
}: ThreadMemberCacheOptions): ThreadMemberCache {
	const expanded = new SvelteSet<string>();
	const members = new SvelteMap<string, MailSummaryResponse[]>();
	const loadingThreads = new SvelteSet<string>();
	// Plain Map: nothing renders from it, and a reactive read here would make
	// every caller's effect depend on a value that only coordinates fetches.
	const requests = new Map<string, Promise<MailSummaryResponse[] | null>>();
	/*
	 * Which fetch currently answers for a thread's loading flag and request entry.
	 * Both are keyed by thread, so without this a request the invalidation
	 * replaced would clear the entry its successor raised: the older `finally`
	 * ran last and reported "nothing is loading" while the refetch was still
	 * open, and a caller waiting on `loadingCount` stopped waiting one round trip
	 * early. Measured, not reasoned about — see the suite.
	 */
	const owners = new Map<string, number>();
	let fetchSerial = 0;
	let generation = 0;
	let viewKey = '';
	let cachedPage: unknown = null;

	function load(threadId: string | null): Promise<MailSummaryResponse[] | null> {
		const view = context();
		if (threadId == null || view == null) return Promise.resolve(null);
		const cached = members.get(threadId);
		if (cached) return Promise.resolve(cached);
		const inFlight = requests.get(threadId);
		if (inFlight) return inFlight;

		// Snapshot of the generation this fetch belongs to — an invalidation that
		// lands mid-flight must not have the older response written over it.
		const token = generation;
		const fetchId = (fetchSerial += 1);
		owners.set(threadId, fetchId);
		loadingThreads.add(threadId);
		const request = (async () => {
			try {
				const fetched = await fetchMembers(view, threadId);
				const messages = [...fetched];
				if (token === generation) members.set(threadId, messages);
				return messages;
			} catch (error) {
				onLoadError(error);
				return null;
			} finally {
				if (owners.get(threadId) === fetchId) {
					owners.delete(threadId);
					loadingThreads.delete(threadId);
					requests.delete(threadId);
				}
			}
		})();
		requests.set(threadId, request);
		return request;
	}

	function expand(threadId: string): boolean {
		if (!members.has(threadId)) return false;
		expanded.add(threadId);
		return true;
	}

	/**
	 * Bumps the generation and empties the cache, the in-flight bookkeeping
	 * included. The loading flags go with it: what is about to be refetched
	 * re-raises its own in the same tick (`refreshExpanded` calls `load`
	 * synchronously), and what is not has nothing left to wait for.
	 */
	function invalidateMembers(): void {
		generation += 1;
		members.clear();
		requests.clear();
		owners.clear();
		loadingThreads.clear();
	}

	return {
		get loadingCount() {
			return loadingThreads.size;
		},
		loaded: (threadId) => (threadId == null ? undefined : members.get(threadId)),
		membersOf: (threadId) => (threadId == null ? undefined : members.get(threadId)) ?? [],
		isExpanded: (threadId) => threadId != null && expanded.has(threadId),
		isLoading: (threadId) => threadId != null && loadingThreads.has(threadId),
		load,
		expand,
		collapse(threadId) {
			expanded.delete(threadId);
		},
		syncToView(nextViewKey, page) {
			if (nextViewKey !== viewKey) {
				viewKey = nextViewKey;
				cachedPage = page;
				invalidateMembers();
				expanded.clear();
				return 'switched';
			}
			if (page !== cachedPage) {
				cachedPage = page;
				invalidateMembers();
				return 'reloaded';
			}
			return 'unchanged';
		},
		async refreshExpanded(stillExpandable) {
			await Promise.all(
				[...expanded].map(async (threadId) => {
					if (!stillExpandable(threadId)) {
						expanded.delete(threadId);
						return;
					}
					/*
					 * `expand` again rather than just checking the answer: a load whose
					 * write the generation check rejected still resolves with messages,
					 * so "the fetch succeeded" is not "the members landed". Without the
					 * second half this path kept a row expanded over an empty cache —
					 * the very invariant `expand` exists to hold, bypassed by the one
					 * caller that never goes through it.
					 */
					if (!(await load(threadId)) || !expand(threadId)) expanded.delete(threadId);
				})
			);
		}
	};
}
