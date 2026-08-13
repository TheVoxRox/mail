import { describe, it, expect } from 'vitest';
import { moveTargetsFor } from './moveTargets.js';
import type { FolderResponse } from '$lib/types.js';

function folder(folderRef: string, displayName = folderRef): FolderResponse {
	return { displayName, folderRef, unreadCount: 0, role: 'USER' };
}

describe('moveTargetsFor', () => {
	const all = [folder('INBOX'), folder('Archiv'), folder('Faktury')];

	it('drops the folder the messages are already in', () => {
		expect(moveTargetsFor(all, 'Archiv').map((f) => f.folderRef)).toEqual(['INBOX', 'Faktury']);
	});

	it('keeps the sidebar order of the remaining folders', () => {
		expect(moveTargetsFor(all, 'INBOX').map((f) => f.folderRef)).toEqual(['Archiv', 'Faktury']);
	});

	it('offers every folder while no listing has loaded', () => {
		// '' is the "not loaded yet" sentinel from messagesState/conversationsState.
		expect(moveTargetsFor(all, '')).toHaveLength(3);
	});

	it('matches on folderRef, not on the display name', () => {
		// The label is localized for special-use roles, so only the IMAP path can
		// identify the current folder.
		expect(moveTargetsFor([folder('INBOX', 'Inbox mailbox')], 'Inbox mailbox')).toHaveLength(1);
	});

	it('returns an empty list for an account with a single folder', () => {
		expect(moveTargetsFor([folder('INBOX')], 'INBOX')).toEqual([]);
	});
});
