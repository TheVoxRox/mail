<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import { buttonVariants } from '$lib/components/ui/button/index.js';
	import {
		focusFirstMenuItem,
		menuContentVariants,
		menuItemVariants
	} from '$lib/components/ui/menu/index.js';
	import { forwardMessage, replyToMessage } from '$lib/mail/actions.js';
	import {
		deleteMessages,
		moveMessages,
		toggleMessageFlag,
		toggleMessageSeen
	} from '$lib/mail/mailbox.js';
	import { folders } from '$lib/stores/folders.js';
	import { messagesState } from '$lib/stores/messages.js';
	import { moveTargetsFor } from '$lib/mail/moveTargets.js';
	import { _ } from '$lib/i18n/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import MoveTargetMenuItems from '$lib/components/MoveTargetMenuItems.svelte';
	import { cn } from '$lib/utils.js';
	import type { RowActions } from '$lib/mail/rowActions.js';
	import type { MailSummaryResponse } from '$lib/types.js';

	type Props = {
		message: MailSummaryResponse;
		focused: boolean;
		col: number;
		onCellFocus: () => void;
		/**
		 * Folder the row belongs to, used to drop the current folder from the
		 * Move submenu. Defaults to the active folder from `messagesState` — the
		 * search results grid spans folders, so it passes the row's own folder,
		 * and the grouped view passes the folder its own store is showing.
		 */
		currentFolderRef?: string;
		/**
		 * Fired after a list-mutating action (flag/seen/move/delete) settles, so
		 * a caller whose list is not driven by `messagesState` (e.g. search) can
		 * refresh. Reply/forward navigate away and do not fire it.
		 */
		onAfterAction?: () => void;
		/** Overrides the default flat-store wiring — see `mail/rowActions.ts`. */
		actions?: RowActions;
		/** Overrides the trigger's accessible name (a conversation is not a message). */
		triggerLabel?: string;
		/**
		 * Read/starred state the labels describe. Defaults to the row's own
		 * message; a conversation row passes the thread's state, which is what
		 * that row displays and what its actions change.
		 */
		seen?: boolean;
		flagged?: boolean;
	};

	let {
		message,
		focused,
		col,
		onCellFocus,
		currentFolderRef,
		onAfterAction,
		actions,
		triggerLabel: triggerLabelOverride,
		seen,
		flagged
	}: Props = $props();

	let open = $state(false);

	/*
	 * Move targets = every folder except the current one. If the array is
	 * empty (fresh account with a single folder) the Move submenu does not
	 * render at all.
	 */
	const currentFolderName = $derived(
		currentFolderRef ?? ($messagesState.status === 'idle' ? '' : $messagesState.context.folderName)
	);
	const moveTargets = $derived(moveTargetsFor($folders, currentFolderName));

	const isFlagged = $derived(flagged ?? message.flagged);
	const isSeen = $derived(seen ?? message.seen);
	const flagLabel = $derived(isFlagged ? $_('toolbar.unflag') : $_('toolbar.flag'));
	const seenLabel = $derived(isSeen ? $_('toolbar.markUnread') : $_('toolbar.markRead'));

	const triggerLabel = $derived(
		triggerLabelOverride ??
			$_('messages.rowActions.trigger', {
				values: { subject: message.subject || $_('messages.noSubject') }
			})
	);

	function run(
		action: (stableId: string) => Promise<unknown> | void,
		opts?: { refresh?: boolean }
	): void {
		void Promise.resolve(action(message.stableId)).then(() => {
			if (opts?.refresh) onAfterAction?.();
		});
	}

	/** The flat-list wiring, used unless the caller injects its own. */
	const effectiveActions: RowActions = $derived(
		actions ?? {
			reply: (all) => run((id) => replyToMessage(id, all)),
			forward: () => run(forwardMessage),
			toggleFlag: () => run(toggleMessageFlag, { refresh: true }),
			toggleSeen: () => run(toggleMessageSeen, { refresh: true }),
			moveTo: (folderRef) => run((id) => moveMessages([id], folderRef), { refresh: true }),
			remove: () => run((id) => deleteMessages([id]), { refresh: true })
		}
	);

	const destructiveItemClass = menuItemVariants({ tone: 'destructive' });
	const defaultItemClass = menuItemVariants();
