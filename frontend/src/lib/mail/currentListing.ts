/**
 * Which folder the mail workspace is listing, and how to get back to it.
 *
 * Two stores can answer that: the flat `messages` list and the grouped
 * `conversations` one. Only the view the `messageGrouping` preference selects
 * is mounted, and only that store is ever loaded — the other keeps whatever it
 * held before the preference was last toggled, or nothing at all.
 *
 * The message pipeline and the navigation helpers used to ask the flat store
 * alone, which in grouped mode is the one that never loads. So closing an open
 * message navigated to `/`, whose redirect drops the user in the entry folder
 * rather than the folder they were reading in, and the permanent-delete
 * confirmation lost the only fallback it had for a message it could not place.
 */
import { resolve } from '$app/paths';
import { get } from 'svelte/store';
import { conversationsState } from '$lib/stores/conversations.js';
import { messagesState } from '$lib/stores/messages.js';
import { messageGrouping } from '$lib/stores/uiLayout.js';

export interface ListingContext {
	accountId: number;
	folderName: string;
}

/**
 * The folder each loaded listing store is showing, the mounted view's first.
 * Empty when neither has been loaded.
 *
 * A store that is still loading (or has failed) answers too: its context is
 * the folder it was asked for, which is what a deep link onto a message needs
 * before anything has arrived.
 */
export function listingContexts(): ListingContext[] {
	const grouped = get(messageGrouping) === 'grouped';
	const states = grouped
		? [get(conversationsState), get(messagesState)]
		: [get(messagesState), get(conversationsState)];
	return states.flatMap((state) =>
		state.status === 'idle'
			? []
			: [{ accountId: state.context.accountId, folderName: state.context.folderName }]
	);
}

/**
 * True when the grouped view is the one on screen and has a page to refresh.
 *
 * The flat store is patched in place after every mutation (`removeMessageLocally`
 * and friends); the grouped store has no such patch, because a conversation
 * that loses a message can change both its counts and which message represents
 * it — and the replacement may not be on the page. So the pipeline refetches
 * instead, and this is the question it asks first.
 */
export function groupedListingIsShowing(): boolean {
	return get(messageGrouping) === 'grouped' && get(conversationsState).status !== 'idle';
}

/** The folder the mounted view is listing, or `null` when nothing is listed yet. */
export function currentListingContext(): ListingContext | null {
	return listingContexts()[0] ?? null;
}

/**
 * Route of the folder currently listed. Falls back to `/`, which decides where
 * Mail opens (see `entryFolder.ts`) — the best answer left when no list has
 * been loaded at all.
 */
export function currentFolderHref(): string {
	const listing = currentListingContext();
	if (!listing) return resolve('/');
	return resolve('/mail/[accountId]/[folderName]', {
		accountId: String(listing.accountId),
		folderName: encodeURIComponent(listing.folderName)
	});
}
