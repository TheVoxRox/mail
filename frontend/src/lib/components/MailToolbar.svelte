<script lang="ts">
	import { DropdownMenu, Toolbar } from 'bits-ui';
	import { MenuContent, menuItemVariants } from '#lib/components/ui/menu/index.js';
	import { selectedMessage } from '#lib/stores/selectedMessage.js';
	import { _ } from '#lib/i18n/index.js';
	import { forwardMessage, replyToMessage } from '#lib/mail/actions.js';
	import { printOpenMessage } from '#lib/mail/printMessages.js';
	import { deleteMessages, toggleMessageFlag, toggleMessageSeen } from '#lib/mail/mailbox.js';
	import { Button, buttonVariants } from '#lib/components/ui/button/index.js';
	import Icon, { type IconName } from '#lib/components/Icon.svelte';
	import MessageMoveControl from '#lib/components/message-detail/MessageMoveControl.svelte';
	import { cn } from '#lib/utils.js';

	type ToolbarAction = {
		id: 'reply' | 'replyAll' | 'forward' | 'flag' | 'seen' | 'print' | 'delete';
		label: string;
		variant: 'ghost' | 'destructive';
		icon?: IconName;
		/** Shortcut in ARIA token form (e.g. "Control+R") for aria-keyshortcuts,
		 * so screen readers announce it without polluting the accessible name.
		 * Matches the handlers wired in globalShortcuts.ts. */
		ariaKeyshortcuts?: string;
		menuClass?: string;
		run: (stableId: string) => Promise<unknown> | void;
	};

	let innerWidth = $state(1200);
	let moreOpen = $state(false);

	const compact = $derived(innerWidth <= 1280);
	const overflow = $derived(innerWidth < 640);
	const hasSelection = $derived(Boolean($selectedMessage?.stableId));
	const selectedStableId = $derived($selectedMessage?.stableId ?? null);
	const selectedDetail = $derived($selectedMessage?.detail ?? null);
	const flagLabel = $derived(selectedDetail?.flagged ? $_('toolbar.unflag') : $_('toolbar.flag'));
	const seenLabel = $derived(
		selectedDetail?.seen ? $_('toolbar.markUnread') : $_('toolbar.markRead')
	);

	async function runSelected(action: (stableId: string) => Promise<unknown> | void) {
		if (!selectedStableId) return;
		await action(selectedStableId);
	}

	function handleMenuSelect(action: (stableId: string) => Promise<unknown> | void): void {
		void runSelected(action);
	}

	function messageActions(): ToolbarAction[] {
		const actions: ToolbarAction[] = [
			{
				id: 'reply',
				label: $_('toolbar.reply'),
				variant: 'ghost',
				icon: 'arrow-uturn-left',
				ariaKeyshortcuts: 'Control+R',
				run: (stableId) => replyToMessage(stableId, false)
			},
			{
				id: 'replyAll',
				label: $_('toolbar.replyAll'),
				variant: 'ghost',
				icon: 'arrow-uturn-left-double',
				ariaKeyshortcuts: 'Control+Shift+R',
				run: (stableId) => replyToMessage(stableId, true)
			},
			{
				id: 'forward',
				label: $_('toolbar.forward'),
				variant: 'ghost',
				icon: 'arrow-uturn-right',
				ariaKeyshortcuts: 'Control+F',
				run: forwardMessage
			},
			{
				id: 'flag',
				label: flagLabel,
				variant: 'ghost',
				icon: selectedDetail?.flagged ? 'star' : 'star-outline',
				ariaKeyshortcuts: 'Control+Shift+G',
				run: toggleMessageFlag
			},
			{
				id: 'seen',
				label: seenLabel,
				variant: 'ghost',
				icon: 'envelope',
				ariaKeyshortcuts: selectedDetail?.seen ? 'Control+U' : 'Control+Q',
				run: toggleMessageSeen
			},
			{
				id: 'print',
				label: $_('toolbar.print'),
				variant: 'ghost',
				icon: 'printer',
				ariaKeyshortcuts: 'Control+P',
				run: () => printOpenMessage()
			},
			{
				id: 'delete',
				label: $_('toolbar.delete'),
				variant: 'destructive',
				icon: 'trash',
				ariaKeyshortcuts: 'Delete',
				menuClass: menuItemVariants({ tone: 'destructive' }),
				run: (stableId: string) => deleteMessages([stableId])
			}
		];
		return actions;
	}
</script>

<svelte:window bind:innerWidth />

<!--
	Outer wrapping bar (border / bg / padding) is driven by the parent in
	+layout.svelte so the heading on the left and action buttons on the
	right sit on the same row.

	bits-ui's Toolbar gives the WAI-ARIA toolbar keyboard: one Tab stop, the
	left and right arrows between the controls (wrapping), Home and End. Every
	control in it is a Toolbar.Button, the menu triggers included (through the
	trigger's child snippet), or it would stay a Tab stop of its own.
-->
<Toolbar.Root
	data-print="chrome"
	aria-label={$_('toolbar.ariaLabel')}
	class="flex min-h-9 min-w-0 flex-1 flex-nowrap items-center gap-1.5 overflow-x-auto"
>
	{#if !overflow}
		<div class="flex shrink-0 items-center gap-1">
			{#each messageActions() as action (action.id)}
				<Toolbar.Button disabled={!hasSelection}>
					{#snippet child({ props })}
						<Button
							{...props}
							variant={action.variant}
							size={compact ? 'icon-sm' : 'sm'}
							onclick={() => runSelected(action.run)}
							title={action.label}
							aria-keyshortcuts={action.ariaKeyshortcuts}
						>
							{#if action.icon}
								<Icon name={action.icon} />
							{/if}
							{#if compact}
								<span class="sr-only">{action.label}</span>
							{:else}
								<span>{action.label}</span>
							{/if}
						</Button>
					{/snippet}
				</Toolbar.Button>
			{/each}
		</div>

		<MessageMoveControl stableId={selectedStableId} size={compact ? 'xs' : 'sm'} {compact} />
	{/if}

	<div class="ml-auto flex shrink-0 items-center gap-2">
		{#if overflow}
			<DropdownMenu.Root bind:open={moreOpen}>
				<DropdownMenu.Trigger>
					{#snippet child({ props })}
						<Toolbar.Button
							{...props}
							class={cn(
								buttonVariants({ variant: 'outline', size: 'sm' }),
								'data-[state=open]:bg-muted'
							)}
							aria-label={$_('toolbar.more')}
						>
							<Icon name="ellipsis-horizontal" />
							<span>{$_('toolbar.more')}</span>
						</Toolbar.Button>
					{/snippet}
				</DropdownMenu.Trigger>

				<MenuContent
					id="mail-toolbar-more-popover"
					label={$_('toolbar.more')}
					align="end"
					sideOffset={8}
					width="md"
					class="absolute right-0 top-full mt-2"
				>
					{#each messageActions() as action (action.id)}
						<DropdownMenu.Item
							class={action.menuClass ?? menuItemVariants()}
							onSelect={() => handleMenuSelect(action.run)}
							disabled={!hasSelection}
							aria-keyshortcuts={action.ariaKeyshortcuts}
						>
							{action.label}
						</DropdownMenu.Item>
					{/each}
				</MenuContent>
			</DropdownMenu.Root>
		{/if}
	</div>
</Toolbar.Root>
