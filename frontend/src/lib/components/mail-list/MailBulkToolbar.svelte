<script lang="ts" module>
	/** The action currently running; the whole bar is unavailable while one is. */
	export type BulkAction = 'read' | 'unread' | 'delete' | 'move' | 'print';
</script>

<script lang="ts">
	/**
	 * The bulk-action bar above a mail list: the select-all box, what is
	 * selected, and the Delete / Mark read / Move / Print actions.
	 *
	 * Shared by the flat list (MessageList) and the conversation-grouped one
	 * (ConversationList). The two disagree only on how a selection is counted —
	 * messages there, conversations plus individually ticked members here —
	 * which is why the summary arrives as ready text instead of a number.
	 * Everything they do agree on lives here: the toolbar role, the labels, the
	 * tri-state checkbox and the rule that an action in flight makes the rest
	 * unavailable. A change to the bar can no longer land in one list and miss
	 * the other.
	 *
	 * The menus' open state and the select-all element are internal — nothing
	 * outside reads them.
	 */
	import { DropdownMenu } from 'bits-ui';
	import { MenuContent, menuItemVariants } from '$lib/components/ui/menu/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import MoveTargetMenuItems from '$lib/components/MoveTargetMenuItems.svelte';
	import { Button, buttonVariants } from '$lib/components/ui/button/index.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
	import { _ } from '$lib/i18n/index.js';
	import { cn } from '$lib/utils.js';
	import type { FolderResponse } from '$lib/types.js';

	interface Props {
		/** Every row of the page is selected. */
		allSelected: boolean;
		/** Some but not all — the native tri-state box and `aria-checked="mixed"`. */
		someSelected: boolean;
		/** Whether there is anything to act on; the actions render only then. */
		hasSelection: boolean;
		/** What the bar reports as selected, already formatted by the caller. */
		summary: string;
		busy: BulkAction | null;
		/** Usually `moveTargetsFor($folders, <current folder>)`; empty disables Move. */
		moveTargets: readonly FolderResponse[];
		/** Failure of the last action, rendered as an alert on its own row. */
		error?: string | null;
		onSelectAll: (checked: boolean) => void;
		onClear: () => void;
		onDelete: () => void;
		onMarkSeen: (seen: boolean) => void;
		onMoveTo: (folderRef: string) => void;
		onPrint: () => void;
	}

	let {
		allSelected,
		someSelected,
		hasSelection,
		summary,
		busy,
		moveTargets,
		error = null,
		onSelectAll,
		onClear,
		onDelete,
		onMarkSeen,
		onMoveTo,
		onPrint
	}: Props = $props();

	let seenMenuOpen = $state(false);
	let moveMenuOpen = $state(false);
	let selectAllInput = $state<HTMLInputElement | null>(null);

	/*
	 * An action in flight makes the bar unavailable with aria-disabled, never
	 * with disabled. The control that started the action is the one holding
	 * focus, and disabling a focused element drops focus to <body> without an
	 * event: the user pressed Print selected and was nowhere, through the fetch,
	 * the print dialog and after it, with nothing to bring focus back because no
	 * navigation happened. Same reasoning as the Sync button in MailSidebar.
	 * aria-disabled keeps the control in the focus order and still tells a
	 * screen reader it is unavailable, so the bar itself refuses a press while
	 * busy — the lists behind it do not all check, and a print in progress is
	 * not their own action to know about.
	 */
	const idle = $derived(busy === null);

	function whenIdle<A extends unknown[]>(action: (...args: A) => void): (...args: A) => void {
		return (...args) => {
			if (busy === null) action(...args);
		};
	}

	// A menu cannot open while the bar is busy; closing it always goes through.
	function menuOpenSetter(set: (open: boolean) => void): (open: boolean) => void {
		return (open) => set(open && busy === null);
	}
	const setSeenMenuOpen = menuOpenSetter((open) => (seenMenuOpen = open));
	const setMoveMenuOpen = menuOpenSetter((open) => (moveMenuOpen = open));

	// The native tri-state, so the box looks the way `aria-checked="mixed"` sounds.
	$effect(() => {
		if (selectAllInput) selectAllInput.indeterminate = someSelected;
	});
