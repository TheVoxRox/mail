import { goToWorkspaceMode } from '$lib/mail/actions.js';
import type { AppLocale } from '$lib/i18n/index.js';
import { localeKeywords, type Command } from '$lib/commands/shared.js';

export function createWorkspaceCommands(locale: AppLocale | null): Command[] {
	return [
		{
			id: 'workspace.mail',
			titleKey: 'command.workspaceMail',
			groupKey: 'nav',
			keywords: localeKeywords(locale, ['posta', 'prepnout na postu'], ['mail', 'switch to mail']),
			shortcut: 'Ctrl+1',
			icon: 'inbox',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('mail')
		},
		{
			id: 'workspace.contacts',
			titleKey: 'command.workspaceContacts',
			groupKey: 'nav',
			keywords: localeKeywords(
				locale,
				['kontakty', 'prepnout na kontakty'],
				['contacts', 'switch to contacts']
			),
			shortcut: 'Ctrl+2',
			icon: 'book-open',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('contacts')
		},
		{
			id: 'workspace.settings',
			titleKey: 'command.workspaceSettings',
			groupKey: 'nav',
			keywords: localeKeywords(
				locale,
				['nastaveni', 'prepnout do nastaveni'],
				['settings', 'switch to settings']
			),
			shortcut: 'Ctrl+3',
			icon: 'cog',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('settings')
		}
	];
}
