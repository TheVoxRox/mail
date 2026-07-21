/**
 * Navigation helpers for mail screens. Message mutations live in
 * `mail/mailbox.ts` and are imported directly by their consumers.
 */
import { goto } from '$app/navigation';
import { resolve } from '$app/paths';
import { get } from 'svelte/store';
import { triggerAccountSync } from '$lib/api/mailAction.js';
import { refreshFolders } from '$lib/stores/folders.js';
import { resolvedActiveAccountId, setActiveAccount } from '$lib/stores/accounts.js';
import {
	clearSelection,
	requestListFocusRestore,
	selectedMessage
} from '$lib/stores/selectedMessage.js';
import { messagesState } from '$lib/stores/messages.js';
import { workspaceHref, type WorkspaceMode, workspaceMode } from '$lib/stores/workspaceMode.js';

function currentFolderHref(): string {
	const state = get(messagesState);
	if (state.status === 'idle') {
		return resolve('/');
	}
	return resolve('/mail/[accountId]/[folderName]', {
		accountId: String(state.context.accountId),
		folderName: encodeURIComponent(state.context.folderName)
	});
}

export async function goToWorkspaceMode(mode: WorkspaceMode): Promise<void> {
	await goto(workspaceHref(mode, get(resolvedActiveAccountId)));
}

export async function goToAccounts(): Promise<void> {
	await goto(resolve('/settings/accounts'));
}

export async function goToSearch(): Promise<void> {
	const accountId = get(resolvedActiveAccountId);
	if (accountId == null) return;
	await goto(resolve('/search/[accountId]', { accountId: String(accountId) }));
}

export async function goToCompose(): Promise<void> {
	await goto(resolve('/compose'));
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

export async function switchToAccount(accountId: number): Promise<void> {
	setActiveAccount(accountId);
	const mode = get(workspaceMode);
	await goto(workspaceHref(mode, accountId));
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
	 * keepFocus: the restore above lands on the list row as soon as the store
	 * changes, which with a split reading pane (list already mounted next to
	 * the detail) happens *before* this navigation settles — SvelteKit's own
	 * post-navigation focus reset would then drop focus back on <body> and the
	 * layout's afterNavigate would park it on <main>.
	 */
	await goto(currentFolderHref(), { keepFocus: true });
}

export async function replyToMessage(stableId: string, all = false): Promise<void> {
	await goto(`${resolve('/compose')}?reply=${encodeURIComponent(stableId)}&all=${all ? '1' : '0'}`);
}

export async function forwardMessage(stableId: string): Promise<void> {
	await goto(`${resolve('/compose')}?forward=${encodeURIComponent(stableId)}`);
}
