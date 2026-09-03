<script lang="ts" module>
	/** The action currently running; the whole bar is disabled while one is. */
	export type BulkAction = 'read' | 'unread' | 'delete' | 'move';
</script>

<script lang="ts">
	/**
	 * The bulk-action bar above a mail list: the select-all box, what is
	 * selected, and the Delete / Mark read / Move actions.
	 *
	 * Shared by the flat list (MessageList) and the conversation-grouped one
	 * (ConversationList). The two disagree only on how a selection is counted —
	 * messages there, conversations plus individually ticked members here —
	 * which is why the summary arrives as ready text instead of a number.
	 * Everything they do agree on lives here: the toolbar role, the labels, the
	 * tri-state checkbox and the rule that an action in flight disables the
	 * rest. A change to the bar can no longer land in one list and miss the
	 * other.
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
		onMoveTo
	}: Props = $props();

	let seenMenuOpen = $state(false);
	let moveMenuOpen = $state(false);
	let selectAllInput = $state<HTMLInputElement | null>(null);

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
		<Button type="button" variant="ghost" size="xs" onclick={onClear} disabled={busy !== null}>
			{$_('messages.clearSelection')}
		</Button>
		<Button
			type="button"
			variant="destructive"
			size="xs"
			onclick={onDelete}
			disabled={busy !== null}
		>
			<Icon name="trash" />
			<span>{busy === 'delete' ? $_('messages.bulkDeleting') : $_('messages.bulkDelete')}</span>
		</Button>
		<DropdownMenu.Root bind:open={seenMenuOpen}>
			<DropdownMenu.Trigger
				class={cn(buttonVariants({ variant: 'outline', size: 'xs' }), 'data-[state=open]:bg-muted')}
				disabled={busy !== null}
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
				<DropdownMenu.Item class={menuItemVariants()} onSelect={() => onMarkSeen(true)}>
					{$_('messages.bulkMarkRead')}
				</DropdownMenu.Item>
				<DropdownMenu.Item class={menuItemVariants()} onSelect={() => onMarkSeen(false)}>
					{$_('messages.bulkMarkUnread')}
				</DropdownMenu.Item>
			</MenuContent>
		</DropdownMenu.Root>
		<DropdownMenu.Root bind:open={moveMenuOpen}>
			<DropdownMenu.Trigger
				class={cn(buttonVariants({ variant: 'outline', size: 'xs' }), 'data-[state=open]:bg-muted')}
				disabled={busy !== null || moveTargets.length === 0}
			>
				<Icon name="folder" />
				<span>{busy === 'move' ? $_('toolbar.moving') : $_('messages.bulkMove')}</span>
				<Icon name="chevron-down" size={16} />
			</DropdownMenu.Trigger>
			<MenuContent label={$_('messages.bulkMove')} scroll>
				<MoveTargetMenuItems targets={moveTargets} {onMoveTo} />
			</MenuContent>
		</DropdownMenu.Root>
	{/if}
	{#if error}
		<p class="basis-full text-xs text-destructive-foreground" role="alert">{error}</p>
	{/if}
</div>
