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
vi.mock('$lib/stores/selectedMessage.js', () => ({ selectedMessage: readable(null) }));
vi.mock('$lib/mail/detailHost.js', () => ({ closeOpenDetail: vi.fn(() => Promise.resolve(true)) }));
vi.mock('$lib/mail/folderLabel.js', () => ({ folderLabel: () => 'Folder' }));
vi.mock('$lib/i18n/index.js', () => ({ _: readable((key: string) => key) }));
vi.mock('$lib/stores/toasts.js', () => ({ pushToast: vi.fn(), announcePolite: vi.fn() }));

import {
	deleteConversationMembers,
	moveConversationMembers,
	type ConversationBulkContext
} from './conversationBulk.js';
import { deleteMessage, moveMessage } from '$lib/api/mailAction.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import { reloadCurrentConversationsPage } from '$lib/stores/conversations.js';

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
});
