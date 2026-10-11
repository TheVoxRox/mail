<script lang="ts">
	import {
		currentWorkspaceMode,
		type WorkspaceMode,
		workspaceHref
	} from '$lib/stores/workspaceMode.js';
	import { _ } from '$lib/i18n/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import { focusRing } from '$lib/components/ui/focus-ring/index.js';
	import { SHORTCUT_LABELS } from '$lib/shortcuts/shortcutLabels.js';
	import {
		WORKSPACE_PANE_ID,
		WORKSPACE_PANE_TOGGLE_ID,
		closePane,
		openPane,
		paneNarrow,
		paneOpen
	} from '$lib/stores/workspacePane.js';
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

	const paneLabelKeys: Record<WorkspaceMode, string> = {
		mail: 'workspace.mailSidebarLabel',
		contacts: 'workspace.contactsSidebarLabel',
		settings: 'workspace.settingsSidebarLabel'
	};
	const paneToggleLabel = $derived(
		$_('workspace.paneToggle', {
			values: {
				pane: $_(paneLabelKeys[currentWorkspaceMode()]),
				shortcut: SHORTCUT_LABELS.workspacePane
			}
		})
	);

	function railItemClass(active: boolean): string {
		return cn(
			'group relative flex h-10 w-10 items-center justify-center rounded-lg border transition-colors',
			focusRing,
			active
				? 'border-primary/20 bg-primary/10 text-primary shadow-sm'
				: 'border-transparent text-sidebar-foreground/80 hover:border-sidebar-border hover:bg-sidebar-accent hover:text-sidebar-accent-foreground'
		);
	}
</script>

<nav
	data-print="chrome"
	aria-label={$_('workspace.railLandmark')}
	class="flex h-full w-16 shrink-0 flex-col items-center border-r border-sidebar-border bg-sidebar px-2 py-3"
>
	<div class="flex w-full flex-col items-center gap-2">
		{#if $paneNarrow}
			<!--
				Only while the pane is folded away (stores/workspacePane.ts). Named
				after the pane it opens, with the state in aria-expanded rather than
				in a name that changes, so the reader hears "sbaleno" / "rozbaleno".
			-->
			<button
				type="button"
				id={WORKSPACE_PANE_TOGGLE_ID}
				class={railItemClass(false)}
				aria-expanded={$paneOpen}
				aria-controls={WORKSPACE_PANE_ID}
				aria-keyshortcuts="Control+Y"
				aria-label={paneToggleLabel}
				title={paneToggleLabel}
				onclick={() => ($paneOpen ? closePane({ returnFocus: true }) : openPane())}
			>
				<Icon name="bars-3" size={20} />
			</button>
		{/if}
		{#each items as item (item.mode)}
			{@const active = currentWorkspaceMode() === item.mode}
			<a
				href={workspaceHref(item.mode)}
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
