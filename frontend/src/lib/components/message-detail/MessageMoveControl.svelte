<script lang="ts">
	import { folders } from '#lib/stores/folders.js';
	import { messagesState } from '#lib/stores/messages.js';
	import { moveMessages } from '#lib/mail/mailbox.js';
	import { moveTargetsFor } from '#lib/mail/moveTargets.js';
	import { toErrorMessage } from '#lib/api/errors.js';
	import { _ } from '#lib/i18n/index.js';
	import { buttonVariants } from '#lib/components/ui/button/index.js';
	import { DropdownMenu, Toolbar } from 'bits-ui';
	import { MenuContent } from '#lib/components/ui/menu/index.js';
	import Icon from '#lib/components/Icon.svelte';
	import MoveTargetMenuItems from '#lib/components/MoveTargetMenuItems.svelte';
	import { cn } from '#lib/utils.js';

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
	const moveTargets = $derived(moveTargetsFor($folders, currentFolderName));

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

<!--
	Rendered only inside MailToolbar's Toolbar.Root: the trigger is one of the
	toolbar's buttons, so the arrow keys reach it and it is no Tab stop of its
	own. Toolbar.Button needs that root around it.
-->
<div class="flex min-w-0 flex-wrap items-center gap-1.5">
	<DropdownMenu.Root bind:open={menuOpen}>
		<DropdownMenu.Trigger disabled={!stableId || moving || moveTargets.length === 0}>
			{#snippet child({ props })}
				<Toolbar.Button
					{...props}
					class={cn(buttonVariants({ variant: 'outline', size }), 'data-[state=open]:bg-muted')}
					title={$_('toolbar.move')}
				>
					<Icon name="folder" />
					{#if compact}
						<span class="sr-only">{$_('toolbar.move')}</span>
					{:else}
						<span>{moving ? $_('toolbar.moving') : $_('toolbar.move')}</span>
						<Icon name="chevron-down" size={16} />
					{/if}
				</Toolbar.Button>
			{/snippet}
		</DropdownMenu.Trigger>
		<MenuContent label={$_('toolbar.move')} scroll>
			<MoveTargetMenuItems targets={moveTargets} onMoveTo={handleMoveTo} />
		</MenuContent>
	</DropdownMenu.Root>
	{#if moveError}
		<p class="basis-full text-xs text-destructive-foreground" role="alert">{moveError}</p>
	{/if}
</div>
