/**
 * What a message-list row's actions menu does when an item is chosen.
 *
 * The menu itself (`components/MessageRowActionsMenu.svelte`) is the same on
 * every list, but the pipeline behind it is not: the flat list and the search
 * grid mutate through `mailbox.ts`, which refreshes the `messages` store, while
 * the conversation-grouped list has to go through `conversationBulk.ts` — that
 * one refreshes the `conversations` store and drives the permanent-delete
 * confirmation from the folder role the grouped view knows. Injecting the
 * actions keeps that choice with the list that owns the store, instead of
 * teaching the menu about both.
 */
export type RowActions = {
	/** `all` = reply to every recipient, not just the sender. */
	reply: (all: boolean) => void;
	forward: () => void;
	toggleFlag: () => void;
	toggleSeen: () => void;
	moveTo: (folderRef: string) => void;
	/** Delete — named `remove` so call sites do not read as the `delete` operator. */
	remove: () => void;
};
