/**
 * The folder list as a tree, for the surfaces that show which folder is a
 * subfolder of which: the mail sidebar nests them as lists, the Move to menu
 * as groups.
 *
 * The backend decides the hierarchy and sends it as `parentRef`, already in
 * display order with every subfolder after its parent (FolderDisplayOrder).
 * The client does not parse `folderRef` to find a parent; it is opaque here as
 * everywhere else.
 */
import type { FolderResponse } from '#lib/types.js';

export interface FolderNode {
	folder: FolderResponse;
	subfolders: FolderNode[];
}

/**
 * Keeps the list's order. A folder whose `parentRef` names no folder listed
 * before it is shown at the top level: the backend never sends one, and a
 * folder in the wrong place still beats a folder missing from the sidebar,
 * which is what a parent that never comes, or a loop, would otherwise cost.
 */
export function folderTree(folders: readonly FolderResponse[]): FolderNode[] {
	const roots: FolderNode[] = [];
	const placed = new Map<string, FolderNode>();
	for (const folder of folders) {
		const node: FolderNode = { folder, subfolders: [] };
		const parent = folder.parentRef == null ? undefined : placed.get(folder.parentRef);
		(parent ? parent.subfolders : roots).push(node);
		placed.set(folder.folderRef, node);
	}
	return roots;
}
