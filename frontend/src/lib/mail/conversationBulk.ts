/**
 * Bulk mutations for the conversation-grouped view (threading Phase 2).
 *
 * Unlike {@link mailbox.ts}, which is bound to the flat `messages` store, these
 * operate on already-resolved member stableIds and refresh the `conversations`
 * store instead. Whole-conversation semantics: the caller resolves each selected
 * conversation to all its folder members before calling here, so a delete/move
 * acts on the entire thread in the folder, not just the representative.
 *
 * A deliberately separate pipeline (not a mailbox refactor) so the heavily used,
 * data-destructive flat delete/move path stays untouched. The trash confirmation
 * is driven by the folder role the grouped view already knows — mailbox's
 * cross-store `anyMessageInTrash` resolution does not see the conversations
 * store and would miss the confirmation here.
 */
import { get } from 'svelte/store';
import { deleteMessage, moveMessage, setMessageFlag } from '$lib/api/mailAction.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import { reloadCurrentConversationsPage } from '$lib/stores/conversations.js';
import { adjustFolderUnread, folders as folderList } from '$lib/stores/folders.js';
import { selectedMessage } from '$lib/stores/selectedMessage.js';
import { closeOpenDetail } from '$lib/mail/detailHost.js';
import { folderLabel } from '$lib/mail/folderLabel.js';
import { _ } from '$lib/i18n/index.js';
import { announcePolite, pushToast } from '$lib/stores/toasts.js';

export interface ConversationBulkContext {
	accountId: number;
	folderName: string;
	/** Role of the folder in view — `TRASH` makes delete a permanent expunge. */
	folderRole: string | undefined;
	/** Member ids currently unread, for the optimistic source-folder badge. */
	unreadMemberIds: readonly string[];
}

interface RunOutcome {
	succeeded: string[];
	failed: string[];
}

async function runPerItem(
	ids: readonly string[],
	perItem: (stableId: string) => Promise<unknown>
): Promise<RunOutcome> {
	const unique = Array.from(new Set(ids));
	const settled = await Promise.allSettled(unique.map((id) => perItem(id)));
	const succeeded = unique.filter((_, index) => settled[index]?.status === 'fulfilled');
	const failed = unique.filter((_, index) => settled[index]?.status !== 'fulfilled');
	return { succeeded, failed };
}

function reportOutcome(
	outcome: RunOutcome,
	toastKey: string,
	extraValues: Record<string, string | number> = {}
): void {
	pushToast(
		get(_)(toastKey, {
			values: { count: outcome.succeeded.length, failed: outcome.failed.length, ...extraValues }
		}),
		{ tone: outcome.failed.length > 0 ? 'error' : 'success' }
	);
}

/** Closes the reading-pane detail if the open message was among those removed. */
async function closeDetailIfRemoved(removedIds: readonly string[]): Promise<void> {
	const open = get(selectedMessage)?.stableId;
	if (open && removedIds.includes(open)) await closeOpenDetail({ removedStableId: open });
}

export async function deleteConversationMembers(
	memberIds: readonly string[],
	ctx: ConversationBulkContext
): Promise<boolean> {
	if (memberIds.length === 0) return false;
	if (ctx.folderRole === 'TRASH') {
		const t = get(_);
		const confirmed = await confirmAction({
			title: t('messages.permanentDeleteConfirmTitle'),
			description: t('messages.permanentDeleteConfirm', { values: { count: memberIds.length } }),
			confirmLabel: t('messages.permanentDeleteConfirmAction'),
			cancelLabel: t('common.cancel'),
			tone: 'destructive'
		});
		if (!confirmed) return false;
	}
	const outcome = await runPerItem(memberIds, (id) => deleteMessage(id));
	await closeDetailIfRemoved(outcome.succeeded);
	const unread = new Set(ctx.unreadMemberIds);
	const removedUnread = outcome.succeeded.filter((id) => unread.has(id)).length;
	if (removedUnread > 0) adjustFolderUnread(ctx.accountId, ctx.folderName, -removedUnread);
	await reloadCurrentConversationsPage();
	reportOutcome(outcome, 'messages.bulkDeleteDone');
	return true;
}

export async function moveConversationMembers(
	memberIds: readonly string[],
	targetFolderRef: string,
	ctx: ConversationBulkContext
): Promise<boolean> {
	if (memberIds.length === 0) return false;
	const target = get(folderList).find((folder) => folder.folderRef === targetFolderRef);
	const label = target ? folderLabel(target, get(_)) : targetFolderRef;
	const outcome = await runPerItem(memberIds, (id) =>
		moveMessage(id, { folderRef: targetFolderRef })
	);
	await closeDetailIfRemoved(outcome.succeeded);
	const unread = new Set(ctx.unreadMemberIds);
	const removedUnread = outcome.succeeded.filter((id) => unread.has(id)).length;
	if (removedUnread > 0) adjustFolderUnread(ctx.accountId, ctx.folderName, -removedUnread);
	await reloadCurrentConversationsPage();
	reportOutcome(outcome, 'messages.bulkMoveDone', { folder: label });
	return true;
}

export async function markConversationMembersSeen(
	memberIds: readonly string[],
	seen: boolean,
	ctx: ConversationBulkContext
): Promise<boolean> {
	if (memberIds.length === 0) return false;
	const outcome = await runPerItem(memberIds, (id) => setMessageFlag(id, 'seen', seen));
	// Marking read clears unread members; marking unread re-adds members that were seen.
	const unread = new Set(ctx.unreadMemberIds);
	const delta = seen
		? -outcome.succeeded.filter((id) => unread.has(id)).length
		: outcome.succeeded.filter((id) => !unread.has(id)).length;
	if (delta !== 0) adjustFolderUnread(ctx.accountId, ctx.folderName, delta);
	await reloadCurrentConversationsPage();
	reportOutcome(outcome, seen ? 'messages.bulkMarkReadDone' : 'messages.bulkMarkUnreadDone');
	return true;
}

/** Politely announces that bulk actions became available (first selection). */
export function announceBulkActionsAvailable(): void {
	announcePolite(get(_)('messages.bulkActionsAvailable'));
}
