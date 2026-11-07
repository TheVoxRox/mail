<script lang="ts">
	import {
		currentWorkspaceMode,
		type WorkspaceMode,
		workspaceHref
	} from '#lib/stores/workspaceMode.js';
	import { _ } from '#lib/i18n/index.js';
	import Icon from '#lib/components/Icon.svelte';
	import { focusRing } from '#lib/components/ui/focus-ring/index.js';
	import {
		WORKSPACE_PANE_ID,
		WORKSPACE_PANE_TOGGLE_ID,
		paneShown,
		togglePane
	} from '#lib/stores/workspacePane.js';
	import { cn } from '#lib/utils.js';

	type RailItem = {
		mode: WorkspaceMode;
		icon: 'inbox' | 'users' | 'cog';
		labelKey: string;
		shortcut: string;
	};

	const items: RailItem[] = [
		{ mode: 'mail', icon: 'inbox', labelKey: 'workspace.mail', shortcut: '1' },
		{ mode: 'contacts', icon: 'users', labelKey: 'workspace.contacts', shortcut: '2' },
		{ mode: 'settings', icon: 'cog', labelKey: 'workspace.settings', shortcut: '3' }
	];

	const paneToggleKeys: Record<WorkspaceMode, string> = {
		mail: 'workspace.mailPaneToggle',
		contacts: 'workspace.contactsPaneToggle',
		settings: 'workspace.settingsPaneToggle'
	};
	const paneToggleLabel = $derived($_(paneToggleKeys[currentWorkspaceMode()]));

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

<!--
	The rail is a column, not a landmark: the landmark is the workspace links
	alone, and the pane toggle stands below it, outside. Only the links switch
	workspaces, so only they are in the landmark named for that.
-->
<div
	data-print="chrome"
	class="flex h-full w-16 shrink-0 flex-col items-center border-r border-sidebar-border bg-sidebar px-2 py-3"
>
	<nav aria-label={$_('workspace.railLandmark')} class="flex w-full flex-col items-center gap-2">
		<!--
			Named by the title alone, as the toggle below is: an aria-label beside
			the same title made Chromium expose the name a second time as the
			description. The link carries no text either, which would outrank the
			title for the name and leave the title to be read as the description.
		-->
		{#each items as item (item.mode)}
			{@const active = currentWorkspaceMode() === item.mode}
			<a
				href={workspaceHref(item.mode)}
				class={railItemClass(active)}
				aria-current={active ? 'page' : undefined}
				title={$_('railSwitch.goToMode', {
					values: { mode: $_(item.labelKey), shortcut: `Ctrl+${item.shortcut}` }
				})}
			>
				{#if active}
					<span class="absolute -left-2 h-6 w-0.5 rounded-r-full bg-primary" aria-hidden="true"
					></span>
				{/if}
				<Icon name={item.icon} size={20} />
			</a>
		{/each}
	</nav>
	<!--
		Shows and hides the pane (stores/workspacePane.ts): slides it in over the
		content in a narrow window, docks it or folds it away in a wide one.
		Named after what a press does, with the state in aria-expanded rather than
		in a name that changes, so the reader hears "sbaleno" / "rozbaleno". The
		name was the pane's alone, which said what the button is about but not
		what it does. Ctrl+Y is not in it, unlike the links above: the key goes to
		the pane and never hides it, so the name would have promised a toggle the
		key is not. The name comes from the title alone: beside an aria-label, the
		title counts as the description too, even with the same words, and is
		offered to the reader a second time.

		At the foot of the rail, outside the landmark and after it in reading
		order (docs/DECISIONS.md). At the head of the column, inside the
		landmark, testers took it for one of the workspaces even with its own
		icon and a rule under it (#772), a screen reader entering the landmark
		met a button that switches nothing, and three links stood between it and
		the pane it controls. Here the next thing after it is that pane, as after
		any disclosure button, in the Tab order and in browse mode. The icon draws
		a window with a side pane, not a hamburger, which testers read as a menu
		holding the links.
	-->
	<button
		type="button"
		id={WORKSPACE_PANE_TOGGLE_ID}
		class={cn(railItemClass(false), 'mt-auto')}
		aria-expanded={$paneShown}
		aria-controls={WORKSPACE_PANE_ID}
		title={paneToggleLabel}
		onclick={togglePane}
	>
		<Icon name="panel-left" size={20} />
	</button>
</div>
