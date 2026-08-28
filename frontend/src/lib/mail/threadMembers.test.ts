import { describe, expect, it, vi } from 'vitest';
import {
	createThreadMemberCache,
	type ThreadMemberCache,
	type ThreadMemberContext
} from './threadMembers.js';
import type { MailSummaryResponse } from '$lib/types.js';

/*
 * The races the conversation grid's member cache exists to lose safely. Every
 * one of them needs a fetch held open across another event, which is a deferred
 * promise here and not reliably reachable by clicking in an e2e run.
 */

function message(stableId: string, folderName = 'INBOX'): MailSummaryResponse {
	return {
		id: Number(stableId.replace(/\D/g, '')) || 1,
		stableId,
		folderName,
		subject: 'Subject',
		sender: 'someone@example.com',
		recipientsTo: null,
		receivedAt: '2026-08-28T10:00:00Z',
		seen: false,
		flagged: false,
		answered: false,
		hasAttachments: false,
		messageId: `<${stableId}@example.com>`
	};
}

interface Deferred {
	resolve(messages: MailSummaryResponse[]): void;
	reject(error: unknown): void;
}

interface Harness {
	cache: ThreadMemberCache;
	/**
	 * A fetch `load` started, by thread. Defaults to the most recent one; `nth`
	 * addresses an earlier fetch of the same thread, which is what a request
	 * outliving the invalidation that replaced it looks like from here.
	 */
	pending(threadId: string, nth?: number): Deferred;
	fetchCount(threadId?: string): number;
	setContext(context: ThreadMemberContext | null): void;
	errors: unknown[];
}

/**
 * A cache whose fetches never settle on their own. `page` objects are plain
 * sentinels — the cache compares them by identity, exactly as the store's own
 * page objects are compared.
 */
function harness(initial: ThreadMemberContext | null = { accountId: 1, folderName: 'INBOX' }) {
	let context = initial;
	const calls: { threadId: string; deferred: Deferred }[] = [];
	const errors: unknown[] = [];
	const cache = createThreadMemberCache({
		context: () => context,
		fetchMembers: (_ctx, threadId) =>
			new Promise<MailSummaryResponse[]>((resolve, reject) => {
				calls.push({ threadId, deferred: { resolve, reject } });
			}),
		onLoadError: (error) => errors.push(error)
	});
	const api: Harness = {
		cache,
		pending: (threadId, nth) => {
			const forThread = calls.filter((call) => call.threadId === threadId);
			const call = forThread[nth ?? forThread.length - 1];
			if (!call) throw new Error(`no fetch #${nth ?? 'last'} started for ${threadId}`);
			return call.deferred;
		},
		fetchCount: (threadId) =>
			threadId == null ? calls.length : calls.filter((call) => call.threadId === threadId).length,
		setContext: (next) => {
			context = next;
		},
		errors
	};
	return api;
}

/** The cache's own view bookkeeping, primed so later syncs read as changes. */
function openView(cache: ThreadMemberCache, page: object): void {
	expect(cache.syncToView('1:INBOX:0', page)).toBe('switched');
}

