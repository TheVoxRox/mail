/**
 * Maps a `FolderResponse` to a localized label based on its role.
 *
 * The role is the source of truth — the backend detects it via RFC 6154
 * SPECIAL-USE + name fallback (`FolderRole.fromNameFallback`). For USER
 * folders the server's original `displayName` is shown, because it cannot
 * be translated — the user named it themselves.
 *
 * `folderRef` (the IMAP path) is **never** changed here — it is an opaque
 * key for navigation and the backend API.
 */

import type { FolderResponse, FolderRole } from '$lib/types.js';

/**
 * Structurally compatible with `MessageFormatter` from svelte-i18n (`$_` /
 * `get(_)`). Here we only need the key id, no values — role translations
 * are static strings.
 */
type FolderLabelFn = (id: string) => string;

const ROLE_KEYS: Record<Exclude<FolderRole, 'USER'>, string> = {
	INBOX: 'folders.role.inbox',
	SENT: 'folders.role.sent',
	DRAFTS: 'folders.role.drafts',
	NEWSLETTERS: 'folders.role.newsletters',
	ARCHIVE: 'folders.role.archive',
	JUNK: 'folders.role.junk',
	TRASH: 'folders.role.trash'
};

export function folderLabel(folder: FolderResponse, t: FolderLabelFn): string {
	if (folder.role === 'USER') return folder.displayName;
	return t(ROLE_KEYS[folder.role]);
}
