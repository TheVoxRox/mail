<script lang="ts">
	import { Dialog } from 'bits-ui';
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import {
		installPromptedUpdate,
		postponePromptedUpdate,
		updatePromptState
	} from '$lib/updates.js';

	let open = $derived(
		$updatePromptState.status === 'available' || $updatePromptState.status === 'installing'
	);
	let update = $derived(
		$updatePromptState.status === 'available' || $updatePromptState.status === 'installing'
			? $updatePromptState.update
			: null
	);
	let installing = $derived($updatePromptState.status === 'installing');

	function handleOpenChange(nextOpen: boolean) {
		if (!nextOpen && !installing) {
			postponePromptedUpdate();
		}
	}
</script>

<Dialog.Root {open} onOpenChange={handleOpenChange}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-popover p-5 text-popover-foreground shadow-2xl"
			aria-describedby="update-prompt-description"
		>
			<Dialog.Title class="text-base font-semibold">
				{$_('update.prompt.title', { values: { version: update?.version ?? '' } })}
			</Dialog.Title>
			<Dialog.Description id="update-prompt-description" class="mt-2 text-sm text-muted-foreground">
				{$_('update.prompt.description', {
					values: {
						version: update?.version ?? '',
						currentVersion: update?.currentVersion ?? ''
					}
				})}
			</Dialog.Description>

			<div class="mt-5 flex flex-wrap justify-end gap-2">
				<Button variant="outline" onclick={postponePromptedUpdate} disabled={installing}>
					{$_('update.prompt.later')}
				</Button>
				<Button onclick={() => void installPromptedUpdate()} disabled={installing}>
					{installing ? $_('update.prompt.installing') : $_('update.prompt.installNow')}
				</Button>
			</div>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
