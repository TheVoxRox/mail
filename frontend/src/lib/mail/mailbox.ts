/**
 * Mailbox facade — owns the optimistic pipeline for message mutations.
 *
 * Unifies single + bulk operations under one path (executeBulkMessageAction)
 * and centralises "after mutation" side-effects: local cache, invalidation
 * of the selected message, folder refresh, navigation away from a
 * deleted/moved message and toast. Callers (`mail/actions.ts`,
 * `commands/*`) should not orchestrate these steps manually.
 */
import { goto } from '$app/navigation';
import { get } from 'svelte/store';
import { deleteMessage, moveMessage, setMessageFlag } from '$lib/api/mailAction.js';
import { getMessageDetail } from '$lib/api/mailRead.js';
import { folders as folderList, adjustFolderUnread } from '$lib/stores/folders.js';
import { searchState } from '$lib/stores/search.js';
import { conversationsState, reloadCurrentConversationsPage } from '$lib/stores/conversations.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import {
	clearSelection,
	invalidateMessage,
	patchSelectedMessageDetail,
	requestConversationFocusRestore,
	requestEmptyListFocus,
	requestListFocusRestore,
	selectedMessage,
	type ListFocusRestore
} from '$lib/stores/selectedMessage.js';
import {
	markSeenLocally,
	patchMessageLocally,
	removeMessageLocally,
	type MessagesState,
	messagesState
} from '$lib/stores/messages.js';
import { setMessageSelection } from '$lib/stores/messageSelection.js';
import { _ } from '$lib/i18n/index.js';
import { toErrorMessage } from '$lib/api/errors.js';
import {
	currentFolderHref,
	currentListingContext,
	groupedListingIsShowing,
	listingContexts
} from '$lib/mail/currentListing.js';
import { closeOpenDetail } from '$lib/mail/detailHost.js';
import { folderLabel } from '$lib/mail/folderLabel.js';
import { overruleAutoMarkSeen } from '$lib/mail/message-seen.js';
import { announcePolite, pushToast } from '$lib/stores/toasts.js';

interface BulkResult {
	succeeded: number;
	failed: number;
	succeededIds: string[];
	failedIds: string[];
	/** Rejection reason of the first failed item — error feedback for single ops. */
	firstError?: unknown;
}

interface ExecuteBulkOptions {
	ids: readonly string[];
	perItem: (stableId: string) => Promise<void>;
	/** Folds the effect into MessageSelection: successful items are removed from the selection. */
	pruneSelection?: boolean;
	/**
	 * When true and the successfully processed message is currently open in
	 * the detail view, close the detail and navigate back to the folder
	 * (typical for delete/move).
	 */
	clearDetailIfAffected?: boolean;
	/**
	 * On success optimistically decrements the source folder's unread count by
	 * the number of unread messages removed (for delete/move). The server-side
	 * op is async, so a folder re-fetch here would read the stale pre-op count
	 * and clobber the heading badge; the next sync reconciles the real value.
	 */
	adjustSourceFolderUnread?: boolean;
	/** i18n key for the toast with {count, failed}. */
	toastKey?: string;
	/** Extra values for the toast (e.g. folder). */
	toastValues?: Record<string, string | number>;
}

function currentMessagesState(): MessagesState {
	return get(messagesState);
}

/** A subject worth announcing, or nothing — blank and whitespace-only alike. */
function spokenSubject(subject: string | null | undefined): string | undefined {
	const trimmed = subject?.trim();
	return trimmed ? trimmed : undefined;
}

/**
 * Subject of a message for an outcome announcement, from whichever screen
 * knows it; falls back only when none of them does.
 *
 * The flat list is not the only place these actions start from. The grouped
 * view lists from its own store and never loads this one, search results sit
 * in a third, and a message opened from any of them is deleted from the detail
 * toolbar, its shortcut or the command palette — all of which land here.
 * Reading the flat rows alone made every one of those announce "(no subject)"
 * for a message that had one; in grouped mode, where this store stays idle,
 * that was every delete of an open message.
 *
 * The open detail answers last because it is the one source that knows a
 * single message rather than a list — right when the action came from the
 * detail, silent otherwise.
 */