describe('createThreadMemberCache', () => {
	describe('loading', () => {
		it('fetches a thread once and serves the cache after that', async () => {
			const { cache, pending, fetchCount } = harness();
			const first = cache.load('t1');
			pending('t1').resolve([message('a'), message('b')]);
			await first;
			await cache.load('t1');
			expect(fetchCount('t1')).toBe(1);
			expect(cache.membersOf('t1').map((m) => m.stableId)).toEqual(['a', 'b']);
		});

		it('shares one in-flight request between concurrent callers', async () => {
			const { cache, pending, fetchCount } = harness();
			const first = cache.load('t1');
			const second = cache.load('t1');
			pending('t1').resolve([message('a')]);
			expect(await first).toEqual(await second);
			expect(fetchCount('t1')).toBe(1);
		});

		it('reports loading while the fetch is open and stops when it settles', async () => {
			const { cache, pending } = harness();
			const inFlight = cache.load('t1');
			expect(cache.isLoading('t1')).toBe(true);
			expect(cache.loadingCount).toBe(1);
			pending('t1').resolve([message('a')]);
			await inFlight;
			expect(cache.isLoading('t1')).toBe(false);
			expect(cache.loadingCount).toBe(0);
		});

		it('answers null without a view rather than fetching', async () => {
			const { cache, fetchCount } = harness(null);
			expect(await cache.load('t1')).toBeNull();
			expect(fetchCount()).toBe(0);
		});

		it('answers null for a row with no thread id', async () => {
			const { cache, fetchCount } = harness();
			expect(await cache.load(null)).toBeNull();
			expect(fetchCount()).toBe(0);
		});

		it('answers null on a failed fetch and caches nothing', async () => {
			const { cache, pending, errors } = harness();
			const inFlight = cache.load('t1');
			const failure = new Error('offline');
			pending('t1').reject(failure);
			expect(await inFlight).toBeNull();
			expect(errors).toEqual([failure]);
			expect(cache.loaded('t1')).toBeUndefined();
			expect(cache.loadingCount).toBe(0);
		});

		it('retries after a failure instead of serving the failed request', async () => {
			const { cache, pending, fetchCount } = harness();
			const failed = cache.load('t1');
			pending('t1').reject(new Error('offline'));
			await failed;
			const retry = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await retry;
			expect(fetchCount('t1')).toBe(2);
			expect(cache.membersOf('t1')).toHaveLength(1);
		});
	});

	describe('the expansion invariant', () => {
		it('expands a thread whose members are loaded', async () => {
			const { cache, pending } = harness();
			const inFlight = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await inFlight;
			expect(cache.expand('t1')).toBe(true);
			expect(cache.isExpanded('t1')).toBe(true);
		});

		it('refuses to expand a thread with nothing loaded', () => {
			const { cache } = harness();
			expect(cache.expand('t1')).toBe(false);
			expect(cache.isExpanded('t1')).toBe(false);
		});

		it('refuses to expand on a response the view switch already invalidated', async () => {
			// The fetch resolves with real messages, so the caller cannot tell from
			// the answer alone that they are stale — which is why the cache, not the
			// call site, owns this decision.
			const { cache, pending } = harness();
			openView(cache, {});
			const inFlight = cache.load('t1');
			cache.syncToView('1:Sent:0', {});
			pending('t1').resolve([message('a')]);
			expect(await inFlight).toHaveLength(1);
			expect(cache.loaded('t1')).toBeUndefined();
			expect(cache.expand('t1')).toBe(false);
		});

		it('collapses on request', async () => {
			const { cache, pending } = harness();
			const inFlight = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await inFlight;
			cache.expand('t1');
			cache.collapse('t1');
			expect(cache.isExpanded('t1')).toBe(false);
		});
	});

	describe('view changes', () => {
		it('reports the same view unchanged', () => {
			const { cache } = harness();
			const page = {};
			openView(cache, page);
			expect(cache.syncToView('1:INBOX:0', page)).toBe('unchanged');
		});

		it('drops expansion and members on a folder or page switch', async () => {
			const { cache, pending } = harness();
			openView(cache, {});
			const inFlight = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await inFlight;
			cache.expand('t1');
			expect(cache.syncToView('1:INBOX:1', {})).toBe('switched');
			expect(cache.isExpanded('t1')).toBe(false);
			expect(cache.loaded('t1')).toBeUndefined();
			expect(cache.loadingCount).toBe(0);
		});

		it('keeps expansion but drops members when the same view is refetched', async () => {
			const { cache, pending } = harness();
			openView(cache, {});
			const inFlight = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await inFlight;
			cache.expand('t1');
			expect(cache.syncToView('1:INBOX:0', {})).toBe('reloaded');
			expect(cache.isExpanded('t1')).toBe(true);
			expect(cache.loaded('t1')).toBeUndefined();
		});

		it('does not let a fetch started before a reload write into the new generation', async () => {
			const { cache, pending } = harness();
			openView(cache, {});
			const stale = cache.load('t1');
			cache.syncToView('1:INBOX:0', {});
			pending('t1').resolve([message('stale')]);
			await stale;
			expect(cache.loaded('t1')).toBeUndefined();
		});

		it('keeps reporting a load while the refetch replacing it is still open', async () => {
			// The replaced request settles last and its `finally` runs last. Both the
			// loading flag and the request entry are keyed by thread, so without an
			// owner check that older `finally` clears the bookkeeping of its own
			// successor — and the focus restore in ConversationList, which waits on
			// `loadingCount` before it grabs a row, stops waiting a round trip early
			// and lands on an unrelated one.
			const { cache, pending } = harness();
			openView(cache, {});
			const stale = cache.load('t1');
			cache.syncToView('1:INBOX:0', {});
			const fresh = cache.load('t1');
			pending('t1', 0).resolve([message('stale')]);
			await stale;
			expect(cache.isLoading('t1')).toBe(true);
			expect(cache.loadingCount).toBe(1);
			pending('t1', 1).resolve([message('fresh')]);
			await fresh;
			expect(cache.loadingCount).toBe(0);
		});

		it('stops reporting a load that nothing replaced', async () => {
			// The other half of the owner check: an invalidation with no refetch
			// behind it must not leave the flag raised for ever, or the same focus
			// restore waits for a load that will never finish.
			const { cache, pending } = harness();
			openView(cache, {});
			const abandoned = cache.load('t1');
			cache.syncToView('1:INBOX:0', {});
			pending('t1').resolve([message('a')]);
			await abandoned;
			expect(cache.loadingCount).toBe(0);
		});

		it('lets the refetch after a reload win over the request it replaced', async () => {
			const { cache, pending } = harness();
			openView(cache, {});
			const stale = cache.load('t1');
			cache.syncToView('1:INBOX:0', {});
			const fresh = cache.load('t1');
			// The fresh answer lands first and the request it replaced settles after
			// it — the interleaving in which a cache without generations serves the
			// older list from then on.
			pending('t1', 1).resolve([message('fresh')]);
			await fresh;
			pending('t1', 0).resolve([message('stale')]);
			await stale;
			expect(cache.membersOf('t1').map((m) => m.stableId)).toEqual(['fresh']);
		});
	});

	describe('refreshing what stayed expanded', () => {
		it('refetches the still-expandable threads', async () => {
			const { cache, pending, fetchCount } = harness();
			openView(cache, {});
			const first = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await first;
			cache.expand('t1');
			cache.syncToView('1:INBOX:0', {});
			const refreshed = cache.refreshExpanded(() => true);
			pending('t1').resolve([message('a'), message('b')]);
			await refreshed;
			expect(fetchCount('t1')).toBe(2);
			expect(cache.isExpanded('t1')).toBe(true);
			expect(cache.membersOf('t1')).toHaveLength(2);
		});

		it('collapses a thread that lost its row', async () => {
			const { cache, pending, fetchCount } = harness();
			const first = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await first;
			cache.expand('t1');
			await cache.refreshExpanded(() => false);
			expect(cache.isExpanded('t1')).toBe(false);
			expect(fetchCount('t1')).toBe(1);
		});

		it('collapses a thread whose refetch failed', async () => {
			const { cache, pending } = harness();
			openView(cache, {});
			const first = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await first;
			cache.expand('t1');
			cache.syncToView('1:INBOX:0', {});
			const refreshed = cache.refreshExpanded(() => true);
			pending('t1').reject(new Error('offline'));
			await refreshed;
			expect(cache.isExpanded('t1')).toBe(false);
		});

		it('collapses a thread whose refetch resolved into a generation that moved on', async () => {
			// A second reload lands while the first refresh is open. The refetch then
			// resolves with real messages the cache refuses to keep, so "the load
			// succeeded" is not enough to stay expanded — the row would render open
			// over an empty member list.
			const { cache, pending } = harness();
			openView(cache, {});
			const first = cache.load('t1');
			pending('t1').resolve([message('a')]);
			await first;
			cache.expand('t1');
			cache.syncToView('1:INBOX:0', {});
			const refreshing = cache.refreshExpanded(() => true);
			cache.syncToView('1:INBOX:0', {});
			pending('t1').resolve([message('a')]);
			await refreshing;
			expect(cache.loaded('t1')).toBeUndefined();
			expect(cache.isExpanded('t1')).toBe(false);
		});

		it('asks about every expanded thread, not just the first', async () => {
			const { cache, pending } = harness();
			const loads = [cache.load('t1'), cache.load('t2')];
			pending('t1').resolve([message('a')]);
			pending('t2').resolve([message('b')]);
			await Promise.all(loads);
			cache.expand('t1');
			cache.expand('t2');
			const stillExpandable = vi.fn((_threadId: string) => false);
			await cache.refreshExpanded(stillExpandable);
			expect(stillExpandable.mock.calls.map(([id]) => id).sort()).toEqual(['t1', 't2']);
		});
	});
});
