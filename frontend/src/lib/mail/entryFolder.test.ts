import { describe, expect, it, vi } from 'vitest';
import type { FolderResponse, FolderRole } from '#lib/types.js';
import { lastFolderRef, pickEntryFolder, rememberFolder } from './entryFolder.js';

vi.mock('$app/paths', () => ({
	resolve: vi.fn((path: string) => path)
}));

function folder(folderRef: string, role: FolderRole = 'USER'): FolderResponse {
	return { folderRef, displayName: folderRef, role, unreadCount: 0, parentRef: null };
}

const LIST = [folder('Archive'), folder('INBOX', 'INBOX'), folder('SENT', 'SENT')];

describe('pickEntryFolder', () => {
	it('opens the Inbox when nothing is remembered', () => {
		expect(pickEntryFolder(LIST)?.folderRef).toBe('INBOX');
	});

	it('opens the first folder of an account without an Inbox', () => {
		expect(pickEntryFolder([folder('Archive'), folder('SENT', 'SENT')])?.folderRef).toBe('Archive');
	});

	it('returns to the remembered folder', () => {
		expect(pickEntryFolder(LIST, 'SENT')?.folderRef).toBe('SENT');
	});

	// Deleted or renamed since it was shown: an empty listing of a folder that
	// is gone helps nobody, the Inbox does.
	it('passes over a remembered folder the account no longer lists', () => {
		expect(pickEntryFolder(LIST, 'Gone')?.folderRef).toBe('INBOX');
	});

	it('has nothing to open for an account without folders', () => {
		expect(pickEntryFolder([], 'SENT')).toBeUndefined();
	});
});

describe('rememberFolder', () => {
	it('keeps the last folder of each account apart', () => {
		rememberFolder(1, 'SENT');
		rememberFolder(2, 'Archive');
		rememberFolder(1, 'DRAFTS');

		expect(lastFolderRef(1)).toBe('DRAFTS');
		expect(lastFolderRef(2)).toBe('Archive');
		expect(lastFolderRef(3)).toBeUndefined();
	});
});
