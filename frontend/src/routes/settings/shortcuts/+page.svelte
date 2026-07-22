<script lang="ts">
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { SHORTCUT_LABELS } from '$lib/shortcuts/shortcutLabels.js';

	type Shortcut = {
		/** Literal key name rendered as-is (Ctrl+1, Delete, …). */
		shortcut?: string;
		/** i18n key for key names that need translating (arrows, Space). */
		shortcutKey?: string;
		actionKey: string;
		scopeKey: string;
	};

	type ShortcutGroup = {
		headingKey: string;
		shortcuts: Shortcut[];
	};

	/*
	 * Scope/action rows are a hand-maintained mirror of the real handlers —
	 * keep in sync with lib/shortcuts/globalShortcuts.ts,
	 * lib/components/compose/controller.ts and
	 * lib/components/grid/rowNavigation.ts (each carries a reminder). Labels
	 * of the global shortcuts come from lib/shortcuts/shortcutLabels.ts.
	 */
	const groups: ShortcutGroup[] = [
		{
			headingKey: 'settings.shortcuts.groups.navigation',
			shortcuts: [
				{
					shortcut: SHORTCUT_LABELS.workspaceMail,
					actionKey: 'settings.shortcuts.actions.mail',
					scopeKey: 'settings.shortcuts.scopes.globalIncludingInputs'
				},
				{
					shortcut: SHORTCUT_LABELS.workspaceContacts,
					actionKey: 'settings.shortcuts.actions.contacts',
					scopeKey: 'settings.shortcuts.scopes.globalIncludingInputs'
				},
				{
					shortcut: SHORTCUT_LABELS.workspaceSettings,
					actionKey: 'settings.shortcuts.actions.settings',
					scopeKey: 'settings.shortcuts.scopes.globalIncludingInputs'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.mail',
			shortcuts: [
				{
					shortcut: SHORTCUT_LABELS.newItem,
					actionKey: 'settings.shortcuts.actions.newMessage',
					scopeKey: 'settings.shortcuts.scopes.mailOrSettings'
				},
				{
					shortcut: SHORTCUT_LABELS.reply,
					actionKey: 'settings.shortcuts.actions.reply',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.replyAll,
					actionKey: 'settings.shortcuts.actions.replyAll',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.forward,
					actionKey: 'settings.shortcuts.actions.forward',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.toggleFlag,
					actionKey: 'settings.shortcuts.actions.flag',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.markRead,
					actionKey: 'settings.shortcuts.actions.markRead',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.markUnread,
					actionKey: 'settings.shortcuts.actions.markUnread',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: SHORTCUT_LABELS.deleteMessage,
					actionKey: 'settings.shortcuts.actions.deleteMessage',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Escape',
					actionKey: 'settings.shortcuts.actions.closeMessage',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+Enter',
					actionKey: 'settings.shortcuts.actions.sendMessage',
					scopeKey: 'settings.shortcuts.scopes.compose'
				},
				{
					shortcut: 'Ctrl+S',
					actionKey: 'settings.shortcuts.actions.saveDraft',
					scopeKey: 'settings.shortcuts.scopes.compose'
				},
				{
					shortcut: 'Ctrl+Shift+D',
					actionKey: 'settings.shortcuts.actions.discardDraft',
					scopeKey: 'settings.shortcuts.scopes.compose'
				},
				{
					shortcut: 'Escape',
					actionKey: 'settings.shortcuts.actions.discardDraft',
					scopeKey: 'settings.shortcuts.scopes.compose'
				},
				{
					shortcut: 'Ctrl+Shift+C',
					actionKey: 'settings.shortcuts.actions.focusCc',
					scopeKey: 'settings.shortcuts.scopes.compose'
				},
				{
					shortcut: 'Ctrl+Shift+B',
					actionKey: 'settings.shortcuts.actions.focusBcc',
					scopeKey: 'settings.shortcuts.scopes.compose'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.list',
			shortcuts: [
				{
					shortcutKey: 'settings.shortcuts.keys.upDown',
					actionKey: 'settings.shortcuts.actions.listPrevNextMessage',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcutKey: 'settings.shortcuts.keys.leftRight',
					actionKey: 'settings.shortcuts.actions.listPrevNextCell',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcut: 'Home / End',
					actionKey: 'settings.shortcuts.actions.listRowStartEnd',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcut: 'Ctrl+Home / Ctrl+End',
					actionKey: 'settings.shortcuts.actions.listFirstLastMessage',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcut: 'Page Up / Page Down',
					actionKey: 'settings.shortcuts.actions.listPage',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcutKey: 'settings.shortcuts.keys.enterSpace',
					actionKey: 'settings.shortcuts.actions.listOpenMessage',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				},
				{
					shortcut: 'Delete',
					actionKey: 'settings.shortcuts.actions.listDeleteMessage',
					scopeKey: 'settings.shortcuts.scopes.messageGrid'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.contacts',
			shortcuts: [
				{
					shortcut: SHORTCUT_LABELS.newItem,
					actionKey: 'settings.shortcuts.actions.newContact',
					scopeKey: 'settings.shortcuts.scopes.contacts'
				},
				{
					shortcutKey: 'settings.shortcuts.keys.listNavCombo',
					actionKey: 'settings.shortcuts.actions.contactsListNav',
					scopeKey: 'settings.shortcuts.scopes.contactList'
				},
				{
					shortcut: 'Escape',
					actionKey: 'settings.shortcuts.actions.cancelContactForm',
					scopeKey: 'settings.shortcuts.scopes.contactForm'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.palette',
			shortcuts: [
				{
					shortcut: SHORTCUT_LABELS.palette,
					actionKey: 'settings.shortcuts.actions.palette',
					scopeKey: 'settings.shortcuts.scopes.globalIncludingInputs'
				},
				{
					shortcut: 'Enter',
					actionKey: 'settings.shortcuts.actions.runCommand',
					scopeKey: 'settings.shortcuts.scopes.palette'
				},
				{
					shortcut: 'Escape',
					actionKey: 'settings.shortcuts.actions.closePalette',
					scopeKey: 'settings.shortcuts.scopes.palette'
				},
				{
					shortcut: 'Tab',
					actionKey: 'settings.shortcuts.actions.keepFocus',
					scopeKey: 'settings.shortcuts.scopes.palette'
				},
				{
					shortcut: 'Shift+Tab',
					actionKey: 'settings.shortcuts.actions.keepFocusBack',
					scopeKey: 'settings.shortcuts.scopes.palette'
				}
			]
		}
	];
</script>

<svelte:head>
	<title>{$_('settings.shortcuts.pageTitle')}</title>
</svelte:head>

<PageShell title={$_('settings.nav.shortcuts')} description={$_('settings.shortcuts.intro')}>
	<div class="max-w-3xl space-y-4">
		{#each groups as group (group.headingKey)}
			<Surface as="section" class="space-y-3">
				<h2 class="text-sm font-semibold">{$_(group.headingKey)}</h2>
				<ul role="list" class="divide-y divide-border">
					{#each group.shortcuts as shortcut (`${shortcut.shortcut ?? shortcut.shortcutKey}|${shortcut.actionKey}`)}
						<li class="grid gap-2 py-3 sm:grid-cols-[13rem_1fr] sm:items-start">
							<kbd
								class="w-fit rounded border border-border bg-muted px-2 py-1 font-mono text-caption font-medium text-foreground shadow-xs"
							>
								{shortcut.shortcutKey ? $_(shortcut.shortcutKey) : shortcut.shortcut}
							</kbd>
							<div class="min-w-0">
								<p class="text-sm font-medium text-foreground">{$_(shortcut.actionKey)}</p>
								<p class="mt-0.5 text-xs text-muted-foreground">{$_(shortcut.scopeKey)}</p>
							</div>
						</li>
					{/each}
				</ul>
			</Surface>
		{/each}
	</div>
</PageShell>
