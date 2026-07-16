<script lang="ts">
	import { DropdownMenu } from 'bits-ui';
	import { forwardMessage, replyToMessage } from '$lib/mail/actions.js';
	import {
		deleteMessages,
		moveMessages,
		toggleMessageFlag,
		toggleMessageSeen
	} from '$lib/mail/mailbox.js';
	import { folders } from '$lib/stores/folders.js';
	import { messagesState } from '$lib/stores/messages.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { _ } from '$lib/i18n/index.js';
	import Icon from '$lib/components/Icon.svelte';
	import { cn } from '$lib/utils.js';
	import type { MailSummaryResponse } from '$lib/types.js';

	type Props = {
		message: MailSummaryResponse;
		focused: boolean;
		col: number;
		onCellFocus: () => void;
		/**
		 * Folder the row belongs to, used to drop the current folder from the
		 * Move submenu. Defaults to the active folder from `messagesState` — the
		 * search results grid spans folders, so it passes the row's own folder.
		 */
		currentFolderRef?: string;
		/**
		 * Fired after a list-mutating action (flag/seen/move/delete) settles, so
		 * a caller whose list is not driven by `messagesState` (e.g. search) can
		 * refresh. Reply/forward navigate away and do not fire it.
		 */
		onAfterAction?: () => void;
	};

	let { message, focused, col, onCellFocus, currentFolderRef, onAfterAction }: Props = $props();

	let open = $state(false);

	/*
	 * Move targets = every folder except the current one. If the array is
	 * empty (fresh account with a single folder) the Move submenu does not
	 * render at all.
	 */
	const currentFolderName = $derived(
		currentFolderRef ?? ($messagesState.status === 'idle' ? '' : $messagesState.context.folderName)
	);
	const moveTargets = $derived($folders.filter((folder) => folder.folderRef !== currentFolderName));

	const flagLabel = $derived(message.flagged ? $_('toolbar.unflag') : $_('toolbar.flag'));
	const seenLabel = $derived(message.seen ? $_('toolbar.markUnread') : $_('toolbar.markRead'));

	const triggerLabel = $derived(
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

	const destructiveItemClass =
		'flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm text-destructive outline-none data-[highlighted]:bg-destructive/10 data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground';
	const defaultItemClass =
		'flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-none data-[highlighted]:bg-muted data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground';
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
		class={cn(
			'inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring data-[state=open]:bg-muted data-[state=open]:text-foreground'
		)}
	>
		<Icon name="ellipsis-horizontal" size={16} />
	</DropdownMenu.Trigger>

	<DropdownMenu.Portal>
		<DropdownMenu.Content
			align="end"
			sideOffset={4}
			loop
			class="z-10 min-w-44 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
		>
			<DropdownMenu.Item
				class={defaultItemClass}
				onSelect={() => run((id) => replyToMessage(id, false))}
			>
				{$_('toolbar.reply')}
			</DropdownMenu.Item>
			<DropdownMenu.Item
				class={defaultItemClass}
				onSelect={() => run((id) => replyToMessage(id, true))}
			>
				{$_('toolbar.replyAll')}
			</DropdownMenu.Item>
			<DropdownMenu.Item class={defaultItemClass} onSelect={() => run(forwardMessage)}>
				{$_('toolbar.forward')}
			</DropdownMenu.Item>

			<DropdownMenu.Separator class="my-1 h-px bg-border" />

			<DropdownMenu.Item
				class={defaultItemClass}
				onSelect={() => run(toggleMessageFlag, { refresh: true })}
			>
				{flagLabel}
			</DropdownMenu.Item>
			<DropdownMenu.Item
				class={defaultItemClass}
				onSelect={() => run(toggleMessageSeen, { refresh: true })}
			>
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
						class="z-10 max-h-64 min-w-48 overflow-y-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
					>
						{#each moveTargets as folder (folder.folderRef)}
							{@const label = folderLabel(folder, $_)}
							<DropdownMenu.Item
								class={defaultItemClass}
								title={label}
								onSelect={() =>
									run((id) => moveMessages([id], folder.folderRef), { refresh: true })}
							>
								<span class="truncate">{label}</span>
							</DropdownMenu.Item>
						{/each}
					</DropdownMenu.SubContent>
				</DropdownMenu.Sub>
			{/if}

			<DropdownMenu.Separator class="my-1 h-px bg-border" />

			<DropdownMenu.Item
				class={destructiveItemClass}
				onSelect={() => run((id) => deleteMessages([id]), { refresh: true })}
			>
				{$_('toolbar.delete')}
			</DropdownMenu.Item>
		</DropdownMenu.Content>
	</DropdownMenu.Portal>
</DropdownMenu.Root>
