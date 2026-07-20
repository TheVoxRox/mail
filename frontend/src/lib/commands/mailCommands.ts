import type { AppLocale } from '$lib/i18n/index.js';
import {
	forwardMessage,
	goToCompose,
	goToSearch,
	replyToMessage,
	syncCurrentAccount
} from '$lib/mail/actions.js';
import {
	deleteMessages,
	moveMessages,
	toggleMessageFlag,
	toggleMessageSeen
} from '$lib/mail/mailbox.js';
import { localeKeywords, type Command } from '$lib/commands/shared.js';
import { SHORTCUT_LABELS } from '$lib/shortcuts/shortcutLabels.js';
import type { FolderResponse } from '$lib/types.js';

type MessageCommandDetail = {
	flagged: boolean;
	seen: boolean;
} | null;

interface MailCommandOptions {
	activeAccountId: number | null;
	folders: FolderResponse[];
	locale: AppLocale | null;
	pathname: string;
	selectedDetail: MessageCommandDetail;
	stableId: string | null;
}

function mailRouteAvailable(pathname: string): boolean {
	return pathname.startsWith('/mail/');
}

function messageRouteAvailable(pathname: string): boolean {
	return mailRouteAvailable(pathname) && pathname.split('/').length >= 5;
}

export function createMailCommands(options: MailCommandOptions): Command[] {
	const { activeAccountId, folders, locale, pathname, selectedDetail, stableId } = options;
	const currentFolderName = decodeURIComponent(pathname.split('/')[3] ?? '');
	const moveTargets = folders.filter((folder) => folder.folderRef !== currentFolderName);
	const commands: Command[] = [
		{
			id: 'nav.search',
			titleKey: 'command.openSearch',
			groupKey: 'nav',
			keywords: localeKeywords(locale, ['hledat', 'vyhledavani'], ['search', 'find']),
			icon: 'folder',
			contexts: ['mail'],
			routePrefixes: ['/mail/', '/search/'],
			priority: 12,
			available: () => activeAccountId != null,
			run: () => goToSearch()
		},
		{
			id: 'other.compose',
			titleKey: 'command.openCompose',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['nova zprava', 'psat'], ['compose', 'new message']),
			icon: 'pencil-square',
			contexts: ['mail', 'contacts', 'settings'],
			routePrefixes: ['/'],
			priority: 26,
			available: () => true,
			run: () => goToCompose()
		},
		{
			id: 'other.sync',
			titleKey: 'command.syncNow',
			groupKey: 'mail',
			keywords: localeKeywords(
				locale,
				['synchronizovat', 'obnovit schranku'],
				['sync', 'refresh mailbox']
			),
			icon: 'arrow-path',
			contexts: ['mail'],
			routePrefixes: ['/mail/', '/search/'],
			priority: 18,
			restoreFocus: true,
			available: () => activeAccountId != null,
			run: () => syncCurrentAccount()
		}
	];

	if (!stableId || !messageRouteAvailable(pathname)) return commands;

	return [
		...commands,
		{
			id: 'mail.reply',
			titleKey: 'command.reply',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['odpovedet'], ['reply']),
			shortcut: SHORTCUT_LABELS.reply,
			icon: 'arrow-uturn-left',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 80,
			available: () => true,
			run: () => replyToMessage(stableId, false)
		},
		{
			id: 'mail.replyAll',
			titleKey: 'command.replyAll',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['odpovedet vsem'], ['reply all']),
			shortcut: SHORTCUT_LABELS.replyAll,
			icon: 'arrow-uturn-left-double',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 76,
			available: () => true,
			run: () => replyToMessage(stableId, true)
		},
		{
			id: 'mail.forward',
			titleKey: 'command.forward',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['preposlat'], ['forward']),
			shortcut: SHORTCUT_LABELS.forward,
			icon: 'arrow-uturn-right',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 72,
			available: () => true,
			run: () => forwardMessage(stableId)
		},
		{
			id: 'mail.flag',
			titleKey: selectedDetail?.flagged ? 'command.unflag' : 'command.flag',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['hvezdicka', 'oznacit'], ['star', 'flag']),
			shortcut: SHORTCUT_LABELS.toggleFlag,
			icon: selectedDetail?.flagged ? 'star' : 'star-outline',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 64,
			restoreFocus: true,
			available: () => true,
			run: () => toggleMessageFlag(stableId)
		},
		{
			id: 'mail.seen',
			titleKey: selectedDetail?.seen ? 'command.markUnread' : 'command.markRead',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['precteno', 'neprecteno'], ['read', 'unread']),
			shortcut: selectedDetail?.seen ? SHORTCUT_LABELS.markUnread : SHORTCUT_LABELS.markRead,
			icon: 'inbox',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 60,
			restoreFocus: true,
			available: () => true,
			run: () => toggleMessageSeen(stableId)
		},
		{
			id: 'mail.delete',
			titleKey: 'command.deleteMessage',
			groupKey: 'mail',
			keywords: localeKeywords(locale, ['smazat'], ['delete', 'remove']),
			shortcut: SHORTCUT_LABELS.deleteMessage,
			icon: 'trash',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 52,
			available: () => true,
			run: () => deleteMessages([stableId])
		},
		...moveTargets.map((folder): Command => ({
			id: `mail.move.${folder.folderRef}`,
			titleKey: 'command.moveMessageToFolder',
			titleValues: { folder: folder.displayName },
			groupKey: 'mail',
			keywords: localeKeywords(
				locale,
				['presunout', 'slozka', folder.displayName],
				['move', 'folder', folder.displayName]
			),
			icon: 'folder',
			contexts: ['mail'],
			routePrefixes: ['/mail/'],
			priority: 48,
			available: () => true,
			run: () => moveMessages([stableId], folder.folderRef)
		}))
	];
}
