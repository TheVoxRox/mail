import { page } from '$app/stores';
import { derived, writable } from 'svelte/store';
import type { Command } from '$lib/commands/shared.js';
import { createAccountCommands, createFolderCommands } from '$lib/commands/accountCommands.js';
import { createMailCommands } from '$lib/commands/mailCommands.js';
import { createViewCommands } from '$lib/commands/viewCommands.js';
import { createWorkspaceCommands } from '$lib/commands/workspaceCommands.js';
import { activeAccountId, accountsState } from '$lib/stores/accounts.js';
import { folders } from '$lib/stores/folders.js';
import { selectedMessage } from '$lib/stores/selectedMessage.js';
import { appLocale, _ } from '$lib/i18n/index.js';
import { folderLabel } from '$lib/mail/folderLabel.js';
import { themePreference } from '$lib/stores/theme.js';

export type { Command } from '$lib/commands/shared.js';

const registryReady = writable(false);

export function registerDefaultCommands(): void {
	registryReady.set(true);
}

export const commands = derived(
	[
		registryReady,
		accountsState,
		activeAccountId,
		folders,
		selectedMessage,
		appLocale,
		themePreference,
		page,
		_
	],
	([
		$registryReady,
		$accountsState,
		$activeAccountId,
		$folders,
		$selectedMessage,
		$appLocale,
		$themePreference,
		$page,
		$t
	]): Command[] => {
		if (!$registryReady) return [];

		const stableId = $selectedMessage?.stableId ?? null;
		const selectedDetail = $selectedMessage?.detail ?? null;

		// Special folders carry a technical name (e.g. "INBOX") in `displayName`;
		// folderLabel maps them to the localized label by role, so command titles
		// match the rest of the UI instead of showing the raw IMAP name.
		const localizedFolders = $folders.map((folder) => ({
			...folder,
			displayName: folderLabel(folder, $t)
		}));

		const items: Command[] = [
			...createWorkspaceCommands($appLocale),
			...createAccountCommands($accountsState, $appLocale),
			...createFolderCommands($activeAccountId, localizedFolders, $appLocale),
			...createMailCommands({
				activeAccountId: $activeAccountId,
				folders: localizedFolders,
				locale: $appLocale,
				pathname: $page.url.pathname,
				selectedDetail,
				stableId
			}),
			...createViewCommands($appLocale, $themePreference)
		];

		return items.filter((command) => command.available());
	}
);
