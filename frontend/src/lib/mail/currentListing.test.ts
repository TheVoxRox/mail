import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$lib/stores/messages.js', async () => {
	const { writable } = await import('svelte/store');
	return { messagesState: writable({ status: 'idle' }) };
});

vi.mock('$lib/stores/conversations.js', async () => {
	const { writable } = await import('svelte/store');
	return { conversationsState: writable({ status: 'idle' }) };
});

import { currentFolderHref, currentListingContext, listingContexts } from './currentListing.js';
import { conversationsState } from '$lib/stores/conversations.js';
import { messagesState } from '$lib/stores/messages.js';
import { messageGrouping } from '$lib/stores/uiLayout.js';
import type { Writable } from 'svelte/store';

const messagesStore = messagesState as unknown as Writable<unknown>;
const conversationsStore = conversationsState as unknown as Writable<unknown>;

function listing(status: 'loading' | 'ready', folderName: string) {
	const context = { accountId: 7, folderName, page: 0, size: 50 };
	if (status === 'loading') return { status, context };
	return {
		status,
		context,
		page: { content: [], totalElements: 0, totalPages: 0, number: 0 }
	};
}

describe('currentListing', () => {
	beforeEach(() => {
		messagesStore.set({ status: 'idle' });
		conversationsStore.set({ status: 'idle' });
		messageGrouping.set('flat');
	});

	it('answers with the mounted view even when the other store holds an older folder', () => {
		// Toggling the grouping leaves the store the previous view used loaded,
		// and it is not reloaded while it is not on screen.
		messagesStore.set(listing('ready', 'ARCHIVE'));
		conversationsStore.set(listing('ready', 'INBOX'));
		messageGrouping.set('grouped');

		expect(currentListingContext()).toEqual({ accountId: 7, folderName: 'INBOX' });
	});

	it('answers from the other store when the mounted view has not loaded', () => {
		// The grouped view is selected but its first page is still on its way:
		// what the flat store was showing is a better answer than none.
		messagesStore.set(listing('ready', 'ARCHIVE'));
		messageGrouping.set('grouped');

		expect(currentListingContext()).toEqual({ accountId: 7, folderName: 'ARCHIVE' });
	});

	it('answers from a listing that is still loading', () => {
		// A deep link onto a message acts before its folder page arrives.
		conversationsStore.set(listing('loading', 'Trash'));
		messageGrouping.set('grouped');

		expect(currentListingContext()).toEqual({ accountId: 7, folderName: 'Trash' });
	});

	it('reports every loaded listing, the mounted view first', () => {
		messagesStore.set(listing('ready', 'ARCHIVE'));
		conversationsStore.set(listing('loading', 'Trash'));
		messageGrouping.set('grouped');

		expect(listingContexts().map((context) => context.folderName)).toEqual(['Trash', 'ARCHIVE']);
	});

	it('reports nothing when no list has been loaded', () => {
		expect(listingContexts()).toEqual([]);
		expect(currentListingContext()).toBeNull();
	});

	it('routes back to the folder the grouped view is listing', () => {
		// The flat store stays idle for a whole session in grouped mode, and
		// reading it alone sent every close of an open message to `/`, whose
		// redirect lands in the entry folder instead of this one.
		conversationsStore.set(listing('ready', 'Archiv/2026'));
		messageGrouping.set('grouped');

		expect(currentFolderHref()).toBe('/mail/7/Archiv%2F2026');
	});

	it('routes to `/` when no list has been loaded', () => {
		expect(currentFolderHref()).toBe('/');
	});
});