function messageSubjectLabel(stableId: string): string {
	const messages = currentMessagesState();
	const listRow =
		messages.status === 'ready'
			? messages.page.content.find((message) => message.stableId === stableId)
			: undefined;
	const search = get(searchState);
	const searchRow =
		search.status === 'ready'
			? search.page.content.find((message) => message.stableId === stableId)
			: undefined;
	const conversations = get(conversationsState);
	const conversationRow =
		conversations.status === 'ready'
			? conversations.page.content.find((row) => row.latest.stableId === stableId)?.latest
			: undefined;
	const open = get(selectedMessage);
	const openDetail = open?.stableId === stableId ? open.detail : null;
	return (
		spokenSubject(listRow?.subject) ??
		spokenSubject(searchRow?.subject) ??
		spokenSubject(conversationRow?.subject) ??
		spokenSubject(openDetail?.subject) ??
		get(_)('messages.noSubject')
	);
}

/**
 * Whether a message was still unread just before the mutation, from whatever
 * holds its state; `false` when nothing does, so an unknown message is never
 * subtracted from a folder's badge.
 *
 * The open detail answers first here, unlike the subject above. It is the copy
 * the app keeps patched — opening a message marks it read through
 * `patchSelectedMessageDetail` — while a grouped row is never patched locally,
 * so trusting the row would subtract a message that had already been counted
 * as read the moment it was opened.
 */
function wasUnread(stableId: string): boolean {
	const open = get(selectedMessage);
	if (open?.stableId === stableId && open.detail) return !open.detail.seen;
	const messages = currentMessagesState();
	if (messages.status === 'ready') {
		const row = messages.page.content.find((message) => message.stableId === stableId);
		if (row) return !row.seen;
	}
	const conversations = get(conversationsState);
	if (conversations.status === 'ready') {
		const row = conversations.page.content.find((entry) => entry.latest.stableId === stableId);
		if (row) return !row.latest.seen;
	}
	return false;
}

/**
 * Where focus goes once `stableId` leaves the list. `null` means the row is
 * not on the page the list is showing (the action came from search results, or
 * from another page), so this list has no say; `emptied` means it was the last
 * row and only the empty state is left to receive focus.
 *
 * Both listings answer, because both can be the one the user came from and the
 * one they are dropped back into — and the grouped list, which is never
 * mounted while a message is open in `off` mode, cannot work this out for
 * itself afterwards: by then the row is gone and the page has been refetched.
 */
function focusTargetAfterRemoving(stableId: string): ListFocusRestore | null {
	const state = currentMessagesState();
	if (state.status === 'ready') {
		const index = state.page.content.findIndex((message) => message.stableId === stableId);
		if (index >= 0) {
			const neighbour =
				state.page.content[index + 1]?.stableId ?? state.page.content[index - 1]?.stableId ?? null;
			return neighbour ? { kind: 'row', stableId: neighbour } : { kind: 'emptied' };
		}
	}
	const conversations = get(conversationsState);
	if (conversations.status !== 'ready') return null;
	const rows = conversations.page.content;
	const index = rows.findIndex((row) => row.latest.stableId === stableId);
	// Only a conversation's representative is resolvable here: a member of an
	// expanded thread lives in the list's own cache, which this store does not
	// carry. That row keeps the list's own restore (`pendingRowFocus`), because
	// a member is opened from a list that stays mounted.
	if (index < 0) return null;
	const neighbour = rows[index + 1] ?? rows[index - 1] ?? null;
	const threadId = rows[index].threadId;
	// A thread the backfill has not processed yet is a singleton, so its row
	// goes with the message and only the neighbour is left to land on.
	if (threadId === null) {
		return neighbour ? { kind: 'row', stableId: neighbour.latest.stableId } : { kind: 'emptied' };
	}
	return { kind: 'conversation', threadId, fallbackStableId: neighbour?.latest.stableId ?? null };
}

/** Applies a target from `focusTargetAfterRemoving`; a null target asks for nothing. */
function requestFocusTarget(target: ListFocusRestore | null | undefined): void {
	if (!target) return;
	if (target.kind === 'row') requestListFocusRestore(target.stableId);
	else if (target.kind === 'conversation')
		requestConversationFocusRestore(target.threadId, target.fallbackStableId);
	else requestEmptyListFocus();
}

async function ensureMessageDetail(stableId: string) {
	const current = get(selectedMessage);
	if (current?.stableId === stableId && current.detail) {
		return current.detail;
	}
	return getMessageDetail(stableId);
}

