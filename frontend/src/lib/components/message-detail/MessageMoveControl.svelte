<script lang="ts">
	import { folders } from '$lib/stores/folders.js';
	import { messagesState } from '$lib/stores/messages.js';
	import { moveMessages } from '$lib/mail/mailbox.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { _ } from '$lib/i18n/index.js';
	import { buttonVariants } from '$lib/components/ui/button/index.js';
	import { DropdownMenu } from 'bits-ui';
	import Icon from '$lib/components/Icon.svelte';
	import { cn } from '$lib/utils.js';
	import type { FolderResponse } from '$lib/types.js';

	interface Props {
		stableId: string | null;
		size?: 'xs' | 'sm';
		compact?: boolean;
	}

	let { stableId, size = 'sm', compact = false }: Props = $props();

	let moving = $state(false);
	let moveError = $state<string | null>(null);
	let menuOpen = $state(false);

	const currentFolderName = $derived(
		$messagesState.status === 'idle' ? '' : $messagesState.context.folderName
	);
	const moveTargets = $derived(
		$folders.filter((folder: FolderResponse) => folder.folderRef !== currentFolderName)
	);

	async function handleMoveTo(folderRef: string) {
		if (!stableId) return;
		moving = true;
		moveError = null;
		try {
			await moveMessages([stableId], folderRef);
		} catch (err) {
			moveError = toErrorMessage(err);
		} finally {
			moving = false;
		}
	}
</script>

<div class="flex min-w-0 flex-wrap items-center gap-1.5">
	<DropdownMenu.Root bind:open={menuOpen}>
		<DropdownMenu.Trigger
			class={cn(buttonVariants({ variant: 'outline', size }), 'data-[state=open]:bg-muted')}
			disabled={!stableId || moving || moveTargets.length === 0}
			title={$_('toolbar.move')}
		>
			<Icon name="folder" />
			{#if compact}
				<span class="sr-only">{$_('toolbar.move')}</span>
			{:else}
				<span>{moving ? $_('toolbar.moving') : $_('toolbar.move')}</span>
				<Icon name="chevron-down" size={16} />
			{/if}
		</DropdownMenu.Trigger>
		<DropdownMenu.Portal>
			<DropdownMenu.Content
				align="start"
				sideOffset={4}
				loop
				class="z-10 max-h-64 min-w-44 overflow-y-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
			>
				{#each moveTargets as folder (folder.folderRef)}
					{@const label = folderLabel(folder, $_)}
					<DropdownMenu.Item
						class="flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-none data-[highlighted]:bg-muted data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground"
						title={label}
						onSelect={() => handleMoveTo(folder.folderRef)}
					>
						<span class="truncate">{label}</span>
					</DropdownMenu.Item>
				{/each}
			</DropdownMenu.Content>
		</DropdownMenu.Portal>
	</DropdownMenu.Root>
	{#if moveError}
		<p class="basis-full text-xs text-destructive" role="alert">{moveError}</p>
	{/if}
</div>
