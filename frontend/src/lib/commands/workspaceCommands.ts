import { goToFeedback, goToWorkspaceMode } from '#lib/mail/actions.js';
import type { Command } from '#lib/commands/shared.js';
import { SHORTCUT_LABELS } from '#lib/shortcuts/shortcutLabels.js';

export function createWorkspaceCommands(): Command[] {
	return [
		{
			id: 'workspace.mail',
			titleKey: 'command.workspaceMail',
			groupKey: 'nav',
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
			shortcut: SHORTCUT_LABELS.workspaceContacts,
			icon: 'users',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('contacts')
		},
		{
			id: 'workspace.settings',
			titleKey: 'command.workspaceSettings',
			groupKey: 'nav',
			shortcut: SHORTCUT_LABELS.workspaceSettings,
			icon: 'cog',
			priority: 90,
			available: () => true,
			run: () => goToWorkspaceMode('settings')
		},
		/*
		 * Its own group, not Navigation: the palette orders whole groups by their
		 * best match, so a keyword-only match here rode up with a folder the query
		 * named and sat above the commands whose titles matched.
		 */
		{
			id: 'other.feedback',
			titleKey: 'command.openFeedback',
			groupKey: 'other',
			icon: 'envelope',
			priority: 20,
			available: () => true,
			run: () => goToFeedback()
		}
	];
}
