import { describe, expect, it } from 'vitest';
import { folderTree, type FolderNode } from './folderTree.js';
import type { FolderResponse } from '#lib/types.js';

function folder(folderRef: string, parentRef: string | null = null): FolderResponse {
	return { displayName: folderRef, folderRef, unreadCount: 0, role: 'USER', parentRef };
}

/** The tree as nested refs, which is what the assertions are about. */
function shape(nodes: FolderNode[]): unknown[] {
	return nodes.map(({ folder, subfolders }) =>
		subfolders.length > 0 ? [folder.folderRef, shape(subfolders)] : folder.folderRef
	);
}

describe('folderTree', () => {
	it('nests each folder under its parentRef and keeps the list order', () => {
		const tree = folderTree([
			folder('INBOX'),
			folder('INBOX/Clients', 'INBOX'),
			folder('Sent'),
			folder('Projects'),
			folder('Projects/2024', 'Projects'),
			folder('Projects/2024/Q1', 'Projects/2024'),
			folder('Projects/2025', 'Projects')
		]);

		expect(shape(tree)).toEqual([
			['INBOX', ['INBOX/Clients']],
			'Sent',
			['Projects', [['Projects/2024', ['Projects/2024/Q1']], 'Projects/2025']]
		]);
	});

	it('does not parse folderRef: a ref that looks nested is top-level without a parentRef', () => {
		expect(shape(folderTree([folder('Projects'), folder('Projects/2024')]))).toEqual([
			'Projects',
			'Projects/2024'
		]);
	});

	it('shows a folder at the top level when its parent is not listed before it', () => {
		// Neither happens with the backend's list; both would otherwise drop
		// folders from the sidebar. A loop is cut where it is entered.
		const missing = folder('Kept', 'Gone');
		const loop = [folder('A', 'B'), folder('B', 'A')];

		expect(shape(folderTree([missing, ...loop]))).toEqual(['Kept', ['A', ['B']]]);
	});
});