</script>

<DropdownMenu.Root bind:open>
	<DropdownMenu.Trigger
		data-cell-target
		data-col={col}
		tabindex={focused ? 0 : -1}
		onfocus={onCellFocus}
		onkeydown={(e: KeyboardEvent) => {
			/*
			 * The trigger sits in a grid cell, where arrows move focus between
			 * cells — they must not double as menubutton open keys (bits-ui opens
			 * on ArrowDown). preventDefault makes the merged internal handler skip
			 * (composeHandlers contract); the event still bubbles to the row's
			 * grid navigation. The menu opens with Enter/Space only.
			 */
			if (e.key === 'ArrowDown') e.preventDefault();
		}}
		aria-label={triggerLabel}
		class={cn(buttonVariants({ variant: 'ghost', size: 'icon-sm' }), 'text-muted-foreground')}
	>
		<Icon name="ellipsis-horizontal" size={16} />
	</DropdownMenu.Trigger>

	<DropdownMenu.Portal>
		<!--
			Named after the trigger that opens it, the way the detail toolbar's
			menu already is — a menu with no accessible name is a container a
			screen reader can only describe by reading what is inside it.
		-->
		<DropdownMenu.Content
			align="end"
			sideOffset={4}
			loop
			aria-label={triggerLabel}
			onfocus={focusFirstMenuItem}
			class={menuContentVariants()}
		>
			<DropdownMenu.Item class={defaultItemClass} onSelect={() => effectiveActions.reply(false)}>
				{$_('toolbar.reply')}
			</DropdownMenu.Item>
			<DropdownMenu.Item class={defaultItemClass} onSelect={() => effectiveActions.reply(true)}>
				{$_('toolbar.replyAll')}
			</DropdownMenu.Item>
			<DropdownMenu.Item class={defaultItemClass} onSelect={() => effectiveActions.forward()}>
				{$_('toolbar.forward')}
			</DropdownMenu.Item>

			<DropdownMenu.Separator class="my-1 h-px bg-border" />

			<DropdownMenu.Item class={defaultItemClass} onSelect={() => effectiveActions.toggleFlag()}>
				{flagLabel}
			</DropdownMenu.Item>
			<DropdownMenu.Item class={defaultItemClass} onSelect={() => effectiveActions.toggleSeen()}>
				{seenLabel}
			</DropdownMenu.Item>

			{#if moveTargets.length > 0}
				<DropdownMenu.Separator class="my-1 h-px bg-border" />

				<DropdownMenu.Sub>
					<DropdownMenu.SubTrigger class={cn(defaultItemClass, 'justify-between gap-3')}>
						<span class="truncate">{$_('toolbar.move')}</span>
						<span aria-hidden="true" class="text-muted-foreground">›</span>
					</DropdownMenu.SubTrigger>
					<DropdownMenu.SubContent
						sideOffset={4}
						class={menuContentVariants({ width: 'md', scroll: true })}
					>
						<MoveTargetMenuItems
							targets={moveTargets}
							onMoveTo={(folderRef) => effectiveActions.moveTo(folderRef)}
						/>
					</DropdownMenu.SubContent>
				</DropdownMenu.Sub>
			{/if}

			<DropdownMenu.Separator class="my-1 h-px bg-border" />

			<DropdownMenu.Item class={destructiveItemClass} onSelect={() => effectiveActions.remove()}>
				{$_('toolbar.delete')}
			</DropdownMenu.Item>
		</DropdownMenu.Content>
	</DropdownMenu.Portal>
</DropdownMenu.Root>
