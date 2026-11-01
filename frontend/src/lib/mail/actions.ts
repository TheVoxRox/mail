/**
 * Navigation helpers for mail screens. Message mutations live in
 * `mail/mailbox.ts` and are imported directly by their consumers.
 */
import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { get } from 'svelte/store';
import { triggerAccountSync } from '#lib/api/mailAction.js';
import { folders, loadFolders, refreshFolders } from '#lib/stores/folders.js';
import { resolvedActiveAccountId, setActiveAccount } from '#lib/stores/accounts.js';
import {
	clearSelection,
	requestListFocusRestore,
	selectedMessage
} from '#lib/stores/selectedMessage.js';
import {
	currentWorkspaceMode,
	workspaceHref,
	type WorkspaceMode
} from '#lib/stores/workspaceMode.js';
import { currentFolderHref } from '#lib/mail/currentListing.js';
import { pickEntryFolder } from '#lib/mail/entryFolder.js';

export async function goToWorkspaceMode(mode: WorkspaceMode): Promise<void> {
	await goto(workspaceHref(mode));
}

export async function goToAccounts(): Promise<void> {
	await goto(resolve('settings/accounts'));
}

export async function goToSearch(): Promise<void> {
	const accountId = get(resolvedActiveAccountId);
	if (accountId == null) return;
	await goto(resolve('/search/[accountId]', { accountId: String(accountId) }));
}

export async function goToCompose(): Promise<void> {
	await goto(resolve('compose'));
}

export async function goToFolder(accountId: number, folderName: string): Promise<void> {
	setActiveAccount(accountId);
	await goto(
		resolve('/mail/[accountId]/[folderName]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName)
		})
	);
}

/**
 * The active account's Inbox (Ctrl+Shift+I), whatever folder Mail would
 * reopen on: `/` returns to the last folder shown (entryFolder.ts), so this
 * picks the folder itself and leaves the memory out. An account without an
 * Inbox opens on its first folder, as `/` would on a first visit. With no
 * account, or no folder list to pick from, `/` shows the state that explains
 * why.
 */
export async function goToInbox(): Promise<void> {
	const accountId = get(resolvedActiveAccountId);
	if (accountId == null) {
		await goto(resolve('/'));
		return;
	}
	const cached = get(folders);
	const list = cached.length > 0 ? cached : await loadFolders(accountId).catch(() => []);
	const inbox = pickEntryFolder(list);
	if (!inbox) {
		await goto(resolve('/'));
		return;
	}
	await goToFolder(accountId, inbox.folderRef);
}

export async function switchToAccount(accountId: number): Promise<void> {
	setActiveAccount(accountId);
	const mode = currentWorkspaceMode();
	await goto(workspaceHref(mode));
}

export async function syncCurrentAccount(): Promise<void> {
	const accountId = get(resolvedActiveAccountId);
	if (accountId == null) return;
	await triggerAccountSync(accountId);
	await refreshFolders(accountId);
}

export async function closeCurrentMessageDetail(options?: {
	restoreFocus?: boolean;
}): Promise<void> {
	const restoreFocus = options?.restoreFocus ?? false;
	const stableId = get(selectedMessage)?.stableId;

	if (restoreFocus && stableId) {
		requestListFocusRestore(stableId);
	}

	clearSelection();
	/*
	 * reset: false: the restore above lands on the list row as soon as the store
	 * changes, which with a split reading pane (list already mounted next to
	 * the detail) happens *before* this navigation settles — SvelteKit's own
	 * post-navigation focus reset would then drop focus back on <body> and the
	 * layout's afterNavigate would park it on <main>. Measured: without the
	 * flag, Esc on a message the roving selection opened ends on <main>.
	 */
	await goto(currentFolderHref(), { reset: false });
}

export async function replyToMessage(stableId: string, all = false): Promise<void> {
	await goto(`${resolve('compose')}?reply=${encodeURIComponent(stableId)}&all=${all ? '1' : '0'}`);
}

export async function forwardMessage(stableId: string): Promise<void> {
	await goto(`${resolve('compose')}?forward=${encodeURIComponent(stableId)}`);
}
