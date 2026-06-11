import { describe, expect, it } from 'vitest';
import { get } from 'svelte/store';
import { createPagedListStore } from './createPagedListStore.js';
import type { PagedResponse } from '$lib/types.js';

interface Ctx {
	id: number;
}
interface Item {
	stableId: string;
	seen?: boolean;
}

function makePage(items: Item[], totalElements?: number): PagedResponse<Item> {
	return {
		content: items,
		page: 0,
		size: items.length,
		totalElements: totalElements ?? items.length,
		totalPages: 1
	} as PagedResponse<Item>;
}

function deferred<T>() {
	let resolve!: (v: T) => void;
	let reject!: (e: unknown) => void;
	const promise = new Promise<T>((res, rej) => {
		resolve = res;
		reject = rej;
	});
	return { promise, resolve, reject };
}

describe('createPagedListStore', () => {
	it('starts in idle state', () => {
		const store = createPagedListStore<Ctx, Item>(async () => makePage([]));
		expect(get(store.state)).toEqual({ status: 'idle' });
	});

	it('transitions idle → loading → ready on success', async () => {
		const d = deferred<PagedResponse<Item>>();
		const store = createPagedListStore<Ctx, Item>(() => d.promise);
		const pending = store.load({ id: 1 });
		expect(get(store.state)).toEqual({ status: 'loading', context: { id: 1 } });
		d.resolve(makePage([{ stableId: 'a' }]));
		await pending;
		const snap = get(store.state);
		expect(snap.status).toBe('ready');
		if (snap.status === 'ready') {
			expect(snap.context).toEqual({ id: 1 });
			expect(snap.page.content).toHaveLength(1);
		}
	});

	it('transitions to error when the loader rejects', async () => {
		const store = createPagedListStore<Ctx, Item>(async () => {
			throw new Error('boom');
		});
		await store.load({ id: 1 });
		const snap = get(store.state);
		expect(snap.status).toBe('error');
		if (snap.status === 'error') {
			expect(snap.error.message).toBe('boom');
			expect(snap.context).toEqual({ id: 1 });
		}
	});

	it('wraps non-Error thrown values', async () => {
		const store = createPagedListStore<Ctx, Item>(async () => {
			throw 'plain string';
		});
		await store.load({ id: 1 });
		const snap = get(store.state);
		expect(snap.status).toBe('error');
		if (snap.status === 'error') {
			expect(snap.error.message).toBe('plain string');
		}
	});

	it('cancellation: stale result from earlier load is dropped after a newer load wins', async () => {
		const first = deferred<PagedResponse<Item>>();
		const second = deferred<PagedResponse<Item>>();
		const calls: Ctx[] = [];
		const store = createPagedListStore<Ctx, Item>((ctx) => {
			calls.push(ctx);
			return calls.length === 1 ? first.promise : second.promise;
		});

		const p1 = store.load({ id: 1 });
		const p2 = store.load({ id: 2 });

		// Resolve newer first, older second — older must NOT overwrite ready state.
		second.resolve(makePage([{ stableId: 'B' }]));
		await p2;
		first.resolve(makePage([{ stableId: 'A' }]));
		await p1;

		const snap = get(store.state);
		expect(snap.status).toBe('ready');
		if (snap.status === 'ready') {
			expect(snap.context).toEqual({ id: 2 });
			expect(snap.page.content.map((i) => i.stableId)).toEqual(['B']);
		}
	});

	it('cancellation also drops stale errors', async () => {
		const firstErr = deferred<PagedResponse<Item>>();
		const second = deferred<PagedResponse<Item>>();
		let call = 0;
		const store = createPagedListStore<Ctx, Item>(() => {
			call++;
			return call === 1 ? firstErr.promise : second.promise;
		});
		const p1 = store.load({ id: 1 });
		const p2 = store.load({ id: 2 });
		second.resolve(makePage([{ stableId: 'X' }]));
		await p2;
		firstErr.reject(new Error('old'));
		await p1;
		const snap = get(store.state);
		expect(snap.status).toBe('ready'); // not 'error'
	});

	it('reload re-runs the loader with the most recent context', async () => {
		let counter = 0;
		const store = createPagedListStore<Ctx, Item>(async (ctx) => {
			counter++;
			return makePage([{ stableId: `${ctx.id}-${counter}` }]);
		});
		await store.load({ id: 7 });
		await store.reload();
		const snap = get(store.state);
		expect(snap.status).toBe('ready');
		if (snap.status === 'ready') {
			expect(snap.context).toEqual({ id: 7 });
			expect(snap.page.content[0].stableId).toBe('7-2');
		}
	});

	it('reload is a no-op in idle state', () => {
		const store = createPagedListStore<Ctx, Item>(async () => makePage([]));
		expect(store.reload()).toBeUndefined();
		expect(get(store.state)).toEqual({ status: 'idle' });
	});

	it('mutateReadyPage applies mutator only in ready state', async () => {
		const store = createPagedListStore<Ctx, Item>(async () =>
			makePage([{ stableId: 'a', seen: false }])
		);
		// In idle: no-op
		store.mutateReadyPage((page) => ({ ...page, content: [] }));
		expect(get(store.state)).toEqual({ status: 'idle' });

		await store.load({ id: 1 });
		store.mutateReadyPage((page) => ({
			...page,
			content: page.content.map((i) => ({ ...i, seen: true }))
		}));
		const snap = get(store.state);
		expect(snap.status).toBe('ready');
		if (snap.status === 'ready') {
			expect(snap.page.content[0].seen).toBe(true);
		}
	});

	it('mutateReadyPage does not run during loading', async () => {
		const d = deferred<PagedResponse<Item>>();
		const store = createPagedListStore<Ctx, Item>(() => d.promise);
		const pending = store.load({ id: 1 });
		expect(get(store.state).status).toBe('loading');
		store.mutateReadyPage((page) => ({ ...page, content: [] }));
		expect(get(store.state).status).toBe('loading'); // unchanged
		d.resolve(makePage([{ stableId: 'a' }]));
		await pending;
	});
});
