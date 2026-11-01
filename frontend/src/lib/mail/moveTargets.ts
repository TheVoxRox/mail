/**
 * Targets for "move to folder": every folder except the one the messages are
 * already in.
 *
 * Four surfaces offer the action — the detail toolbar, the row actions
 * submenu, and the bulk bars of both list modes — and each resolves the
 * current folder from a different source: the flat listing (`messagesState`),
 * the grouped listing (`conversationsState`), or the row itself in search
 * results, which spans folders. The filter is shared here; resolving *which*
 * folder is current stays with the caller, because there is no single answer.
 */
import type { FolderResponse } from '#lib/types.js';
import { folderTree, type FolderNode } from './folderTree.js';

/**
 * `currentFolderRef` is the empty string while a listing has not loaded yet.
 * That matches no folder, so every folder is offered — deliberately: the menu
 * is disabled on an empty target list, and hiding the action until the listing
 * settles would make the toolbar flicker between states.
 */
export function moveTargetsFor(
	folders: readonly FolderResponse[],
	currentFolderRef: string
): FolderResponse[] {
	return folders.filter((folder) => folder.folderRef !== currentFolderRef);
}

/**
 * A row of the Move to menu: a target, or the targets below a folder, in a
 * group named after it. A menu item has no nesting level the way a list item
 * does, so the group is what tells a screen reader where a subfolder lives:
 * entering it is announced with its name. Chosen with the maintainer to match
 * the nested lists of the sidebar.
 */
export type MoveMenuEntry =
	| { kind: 'item'; folder: FolderResponse }
	| { kind: 'group'; parent: FolderResponse; entries: MoveMenuEntry[] };

/**
 * The hierarchy comes from the whole folder list, not from `targets`: the
 * folder the messages are in is not a target, yet its subfolders are, and
 * their group still carries its name. A group with no target in it is left out.
 */
export function moveMenuEntries(
	folders: readonly FolderResponse[],
	targets: readonly FolderResponse[]
): MoveMenuEntry[] {
	const targetRefs = new Set(targets.map((folder) => folder.folderRef));
	const entriesOf = (nodes: readonly FolderNode[]): MoveMenuEntry[] =>
		nodes.flatMap(({ folder, subfolders }) => {
			const entries: MoveMenuEntry[] = [];
			if (targetRefs.has(folder.folderRef)) entries.push({ kind: 'item', folder });
			const below = entriesOf(subfolders);
			if (below.length > 0) entries.push({ kind: 'group', parent: folder, entries: below });
			return entries;
		});
	return entriesOf(folderTree(folders));
}
