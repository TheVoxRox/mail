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
import { resolve } from '$app/paths';
import { get } from 'svelte/store';
import { deleteMessage, moveMessage, setMessageFlag } from '$lib/api/mailAction.js';
import { getMessageDetail } from '$lib/api/mailRead.js';
import { folders as folderList, refreshFolders } from '$lib/stores/folders.js';
import { resolvedActiveAccountId } from '$lib/stores/accounts.js';
import {
	clearSelection,
	invalidateMessage,
	patchSelectedMessageDetail,
	requestListFocusRestore,
	selectedMessage
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
import { folderLabel } from '$lib/mail/folderLabel.js';
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
	/** On success refreshes folder counts (for delete/move). */
	refreshFoldersAfterSuccess?: boolean;
	/** i18n key for the toast with {count, failed}. */
	toastKey?: string;
	/** Extra values for the toast (e.g. folder). */
	toastValues?: Record<string, string | number>;
}

function currentMessagesState(): MessagesState {
	return get(messagesState);
}

function currentFolderHref(): string {
	const state = currentMessagesState();
	if (state.status === 'idle') {
		return resolve('/');
	}
	return resolve('/mail/[accountId]/[folderName]', {
		accountId: String(state.context.accountId),
		folderName: encodeURIComponent(state.context.folderName)
	});
}

function focusTargetAfterRemoving(stableId: string): string | null {
	const state = currentMessagesState();
	if (state.status !== 'ready') return null;
	const index = state.page.content.findIndex((message) => message.stableId === stableId);
	if (index < 0) return null;
	return state.page.content[index + 1]?.stableId ?? state.page.content[index - 1]?.stableId ?? null;
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
	const focusTargetsBeforeMutation = new Map<string, string | null>();
	if (options.clearDetailIfAffected) {
		for (const id of ids) {
			focusTargetsBeforeMutation.set(id, focusTargetAfterRemoving(id));
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

	if (options.clearDetailIfAffected) {
		const selected = get(selectedMessage);
		if (selected && succeededIds.includes(selected.stableId)) {
			if (succeededIds.length === 1) {
				requestListFocusRestore(focusTargetsBeforeMutation.get(succeededIds[0]) ?? null);
			}
			clearSelection();
			await goto(currentFolderHref());
		} else if (succeededIds.length === 1) {
			/*
			 * The removed row was not the open message (Delete on a list row in
			 * off mode, or on a non-selected row next to an open reading pane).
			 * Its cell — or its row-menu trigger — just unmounted, so without a
			 * restore focus falls back to <body>. Hand it to a neighbouring row;
			 * the target is null when the row was not on the current list page
			 * (e.g. the action came from search results), so nothing to restore.
			 */
			const target = focusTargetsBeforeMutation.get(succeededIds[0]);
			if (target) requestListFocusRestore(target);
		}
	}

	if (options.refreshFoldersAfterSuccess && result.succeeded > 0) {
		const state = currentMessagesState();
		const accountId =
			state.status === 'idle' ? get(resolvedActiveAccountId) : state.context.accountId;
		if (accountId != null) void refreshFolders(accountId);
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

export async function deleteMessages(stableIds: readonly string[]): Promise<BulkResult> {
	return executeBulkMessageAction({
		ids: stableIds,
		perItem: async (id) => {
			await deleteMessage(id);
			removeMessageLocally(id);
			invalidateMessage(id);
		},
		pruneSelection: stableIds.length > 1,
		clearDetailIfAffected: true,
		refreshFoldersAfterSuccess: true,
		toastKey: stableIds.length > 1 ? 'messages.bulkDeleteDone' : 'toolbar.deleteDone'
	});
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
		refreshFoldersAfterSuccess: true,
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
