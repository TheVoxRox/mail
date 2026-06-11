<script lang="ts">
	import { resolvedActiveAccountId } from '$lib/stores/accounts.js';
	import { type WorkspaceMode, workspaceHref, workspaceMode } from '$lib/stores/workspaceMode.js';
	import { _ } from '$lib/i18n/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import { cn } from '$lib/utils.js';

	type RailItem = {
		mode: WorkspaceMode;
		icon: 'inbox' | 'book-open' | 'cog';
		labelKey: string;
		shortcut: string;
	};

	const items: RailItem[] = [
		{ mode: 'mail', icon: 'inbox', labelKey: 'workspace.mail', shortcut: '1' },
		{ mode: 'contacts', icon: 'book-open', labelKey: 'workspace.contacts', shortcut: '2' },
		{ mode: 'settings', icon: 'cog', labelKey: 'workspace.settings', shortcut: '3' }
	];

	function railItemClass(active: boolean): string {
		return cn(
			'group relative flex h-10 w-10 items-center justify-center rounded-lg border transition-colors',
			active
				? 'border-primary/20 bg-primary/12 text-primary shadow-sm'
				: 'border-transparent text-sidebar-foreground/75 hover:border-sidebar-border hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
		);
	}
</script>

<nav
	aria-label={$_('workspace.railLandmark')}
	class="flex h-full w-16 shrink-0 flex-col items-center border-r border-sidebar-border bg-sidebar px-2 py-3"
>
	<div class="flex w-full flex-col items-center gap-2">
		{#each items as item (item.mode)}
			{@const active = $workspaceMode === item.mode}
			<a
				href={workspaceHref(item.mode, $resolvedActiveAccountId)}
				class={railItemClass(active)}
				aria-current={active ? 'page' : undefined}
				aria-label={$_('railSwitch.goToMode', {
					values: { mode: $_(item.labelKey), shortcut: `Ctrl+${item.shortcut}` }
				})}
				title={$_('railSwitch.goToMode', {
					values: { mode: $_(item.labelKey), shortcut: `Ctrl+${item.shortcut}` }
				})}
			>
				{#if active}
					<span class="absolute -left-2 h-6 w-0.5 rounded-r-full bg-primary" aria-hidden="true"
					></span>
				{/if}
				<Icon name={item.icon} size={20} />
				<span class="sr-only">{$_(item.labelKey)}</span>
			</a>
		{/each}
	</div>
</nav>
