import { describe, it, expect, vi, beforeEach } from 'vitest';
import { readable } from 'svelte/store';

// The grouped bulk pipeline is store-driven; stub every collaborator so the unit
// test isolates the orchestration (esp. the trash permanent-delete confirmation
// gate — the one data-destructive branch e2e cannot cover without reshaping the
// shared trash fixtures).
vi.mock('$lib/api/mailAction.js', () => ({
	deleteMessage: vi.fn(() => Promise.resolve()),
	moveMessage: vi.fn(() => Promise.resolve()),
	setMessageFlag: vi.fn(() => Promise.resolve())
}));
vi.mock('$lib/stores/confirmDialog.js', () => ({ confirmAction: vi.fn() }));
vi.mock('$lib/stores/conversations.js', () => ({
	reloadCurrentConversationsPage: vi.fn(() => Promise.resolve())
}));
vi.mock('$lib/stores/folders.js', () => ({ adjustFolderUnread: vi.fn(), folders: readable([]) }));
vi.mock('$lib/stores/selectedMessage.js', () => ({
	selectedMessage: readable(null),
	invalidateMessage: vi.fn()
}));
vi.mock('$lib/mail/detailHost.js', () => ({ closeOpenDetail: vi.fn(() => Promise.resolve(true)) }));
vi.mock('$lib/mail/folderLabel.js', () => ({ folderLabel: () => 'Folder' }));
vi.mock('$lib/i18n/index.js', () => ({ _: readable((key: string) => key) }));
vi.mock('$lib/stores/toasts.js', () => ({ pushToast: vi.fn(), announcePolite: vi.fn() }));

import {
	deleteConversationMembers,
	flagConversationMembers,
	moveConversationMembers,
	type ConversationBulkContext
} from './conversationBulk.js';
import { deleteMessage, moveMessage, setMessageFlag } from '$lib/api/mailAction.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import { reloadCurrentConversationsPage } from '$lib/stores/conversations.js';
import { invalidateMessage } from '$lib/stores/selectedMessage.js';
import { announcePolite, pushToast } from '$lib/stores/toasts.js';

function ctx(folderRole?: string): ConversationBulkContext {
	return { accountId: 1, folderName: 'X', folderRole, unreadMemberIds: [] };
}

describe('conversationBulk', () => {
	beforeEach(() => vi.clearAllMocks());

	it('deletes every member without confirmation outside the trash', async () => {
		const done = await deleteConversationMembers(['a', 'b', 'c'], ctx('ARCHIVE'));
		expect(done).toBe(true);
		expect(confirmAction).not.toHaveBeenCalled();
		expect(deleteMessage).toHaveBeenCalledTimes(3);
		expect(reloadCurrentConversationsPage).toHaveBeenCalledOnce();
	});

	it('asks for confirmation in the trash and aborts when declined', async () => {
		vi.mocked(confirmAction).mockResolvedValue(false);
		const done = await deleteConversationMembers(['a', 'b'], ctx('TRASH'));
		expect(done).toBe(false);
		expect(confirmAction).toHaveBeenCalledOnce();
		expect(deleteMessage).not.toHaveBeenCalled();
		expect(reloadCurrentConversationsPage).not.toHaveBeenCalled();
	});

	it('permanently deletes in the trash once confirmed', async () => {
		vi.mocked(confirmAction).mockResolvedValue(true);
		const done = await deleteConversationMembers(['a'], ctx('TRASH'));
		expect(done).toBe(true);
		expect(deleteMessage).toHaveBeenCalledTimes(1);
		expect(reloadCurrentConversationsPage).toHaveBeenCalledOnce();
	});

	it('is a no-op for an empty selection', async () => {
		const done = await deleteConversationMembers([], ctx('ARCHIVE'));
		expect(done).toBe(false);
		expect(deleteMessage).not.toHaveBeenCalled();
	});

	it('moves every member to the target folder', async () => {
		const done = await moveConversationMembers(['a', 'b'], 'JUNK', ctx('INBOX'));
		expect(done).toBe(true);
		expect(moveMessage).toHaveBeenCalledTimes(2);
		expect(moveMessage).toHaveBeenCalledWith('a', { folderRef: 'JUNK' });
		expect(reloadCurrentConversationsPage).toHaveBeenCalledOnce();
	});

	it('invalidates the cached detail of every mutated message', async () => {
		// The cache otherwise keeps the pre-move folder, and the trash
		// confirmation elsewhere reads that folder off the open detail.
		await moveConversationMembers(['a', 'b'], 'TRASH', ctx('INBOX'));
		expect(invalidateMessage).toHaveBeenCalledTimes(2);
		expect(invalidateMessage).toHaveBeenCalledWith('a');
		expect(invalidateMessage).toHaveBeenCalledWith('b');
	});

	it('stars one message with a polite announcement and no toast', async () => {
		// The row's star flips visibly, so a toast would be noise — but a screen
		// reader needs the announcement or the toggle is silent. Same split the
		// flat list makes in mailbox.ts.
		const done = await flagConversationMembers(['a'], true, ctx('INBOX'));
		expect(done).toBe(true);
		expect(setMessageFlag).toHaveBeenCalledWith('a', 'flagged', true);
		expect(announcePolite).toHaveBeenCalledWith('messages.flaggedAnnounce');
		expect(pushToast).not.toHaveBeenCalled();
		expect(reloadCurrentConversationsPage).toHaveBeenCalledOnce();
	});

	it('unstars one message with the matching announcement', async () => {
		await flagConversationMembers(['a'], false, ctx('INBOX'));
		expect(setMessageFlag).toHaveBeenCalledWith('a', 'flagged', false);
		expect(announcePolite).toHaveBeenCalledWith('messages.unflaggedAnnounce');
	});

	it('reports a counted toast for more than one message instead of the singular announcement', async () => {
		// The polite announcement describes one message; for a batch it would say
		// neither how many were starred nor that any failed.
		const done = await flagConversationMembers(['a', 'b'], true, ctx('INBOX'));
		expect(done).toBe(true);
		expect(announcePolite).not.toHaveBeenCalled();
		expect(pushToast).toHaveBeenCalledOnce();
	});

	it('reports a toast when the only starred message failed', async () => {
		vi.mocked(setMessageFlag).mockRejectedValueOnce(new Error('offline'));
		const done = await flagConversationMembers(['a'], true, ctx('INBOX'));
		expect(done).toBe(false);
		expect(announcePolite).not.toHaveBeenCalled();
		expect(pushToast).toHaveBeenCalledOnce();
	});

	it('does not star an empty selection', async () => {
		const done = await flagConversationMembers([], true, ctx('INBOX'));
		expect(done).toBe(false);
		expect(setMessageFlag).not.toHaveBeenCalled();
		expect(reloadCurrentConversationsPage).not.toHaveBeenCalled();
	});

	it('keeps the selection when every item failed', async () => {
		// `done: false` is what tells the caller not to clear the selection, so a
		// wholly failed batch can be retried without reselecting.
		vi.mocked(deleteMessage).mockRejectedValue(new Error('offline'));
		const done = await deleteConversationMembers(['a', 'b'], ctx('ARCHIVE'));
		expect(done).toBe(false);
		expect(invalidateMessage).not.toHaveBeenCalled();
	});
});
