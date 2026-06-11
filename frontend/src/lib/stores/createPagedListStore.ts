/**
 * Generic store for a paginated list over a single context (typically
 * account+folder+page or account+query+page).
 *
 * Holds an `idle | loading | ready | error` state with a cancel token —
 * if another `load(ctx)` is invoked, the previous run's result is
 * discarded so the UI never shows "stale" data after a fast context
 * switch.
 */
import { writable, get, type Writable } from 'svelte/store';
import { toError } from '$lib/api/errors.js';
import type { PagedResponse } from '$lib/types.js';

export type PagedListState<TContext, TItem> =
	| { status: 'idle' }
	| { status: 'loading'; context: TContext }
	| { status: 'ready'; context: TContext; page: PagedResponse<TItem> }
	| { status: 'error'; context: TContext; error: Error };

interface PagedListStore<TContext, TItem> {
	state: Writable<PagedListState<TContext, TItem>>;
	load(context: TContext): Promise<void>;
	/** Reloads the current context (no-op in idle state). */
	reload(): Promise<void> | void;
	/**
	 * Atomic mutation of the `ready` page — for optimistic content changes
	 * (e.g. flag, item deletion). No-op in other states.
	 */
	mutateReadyPage(mutator: (page: PagedResponse<TItem>) => PagedResponse<TItem>): void;
}

export function createPagedListStore<TContext, TItem>(
	loader: (context: TContext) => Promise<PagedResponse<TItem>>
): PagedListStore<TContext, TItem> {
	const state = writable<PagedListState<TContext, TItem>>({ status: 'idle' });
	let token = 0;

	async function load(context: TContext): Promise<void> {
		const current = ++token;
		state.set({ status: 'loading', context });
		try {
			const page = await loader(context);
			if (current !== token) return;
			state.set({ status: 'ready', context, page });
		} catch (err) {
			if (current !== token) return;
			const error = toError(err);
			state.set({ status: 'error', context, error });
		}
	}

	function reload(): Promise<void> | void {
		const snapshot = get(state);
		if (snapshot.status === 'idle') return;
		return load(snapshot.context);
	}

	function mutateReadyPage(mutator: (page: PagedResponse<TItem>) => PagedResponse<TItem>): void {
		state.update((snapshot) => {
			if (snapshot.status !== 'ready') return snapshot;
			return { ...snapshot, page: mutator(snapshot.page) };
		});
	}

	return { state, load, reload, mutateReadyPage };
}
