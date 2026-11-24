import { goToAccounts, goToFolder, switchToAccount } from '#lib/mail/actions.js';
import type { AccountsState } from '#lib/stores/accounts.js';
import type { Command } from '#lib/commands/shared.js';
import { SHORTCUT_LABELS } from '#lib/shortcuts/shortcutLabels.js';

type FolderCommandSource = {
	displayName: string;
	folderRef: string;
	role?: string | null;
};

export function createAccountCommands(state: AccountsState): Command[] {
	const commands: Command[] = [
		{
			id: 'nav.accounts',
			titleKey: 'command.openAccounts',
			groupKey: 'nav',
			icon: 'user-circle',
			contexts: ['settings'],
			routePrefixes: ['/settings/accounts'],
			priority: 24,
			available: () => true,
			run: () => goToAccounts()
		}
	];

	if (state.status !== 'ready') return commands;

	for (const account of state.accounts) {
		commands.push({
			id: `account.switch.${account.id}`,
			titleKey: 'command.switchAccount',
			titleValues: { email: account.email },
			groupKey: 'account',
			keywords: [
				account.accountName,
				account.email,
				...(account.displayName ? [account.displayName] : [])
			],
			icon: 'user-circle',
			contexts: ['mail', 'contacts'],
			routePrefixes: ['/mail/', '/contacts'],
			priority: 8,
			available: () => true,
			run: () => switchToAccount(account.id)
		});
	}

	return commands;
}

export function createFolderCommands(
	currentAccountId: number | null,
	currentFolders: FolderCommandSource[]
): Command[] {
	if (currentAccountId == null) return [];

	return currentFolders.map((folder) => ({
		id: `folder.${folder.folderRef}`,
		titleKey: 'command.goToFolder',
		titleValues: { folder: folder.displayName },
		groupKey: 'nav',
		keywords: [folder.displayName, folder.folderRef],
		// Ctrl+Shift+I goes to the active account's Inbox, which is this list's.
		shortcut: folder.role === 'INBOX' ? SHORTCUT_LABELS.goToInbox : undefined,
		icon: 'folder',
		contexts: ['mail'],
		routePrefixes: ['/mail/', '/search/'],
		priority: 10,
		available: () => true,
		run: () => goToFolder(currentAccountId, folder.folderRef)
	}));
}
