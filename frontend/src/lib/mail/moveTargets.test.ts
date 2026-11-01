import { describe, it, expect } from 'vitest';
import { moveMenuEntries, moveTargetsFor, type MoveMenuEntry } from './moveTargets.js';
import type { FolderResponse } from '#lib/types.js';

function folder(
	folderRef: string,
	displayName = folderRef,
	parentRef: string | null = null
): FolderResponse {
	return { displayName, folderRef, unreadCount: 0, role: 'USER', parentRef };
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

describe('moveMenuEntries', () => {
	const all = [
		folder('INBOX'),
		folder('Projekty'),
		folder('Projekty/2024', '2024', 'Projekty'),
		folder('Projekty/2024/Q1', 'Q1', 'Projekty/2024'),
		folder('Projekty/2025', '2025', 'Projekty'),
		folder('Faktury')
	];

	/** Items by ref, groups as `[parent ref, entries]`. */
	function shape(entries: MoveMenuEntry[]): unknown[] {
		return entries.map((entry) =>
			entry.kind === 'item'
				? entry.folder.folderRef
				: [entry.parent.folderRef, shape(entry.entries)]
		);
	}

	it('puts the subfolders of a folder in a group after it, nested as deep as they go', () => {
		expect(shape(moveMenuEntries(all, moveTargetsFor(all, 'INBOX')))).toEqual([
			'Projekty',
			['Projekty', ['Projekty/2024', ['Projekty/2024', ['Projekty/2024/Q1']], 'Projekty/2025']],
			'Faktury'
		]);
	});

	it("keeps the group of the current folder's subfolders, named after it, without the folder itself", () => {
		expect(shape(moveMenuEntries(all, moveTargetsFor(all, 'Projekty')))).toEqual([
			'INBOX',
			['Projekty', ['Projekty/2024', ['Projekty/2024', ['Projekty/2024/Q1']], 'Projekty/2025']],
			'Faktury'
		]);
	});

	it('leaves out a group whose only folder is the current one', () => {
		expect(shape(moveMenuEntries(all, moveTargetsFor(all, 'Projekty/2024/Q1')))).toEqual([
			'INBOX',
			'Projekty',
			['Projekty', ['Projekty/2024', 'Projekty/2025']],
			'Faktury'
		]);
	});
});
