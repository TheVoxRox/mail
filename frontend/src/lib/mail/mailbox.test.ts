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
		requestListFocusRestore: vi.fn(),
		requestEmptyListFocus: vi.fn()
	};
});

vi.mock('$lib/stores/conversations.js', async () => {
	const { writable } = await import('svelte/store');
	return {
		conversationsState: writable({ status: 'idle' })
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
		_: readable((key: string, options?: { values?: Record<string, unknown> }) =>
			options?.values ? `${key} ${JSON.stringify(options.values)}` : key
		)
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
import { conversationsState } from '$lib/stores/conversations.js';
import { pushToast } from '$lib/stores/toasts.js';
import { selectedMessage } from '$lib/stores/selectedMessage.js';
import type { Writable } from 'svelte/store';
import type { Mock } from 'vitest';

const foldersStore = folders as unknown as Writable<unknown>;
const messagesStore = messagesState as unknown as Writable<unknown>;
const searchStore = searchState as unknown as Writable<unknown>;
const selectedStore = selectedMessage as unknown as Writable<unknown>;
const conversationsStore = conversationsState as unknown as Writable<unknown>;
const pushToastMock = pushToast as Mock;
const confirmActionMock = confirmAction as Mock;
const deleteMessageMock = deleteMessage as Mock;

const TRASH_REF = 'Trash';

function folderList() {
	return [
		{ displayName: 'Inbox', folderRef: 'INBOX', unreadCount: 0, role: 'INBOX' },
		{ displayName: 'Trash', folderRef: TRASH_REF, unreadCount: 0, role: 'TRASH' }
	];
}

interface RowSpec {
	stableId: string;
	subject?: string;
	/** Only when the row is not in the folder being listed. */
	folderName?: string;
}

function readyRows(folderName: string, rows: RowSpec[]) {
	return {
		status: 'ready',
		context: { accountId: 1, folderName, page: 0, size: 50 },
		page: {
			content: rows.map((row) => ({ folderName, ...row })),
			totalElements: rows.length,
			totalPages: 1,
			number: 0
		}
	};
}

function readyMessages(folderName: string, stableIds: string[]) {
	return readyRows(
		folderName,
		stableIds.map((stableId) => ({ stableId }))
	);
}

/** The grouped view's store: one row per conversation, keyed by its newest message. */
function readyConversations(folderName: string, rows: RowSpec[]) {
	const flat = readyRows(folderName, rows);
	return {
		...flat,
		page: {
			...flat.page,
			content: flat.page.content.map((latest) => ({
				threadId: `t-${latest.stableId}`,
				latest,
				messageCount: 1,
				unreadCount: 0
			}))
		}
	};
}

function openMessage(stableId: string, detail: { subject?: string; folderName: string }) {
	return { stableId, detail, content: null, loading: false, error: null, notFound: false };
}

describe('deleteMessages – permanent-delete confirmation in trash', () => {
	beforeEach(() => {
		vi.clearAllMocks();
		foldersStore.set(folderList());
		messagesStore.set({ status: 'idle' });
		searchStore.set({ status: 'idle' });
		conversationsStore.set({ status: 'idle' });
		selectedStore.set(null);
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

	it('resolves the folder from the open message detail when no list row shows it', async () => {
		// Detail open, list elsewhere (e.g. paged away) — the detail's own
		// folderName is the only source and must still trigger the confirmation.
		messagesStore.set(readyMessages('INBOX', ['other']));
		selectedStore.set({
			stableId: 'd1',
			detail: { folderName: TRASH_REF },
			content: null,
			loading: false,
			error: null,
			notFound: false
		});
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['d1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('lets a fresh trash list row win over a stale cached detail', async () => {
		// The detail cache is not invalidated by background syncs, so a message
		// moved into the trash elsewhere can still carry its old folder in the
		// open detail; the freshly fetched list row must win, otherwise the
		// purge would skip the confirmation.
		messagesStore.set(readyMessages(TRASH_REF, ['m1']));
		selectedStore.set({
			stableId: 'm1',
			detail: { folderName: 'INBOX' },
			content: null,
			loading: false,
			error: null,
			notFound: false
		});
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['m1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('uses the browsed folder even while the trash list is still loading', async () => {
		// Deep-link onto a trash message: the detail toolbar's Delete is live
		// before either the list or the detail resolves, and the loading state
		// already knows which folder it is fetching.
		messagesStore.set({
			status: 'loading',
			context: { accountId: 1, folderName: TRASH_REF, page: 0, size: 50 }
		});
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['d1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('falls back to the browsed folder for an id nothing on screen resolves', async () => {
		// Browsing the trash, deleting an id the current page no longer shows:
		// unresolvable must fail safe — confirm rather than purge silently.
		messagesStore.set(readyMessages(TRASH_REF, ['m1']));
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['paged-away']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('resolves the folder from a grouped row', async () => {
		// The grouped view lists from its own store, and the flat one it
		// replaces is never loaded: without this store the row was invisible
		// and the purge went ahead unasked.
		conversationsStore.set(
			readyConversations('INBOX', [{ stableId: 'c1', folderName: TRASH_REF }])
		);

		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['c1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('uses the browsed folder while the grouped trash list is still loading', async () => {
		// Delete acts on the open message as soon as the route knows its id, so
		// in grouped mode it runs with no list row and no detail yet — the state
		// in which nothing at all used to place the message in the trash.
		conversationsStore.set({
			status: 'loading',
			context: { accountId: 1, folderName: TRASH_REF, page: 0, size: 50 }
		});
		selectedStore.set({
			stableId: 'c1',
			detail: null,
			content: null,
			loading: true,
			error: null,
			notFound: false
		});
		confirmActionMock.mockResolvedValue(false);

		const result = await deleteMessages(['c1']);

		expect(confirmActionMock).toHaveBeenCalledOnce();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		expect(result.succeeded).toBe(0);
	});

	it('does not use the fallback outside the trash folder', async () => {
		messagesStore.set(readyMessages('INBOX', ['m1']));

		await deleteMessages(['unknown-id']);

		expect(confirmActionMock).not.toHaveBeenCalled();
		expect(deleteMessageMock).toHaveBeenCalledTimes(1);
	});
});

describe('deleteMessages - the subject the single-delete outcome names', () => {
	beforeEach(() => {
		vi.clearAllMocks();
		foldersStore.set(folderList());
		messagesStore.set({ status: 'idle' });
		searchStore.set({ status: 'idle' });
		conversationsStore.set({ status: 'idle' });
		selectedStore.set(null);
	});

	it('takes the subject from the flat list row', async () => {
		messagesStore.set(readyRows('INBOX', [{ stableId: 'm1', subject: 'Quarterly report' }]));

		await deleteMessages(['m1']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('Quarterly report'), {
			tone: 'success'
		});
	});

	it('takes the subject of the open message while the grouped view is listing', async () => {
		// The grouped view has its own store and leaves the flat one idle, yet
		// the detail toolbar, the Delete shortcut and the palette all delete
		// through here - which used to announce "(no subject)" for every open
		// message in the mode that is on by default.
		conversationsStore.set(readyConversations('INBOX', [{ stableId: 'c1', subject: 'Thread' }]));
		selectedStore.set(openMessage('d1', { subject: 'Invoice 14', folderName: 'INBOX' }));

		await deleteMessages(['d1']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('Invoice 14'), {
			tone: 'success'
		});
	});

	it('takes the subject from a grouped row', async () => {
		conversationsStore.set(readyConversations('INBOX', [{ stableId: 'c1', subject: 'Standup' }]));

		await deleteMessages(['c1']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('Standup'), {
			tone: 'success'
		});
	});

	it('takes the subject from a search result row', async () => {
		// Search results are a third list, and the message opened from one is
		// off the browsed folder's page as often as not.
		searchStore.set(readyRows('ARCHIVE', [{ stableId: 's1', subject: 'Contract draft' }]));

		await deleteMessages(['s1']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('Contract draft'), {
			tone: 'success'
		});
	});

	it('falls back when nothing on screen knows the message', async () => {
		messagesStore.set(readyRows('INBOX', [{ stableId: 'other', subject: 'Some other mail' }]));

		await deleteMessages(['paged-away']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('messages.noSubject'), {
			tone: 'success'
		});
	});

	it('falls back for a message whose subject is blank', async () => {
		messagesStore.set(readyRows('INBOX', [{ stableId: 'm1', subject: '   ' }]));

		await deleteMessages(['m1']);

		expect(pushToastMock).toHaveBeenCalledWith(expect.stringContaining('messages.noSubject'), {
			tone: 'success'
		});
	});
});