async function executeBulkMessageAction(options: ExecuteBulkOptions): Promise<BulkResult> {
	const ids = Array.from(new Set(options.ids));
	if (ids.length === 0) {
		return { succeeded: 0, failed: 0, succeededIds: [], failedIds: [] };
	}

	// Compute the focus target before the mutation — after removeMessageLocally
	// the message is no longer in the list and findIndex would return -1.
	const focusTargetsBeforeMutation = new Map<string, ListFocusRestore | null>();
	if (options.clearDetailIfAffected) {
		for (const id of ids) {
			focusTargetsBeforeMutation.set(id, focusTargetAfterRemoving(id));
		}
	}

	// Snapshot which ids were unread and where — before the mutation, while the
	// rows are still in the list — for the optimistic folder-unread adjustment.
	// The folder comes from whichever listing is on screen, so the badge follows
	// a delete in the grouped view as it does in the flat one.
	let unreadBefore: { accountId: number; folderName: string; ids: Set<string> } | null = null;
	if (options.adjustSourceFolderUnread) {
		const listing = currentListingContext();
		if (listing) {
			unreadBefore = {
				accountId: listing.accountId,
				folderName: listing.folderName,
				ids: new Set(ids.filter((id) => wasUnread(id)))
			};
		}
	}

	const settled = await Promise.allSettled(ids.map((id) => options.perItem(id)));
	const succeededIds = ids.filter((_, i) => settled[i]?.status === 'fulfilled');
	const failedIds = ids.filter((_, i) => settled[i]?.status !== 'fulfilled');
	const firstRejection = settled.find(
		(outcome): outcome is PromiseRejectedResult => outcome.status === 'rejected'
	);
	const result: BulkResult = {
		succeeded: succeededIds.length,
		failed: failedIds.length,
		succeededIds,
		failedIds,
		firstError: firstRejection?.reason
	};

	if (options.pruneSelection) {
		setMessageSelection(failedIds);
	}

	/*
	 * Each action patches the flat list in place as its items settle; the
	 * grouped list has no such patch (see groupedListingIsShowing) and used to
	 * keep showing the message until the next sync event — as a row that was
	 * gone, or with counts and a representative that had moved on. It refetches
	 * instead, exactly as the grouped view's own actions do.
	 *
	 * Deliberately not awaited: the store goes to `loading` at once, which is
	 * what the list's restore below waits on, and holding the open message on
	 * screen for a round trip would be a worse answer than a list that redraws
	 * a moment later.
	 */
	if (result.succeeded > 0 && groupedListingIsShowing()) {
		void reloadCurrentConversationsPage();
	}

	if (options.clearDetailIfAffected) {
		const selected = get(selectedMessage);
		if (selected && succeededIds.includes(selected.stableId)) {
			if (succeededIds.length === 1) {
				requestFocusTarget(focusTargetsBeforeMutation.get(succeededIds[0]));
			}
			/*
			 * Closing goes through whoever is showing the detail (see
			 * mail/detailHost.ts): on the mail route that means back to the
			 * folder list, while the search screen closes in place — navigating
			 * to a mail folder from there would drop the user in the inbox with
			 * the results and the query gone. The fallback keeps the mail
			 * behaviour for a selection with no detail mounted.
			 *
			 * keepFocus mirrors closeCurrentMessageDetail. There the reset
			 * demonstrably ate the restore; here the list re-renders (the row is
			 * gone) which gives the restore a second, later attempt, so it lands
			 * either way — the flag keeps this path from depending on that
			 * ordering.
			 */
			const handled = await closeOpenDetail({ removedStableId: selected.stableId });
			if (!handled) {
				clearSelection();
				await goto(currentFolderHref(), { keepFocus: true });
			}
		} else if (succeededIds.length === 1) {
			/*
			 * The removed row was not the open message (Delete on a list row in
			 * off mode, or on a non-selected row next to an open reading pane).
			 * Its cell — or its row-menu trigger — just unmounted, so without a
			 * restore focus falls back to <body>. Hand it to a neighbouring row —
			 * or, when that was the last row, to the empty state. The target is
			 * null when the row was not on the current list page (e.g. the action
			 * came from search results), so nothing to restore.
			 */
			requestFocusTarget(focusTargetsBeforeMutation.get(succeededIds[0]));
		}
	}

	if (unreadBefore && result.succeeded > 0) {
		const ctx = unreadBefore;
		const removedUnread = succeededIds.filter((id) => ctx.ids.has(id)).length;
		// Nothing unread went: a zero adjustment writes the folder list back
		// unchanged, which redraws the sidebar for no news. Same guard the
		// grouped view's own pipeline keeps.
		if (removedUnread > 0) adjustFolderUnread(ctx.accountId, ctx.folderName, -removedUnread);
	}

	if (options.toastKey) {
		pushToast(
			get(_)(options.toastKey, {
				values: {
					count: result.succeeded,
					failed: result.failed,
					...(options.toastValues ?? {})
				}
			}),
			{ tone: result.failed > 0 ? 'error' : 'success' }
		);
	}

	return result;
}