</script>

<div
	role="toolbar"
	aria-label={$_('messages.bulkToolbarLabel')}
	class="flex min-h-11 flex-wrap items-center gap-2 border-b border-border/80 bg-muted/20 px-3 py-2"
>
	<label class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
		<input
			bind:this={selectAllInput}
			type="checkbox"
			class={nativeControlClass}
			checked={allSelected}
			aria-checked={someSelected ? 'mixed' : allSelected ? 'true' : 'false'}
			onchange={(event) => onSelectAll((event.currentTarget as HTMLInputElement).checked)}
		/>
		<span>{$_('messages.selectAll')}</span>
	</label>

	{#if hasSelection}
		<span class="text-xs text-muted-foreground" role="status">
			{summary}
		</span>
		<Button
			type="button"
			variant="ghost"
			size="xs"
			onclick={whenIdle(onClear)}
			aria-disabled={idle ? undefined : 'true'}
		>
			{$_('messages.clearSelection')}
		</Button>
		<Button
			type="button"
			variant="destructive"
			size="xs"
			onclick={whenIdle(onDelete)}
			aria-disabled={idle ? undefined : 'true'}
			aria-busy={busy === 'delete' ? 'true' : undefined}
		>
			<Icon name="trash" />
			<span>{busy === 'delete' ? $_('messages.bulkDeleting') : $_('messages.bulkDelete')}</span>
		</Button>
		<DropdownMenu.Root bind:open={() => seenMenuOpen, setSeenMenuOpen}>
			<DropdownMenu.Trigger
				class={cn(buttonVariants({ variant: 'outline', size: 'xs' }), 'data-[state=open]:bg-muted')}
				aria-disabled={idle ? undefined : 'true'}
				aria-busy={busy === 'read' || busy === 'unread' ? 'true' : undefined}
			>
				<Icon name="envelope" />
				<span
					>{busy === 'read' || busy === 'unread'
						? $_('messages.bulkMarkingRead')
						: $_('messages.bulkSeenMenu')}</span
				>
				<Icon name="chevron-down" size={16} />
			</DropdownMenu.Trigger>
			<MenuContent label={$_('messages.bulkSeenMenu')}>
				<DropdownMenu.Item class={menuItemVariants()} onSelect={whenIdle(() => onMarkSeen(true))}>
					{$_('messages.bulkMarkRead')}
				</DropdownMenu.Item>
				<DropdownMenu.Item class={menuItemVariants()} onSelect={whenIdle(() => onMarkSeen(false))}>
					{$_('messages.bulkMarkUnread')}
				</DropdownMenu.Item>
			</MenuContent>
		</DropdownMenu.Root>
		<DropdownMenu.Root bind:open={() => moveMenuOpen, setMoveMenuOpen}>
			<!-- No move target is a lasting state, not an action in flight: that one stays disabled. -->
			<DropdownMenu.Trigger
				class={cn(buttonVariants({ variant: 'outline', size: 'xs' }), 'data-[state=open]:bg-muted')}
				disabled={moveTargets.length === 0}
				aria-disabled={idle ? undefined : 'true'}
				aria-busy={busy === 'move' ? 'true' : undefined}
			>
				<Icon name="folder" />
				<span>{busy === 'move' ? $_('toolbar.moving') : $_('messages.bulkMove')}</span>
				<Icon name="chevron-down" size={16} />
			</DropdownMenu.Trigger>
			<MenuContent label={$_('messages.bulkMove')} scroll>
				<MoveTargetMenuItems targets={moveTargets} onMoveTo={whenIdle(onMoveTo)} />
			</MenuContent>
		</DropdownMenu.Root>
		<Button
			type="button"
			variant="outline"
			size="xs"
			onclick={whenIdle(onPrint)}
			aria-disabled={idle ? undefined : 'true'}
			aria-busy={busy === 'print' ? 'true' : undefined}
		>
			<Icon name="printer" />
			<span>{busy === 'print' ? $_('messages.bulkPrinting') : $_('messages.bulkPrint')}</span>
		</Button>
	{/if}
	{#if error}
		<p class="basis-full text-xs text-destructive-foreground" role="alert">{error}</p>
	{/if}
</div>
