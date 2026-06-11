<script lang="ts">
	import { PageShell } from '$lib/components/ui/page-shell/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import { _ } from '$lib/i18n/index.js';

	type Shortcut = {
		shortcut: string;
		actionKey: string;
		scopeKey: string;
	};

	type ShortcutGroup = {
		headingKey: string;
		shortcuts: Shortcut[];
	};

	const groups: ShortcutGroup[] = [
		{
			headingKey: 'settings.shortcuts.groups.navigation',
			shortcuts: [
				{
					shortcut: 'Ctrl+1',
					actionKey: 'settings.shortcuts.actions.mail',
					scopeKey: 'settings.shortcuts.scopes.global'
				},
				{
					shortcut: 'Ctrl+2',
					actionKey: 'settings.shortcuts.actions.contacts',
					scopeKey: 'settings.shortcuts.scopes.global'
				},
				{
					shortcut: 'Ctrl+3',
					actionKey: 'settings.shortcuts.actions.settings',
					scopeKey: 'settings.shortcuts.scopes.global'
				},
				{
					shortcut: '?',
					actionKey: 'settings.shortcuts.actions.shortcutHelp',
					scopeKey: 'settings.shortcuts.scopes.global'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.mail',
			shortcuts: [
				{
					shortcut: 'Ctrl+N',
					actionKey: 'settings.shortcuts.actions.newMessage',
					scopeKey: 'settings.shortcuts.scopes.mailOrSettings'
				},
				{
					shortcut: 'Ctrl+R',
					actionKey: 'settings.shortcuts.actions.reply',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+Shift+R',
					actionKey: 'settings.shortcuts.actions.replyAll',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+F',
					actionKey: 'settings.shortcuts.actions.forward',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+Shift+G',
					actionKey: 'settings.shortcuts.actions.flag',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+Q',
					actionKey: 'settings.shortcuts.actions.markRead',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Ctrl+U',
					actionKey: 'settings.shortcuts.actions.markUnread',
					scopeKey: 'settings.shortcuts.scopes.openMessage'
				},
				{
					shortcut: 'Delete',
					actionKey: 'settings.shortcuts.actions.deleteMessage',
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
			headingKey: 'settings.shortcuts.groups.contacts',
			shortcuts: [
				{
					shortcut: 'Ctrl+N',
					actionKey: 'settings.shortcuts.actions.newContact',
					scopeKey: 'settings.shortcuts.scopes.contacts'
				}
			]
		},
		{
			headingKey: 'settings.shortcuts.groups.palette',
			shortcuts: [
				{
					shortcut: 'Ctrl+K',
					actionKey: 'settings.shortcuts.actions.palette',
					scopeKey: 'settings.shortcuts.scopes.global'
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
					{#each group.shortcuts as shortcut (shortcut.actionKey)}
						<li class="grid gap-2 py-3 sm:grid-cols-[13rem_1fr] sm:items-start">
							<kbd
								class="w-fit rounded border border-border bg-muted px-2 py-1 font-mono text-[0.72rem] font-medium text-foreground shadow-xs"
							>
								{shortcut.shortcut}
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