/*
 * Flag mutations patch the list row locally instead of reloading the page:
 * after a successful PATCH the server row equals the optimistic patch, and a
 * reload would re-hit the list endpoint (an extra request that also
 * dispatches a background folder sync) just to redraw identical data. Less
 * churn also keeps the screen-reader experience calm — no list re-render to
 * re-announce.
 */
export async function markMessagesSeen(
	stableIds: readonly string[],
	seen: boolean
): Promise<BulkResult> {
	const result = await executeBulkMessageAction({
		ids: stableIds,
		perItem: async (id) => {
			// Recorded before the request goes out: opening a message marks it read
			// on its own, and a user acting while that is in flight is acting on the
			// newer intention (see message-seen.ts).
			overruleAutoMarkSeen(id, seen);
			await setMessageFlag(id, 'seen', seen);
			markSeenLocally(id, seen);
			patchSelectedMessageDetail(id, { seen });
			invalidateMessage(id);
		},
		// Multi-select bulk action resolves the selection on success, same as
		// delete/move — the page reload that used to clear it as a side effect
		// (messagesState leaving `ready`) is gone.
		pruneSelection: stableIds.length > 1,
		// Bulk call only makes sense in a multi-select context; toast only then.
		toastKey:
			stableIds.length > 1
				? seen
					? 'messages.bulkMarkReadDone'
					: 'messages.bulkMarkUnreadDone'
				: undefined
	});
	announceSingleOutcome(
		stableIds,
		result,
		seen ? 'messages.markedReadAnnounce' : 'messages.markedUnreadAnnounce'
	);
	return result;
}

async function flagMessages(stableIds: readonly string[], flagged: boolean): Promise<BulkResult> {
	const result = await executeBulkMessageAction({
		ids: stableIds,
		perItem: async (id) => {
			await setMessageFlag(id, 'flagged', flagged);
			patchMessageLocally(id, { flagged });
			patchSelectedMessageDetail(id, { flagged });
			invalidateMessage(id);
		},
		pruneSelection: stableIds.length > 1
	});
	announceSingleOutcome(
		stableIds,
		result,
		flagged ? 'messages.flaggedAnnounce' : 'messages.unflaggedAnnounce'
	);
	return result;
}

/*
 * A single flag/seen toggle changes the row visually but was inaudible: no
 * toast (deliberate — the visual state already flips) and no live-region
 * text. Announce the outcome politely; a failure changes nothing on screen
 * either, so it gets a real error toast instead. Only user-invoked toggles
 * route through here — the auto-mark-on-open path lives in message-seen.ts
 * and stays silent by design.
 */
function announceSingleOutcome(
	stableIds: readonly string[],
	result: BulkResult,
	successKey: string
): void {
	if (stableIds.length !== 1) return;
	if (result.succeeded === 1) {
		announcePolite(get(_)(successKey));
	} else if (result.failed === 1) {
		pushToast(toErrorMessage(result.firstError), { tone: 'error' });
	}
}

/**
 * True when any of the targeted messages sits in the trash folder — there a
 * delete is permanent (server-side expunge), not a move to trash, so it needs
 * an explicit confirmation. The folder of each message is resolved from
 * everything the UI is showing: the flat list page, the search results, the
 * grouped list, and the open message detail, whose `folderName` exists for
 * exactly this check.
 *
 * Any of them placing the message in the trash is enough to ask. They can
 * disagree — the detail cache survives a background sync that moved the
 * message, and the store the mounted view does not use keeps whatever it held
 * before — and an extra dialog is the cheap half of being wrong: a false
 * positive costs one press, a false negative permanently deletes without
 * asking. For the same reason an id none of them knows falls back to the
 * folder being listed, which is known even while the list is still loading or
 * errored, and in grouped mode is known only to the conversations store —
 * where its absence used to make the answer "not the trash" by default.
 */
