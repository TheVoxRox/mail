import { goToWorkspaceMode } from '$lib/mail/actions.js';
import type { AppLocale } from '$lib/i18n/index.js';
import { localeKeywords, type Command } from '$lib/commands/shared.js';
import { SHORTCUT_LABELS } from '$lib/shortcuts/shortcutLabels.js';

export function createWorkspaceCommands(locale: AppLocale | null): Command[] {
	return [
		{
			id: 'workspace.mail',
			titleKey: 'command.workspaceMail',
			groupKey: 'nav',
			keywords: localeKeywords(locale, ['posta', 'prepnout na postu'], ['mail', 'switch to mail']),
			shortcut: SHORTCUT_LABELS.workspaceMail,
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
			shortcut: SHORTCUT_LABELS.workspaceContacts,
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
			shortcut: SHORTCUT_LABELS.workspaceSettings,
			icon: 'cog',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('settings')
		}
	];
}
