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
import type { FolderResponse } from '$lib/types.js';

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
