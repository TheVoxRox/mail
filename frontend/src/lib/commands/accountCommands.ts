import type { AppLocale } from '$lib/i18n/index.js';
import { goToAccounts, goToFolder, switchToAccount } from '$lib/mail/actions.js';
import type { AccountsState } from '$lib/stores/accounts.js';
import { localeKeywords, type Command } from '$lib/commands/shared.js';

type FolderCommandSource = {
	displayName: string;
	folderRef: string;
};

export function createAccountCommands(state: AccountsState, locale: AppLocale | null): Command[] {
	const commands: Command[] = [
		{
			id: 'nav.accounts',
			titleKey: 'command.openAccounts',
			groupKey: 'nav',
			keywords: localeKeywords(locale, ['ucty'], ['accounts']),
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
				...(account.displayName ? [account.displayName] : []),
				...localeKeywords(locale, ['ucet', 'prepnout ucet'], ['account', 'switch account'])
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
	currentFolders: FolderCommandSource[],
	locale: AppLocale | null
): Command[] {
	if (currentAccountId == null) return [];

	return currentFolders.map((folder) => ({
		id: `folder.${folder.folderRef}`,
		titleKey: 'command.goToFolder',
		titleValues: { folder: folder.displayName },
		groupKey: 'nav',
		keywords: [
			folder.displayName,
			folder.folderRef,
			...localeKeywords(locale, ['slozka', 'prejit na slozku'], ['folder', 'go to folder'])
		],
		icon: 'folder',
		contexts: ['mail'],
		routePrefixes: ['/mail/', '/search/'],
		priority: 10,
		available: () => true,
		run: () => goToFolder(currentAccountId, folder.folderRef)
	}));
}
