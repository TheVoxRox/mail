/**
 * Display labels for the shortcuts implemented in `globalShortcuts.ts` —
 * the single source for every place that *renders* them (Settings ›
 * Shortcuts, command palette entries). The handler logic stays in
 * `globalShortcuts.ts`; only the user-facing strings live here, so a
 * rebound key is changed in one place instead of three.
 */
export const SHORTCUT_LABELS = {
	palette: 'Ctrl+K',
	newItem: 'Ctrl+N',
	workspaceMail: 'Ctrl+1',
	workspaceContacts: 'Ctrl+2',
	workspaceSettings: 'Ctrl+3',
	reply: 'Ctrl+R',
	replyAll: 'Ctrl+Shift+R',
	forward: 'Ctrl+F',
	toggleFlag: 'Ctrl+Shift+G',
	markRead: 'Ctrl+Q',
	markUnread: 'Ctrl+U',
	deleteMessage: 'Delete'
} as const;