function anyMessageInTrash(stableIds: readonly string[]): boolean {
	const trashRef = get(folderList).find((folder) => folder.role === 'TRASH')?.folderRef;
	if (!trashRef) return false;
	const foldersOf = new Map<string, string[]>();
	const note = (stableId: string, folderName: string) => {
		const known = foldersOf.get(stableId);
		if (known) known.push(folderName);
		else foldersOf.set(stableId, [folderName]);
	};
	const messages = currentMessagesState();
	if (messages.status === 'ready') {
		for (const message of messages.page.content) note(message.stableId, message.folderName);
	}
	const search = get(searchState);
	if (search.status === 'ready') {
		for (const message of search.page.content) note(message.stableId, message.folderName);
	}
	const conversations = get(conversationsState);
	if (conversations.status === 'ready') {
		for (const row of conversations.page.content) note(row.latest.stableId, row.latest.folderName);
	}
	const selected = get(selectedMessage);
	if (selected?.detail) note(selected.stableId, selected.detail.folderName);
	const browsedFolders = listingContexts().map((listing) => listing.folderName);
	return stableIds.some((id) => (foldersOf.get(id) ?? browsedFolders).includes(trashRef));
}

export async function deleteMessages(stableIds: readonly string[]): Promise<BulkResult> {
	if (anyMessageInTrash(stableIds)) {
		const t = get(_);
		const confirmed = await confirmAction({
			title: t('messages.permanentDeleteConfirmTitle'),
			description: t('messages.permanentDeleteConfirm', { values: { count: stableIds.length } }),
			confirmLabel: t('messages.permanentDeleteConfirmAction'),
			cancelLabel: t('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) {
			return { succeeded: 0, failed: 0, succeededIds: [], failedIds: [] };
		}
	}
	const single = stableIds.length === 1;
	// Capture the subject before the row is removed locally, so a single delete
	// can name which message was deleted — screen-reader feedback for an
	// otherwise-silent destructive action.
	const subject = single ? messageSubjectLabel(stableIds[0]) : undefined;
	const result = await executeBulkMessageAction({
		ids: stableIds,
		perItem: async (id) => {
			await deleteMessage(id);
			removeMessageLocally(id);
			invalidateMessage(id);
		},
		pruneSelection: stableIds.length > 1,
		clearDetailIfAffected: true,
		adjustSourceFolderUnread: true,
		// Single delete gets a named toast below; bulk keeps the count summary.
		toastKey: single ? undefined : 'messages.bulkDeleteDone'
	});
	if (single) {
		if (result.succeeded === 1) {
			pushToast(get(_)('toolbar.deleteDoneNamed', { values: { subject: subject ?? '' } }), {
				tone: 'success'
			});
		} else if (result.failed === 1) {
			pushToast(toErrorMessage(result.firstError), { tone: 'error' });
		}
	}
	return result;
}

export async function moveMessages(
	stableIds: readonly string[],
	targetFolderName: string
): Promise<BulkResult> {
	const targetFolder = get(folderList).find((folder) => folder.folderRef === targetFolderName);
	const targetFolderLabel = targetFolder ? folderLabel(targetFolder, get(_)) : targetFolderName;

	return executeBulkMessageAction({
		ids: stableIds,
		perItem: async (id) => {
			await moveMessage(id, { folderRef: targetFolderName });
			removeMessageLocally(id);
			invalidateMessage(id);
		},
		pruneSelection: stableIds.length > 1,
		clearDetailIfAffected: true,
		adjustSourceFolderUnread: true,
		toastKey: stableIds.length > 1 ? 'messages.bulkMoveDone' : 'toolbar.moveDone',
		toastValues: { folder: targetFolderLabel }
	});
}

export async function toggleMessageSeen(stableId: string): Promise<void> {
	const detail = await ensureMessageDetail(stableId);
	await markMessagesSeen([stableId], !detail.seen);
}

export async function toggleMessageFlag(stableId: string): Promise<void> {
	const detail = await ensureMessageDetail(stableId);
	await flagMessages([stableId], !detail.flagged);
}
