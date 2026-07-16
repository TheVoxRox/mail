import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('$app/navigation', () => ({
	goto: vi.fn().mockResolvedValue(undefined)
}));

vi.mock('$app/paths', () => ({
	resolve: vi.fn((path: string) => path)
}));

vi.mock('$lib/api/mailAction.js', () => ({
	deleteMessage: vi.fn().mockResolvedValue(undefined),
	moveMessage: vi.fn().mockResolvedValue(undefined),
	setMessageFlag: vi.fn().mockResolvedValue(undefined)
}));

vi.mock('$lib/api/mailRead.js', () => ({
	getMessageDetail: vi.fn()
}));

vi.mock('$lib/stores/folders.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		folders: writable([]),
		adjustFolderUnread: vi.fn()
	};
});

vi.mock('$lib/stores/search.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		searchState: writable({ status: 'idle' })
	};
});

vi.mock('$lib/stores/confirmDialog.js', () => ({
	confirmAction: vi.fn()
}));

vi.mock('$lib/stores/accounts.js', async () => {
	const { readable } = await import('svelte/store');
	return {
		resolvedActiveAccountId: readable(1)
	};
});

vi.mock('$lib/stores/selectedMessage.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		selectedMessage: writable(null),
		clearSelection: vi.fn(),
		invalidateMessage: vi.fn(),
		patchSelectedMessageDetail: vi.fn(),
		requestListFocusRestore: vi.fn()
	};
});

vi.mock('$lib/stores/messages.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		messagesState: writable({ status: 'idle' }),
		markSeenLocally: vi.fn(),
		patchMessageLocally: vi.fn(),
		removeMessageLocally: vi.fn()
	};
});

vi.mock('$lib/stores/messageSelection.js', () => ({
	setMessageSelection: vi.fn()
}));

vi.mock('$lib/i18n/index.js', async () => {
	const { readable } = await import('svelte/store');
	return {
		_: readable((key: string) => key)
	};
});

vi.mock('$lib/api/errors.js', () => ({
	toErrorMessage: vi.fn((err: unknown) => String(err))
}));

vi.mock('$lib/mail/folderLabel.js', () => ({
	folderLabel: vi.fn((folder: { displayName: string }) => folder.displayName)
}));

vi.mock('$lib/stores/toasts.js', () => ({
	announcePolite: vi.fn(),
	pushToast: vi.fn()
}));

import { deleteMessages } from './mailbox.js';
import { deleteMessage } from '$lib/api/mailAction.js';
import { folders } from '$lib/stores/folders.js';
import { searchState } from '$lib/stores/search.js';
import { confirmAction } from '$lib/stores/confirmDialog.js';
import { messagesState } from '$lib/stores/messages.js';
import type { Writable } from 'svelte/store';
import type { Mock } from 'vitest';

const foldersStore = folders as unknown as Writable<unknown>;
const messagesStore = messagesState as unknown as Writable<unknown>;
const searchStore = searchState as unknown as Writable<unknown>;
const confirmActionMock = confirmAction as Mock;
const deleteMessageMock = deleteMessage as Mock;

const TRASH_REF = 'Trash';

function folderList() {
	return [
		{ displayName: 'Inbox', folderRef: 'INBOX', unreadCount: 0, role: 'INBOX' },
		{ displayName: 'Trash', folderRef: TRASH_REF, unreadCount: 0, role: 'TRASH' }
	];
}

function readyMessages(folderName: string, stableIds: string[]) {
	return {
		status: 'ready',
		context: { accountId: 1, folderName, page: 0, size: 50 },
		page: {
			content: stableIds.map((stableId) => ({ stableId, folderName })),
			totalElements: stableIds.length,
			totalPages: 1,
			number: 0
		}
	};
}

describe('deleteMessages – permanent-delete confirmation in trash', () => {
	beforeEach(() => {
		vi.clearAllMocks();
		foldersStore.set(folderList());
		messagesStore.set({ status: 'idle' });
		searchStore.set({ status: 'idle' });
	});

	it('deletes without confirmation outside the trash folder', async () => {
		messagesStore.set(readyMessages('INBOX', ['m1', 'm2']));

		const result = await deleteMessages(['m1', 'm2']);

		expect(confirmActionMock).not.toHaveBeenCalled();
		expect(deleteMessageMock).toHaveBeenCalledTimes(2);
		expect(result.succeeded).toBe(2);
	});

	it('asks for confirmation and deletes when confirmed in the trash folder', async () => {
		messagesStore.set(readyMessages(TRASH_REF, ['m1', 'm2']));
		confirmActionMock.mockResolvedValue(true);

		const result = await deleteMessages(['m1', 'm2']);

		expect(confirmActionMock).toHaveBeenCalledWith(
			expect.objectContaining({
				title: 'messages.permanentDeleteConfirmTitle',
				tone: 'destructive'
			})
		);
		expect(deleteMessageMock).toHaveBeenCalledTimes(2);
		expect(result.succeeded).toBe(2);
	});

	it('does nothing when the trash confirmation is cancelled', async () => {
		messagesStore.set(readyMessages(TRASH_REF, ['m1', 'm2']));
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['m1', 'm2']);

		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result).toEqual({ succeeded: 0, failed: 0, succeededIds: [], failedIds: [] });
	});

	it('recognises a trash message surfaced through search results', async () => {
		messagesStore.set({ status: 'idle' });
		searchStore.set({
			status: 'ready',
			context: { accountId: 1, query: 'q', page: 0, size: 50 },
			page: {
				content: [{ stableId: 's1', folderName: TRASH_REF }],
				totalElements: 1,
				totalPages: 1,
				number: 0
			}
		});
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['s1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('does not ask for confirmation when no trash folder is known', async () => {
		foldersStore.set([]);
		messagesStore.set(readyMessages(TRASH_REF, ['m1']));

		await deleteMessages(['m1']);

		expect(confirmActionMock).not.toHaveBeenCalled();
		expect(deleteMessageMock).toHaveBeenCalledTimes(1);
	});
});
