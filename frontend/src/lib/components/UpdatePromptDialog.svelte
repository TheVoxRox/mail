<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
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

<DialogShell {open} onOpenChange={handleOpenChange}>
	<DialogTitle>
		{$_('update.prompt.title', { values: { version: update?.version ?? '' } })}
	</DialogTitle>
	<DialogDescription>
		{$_('update.prompt.description', {
			values: {
				version: update?.version ?? '',
				currentVersion: update?.currentVersion ?? ''
			}
		})}
	</DialogDescription>

	<div class="mt-5 flex flex-wrap justify-end gap-2">
		<Button variant="outline" onclick={postponePromptedUpdate} disabled={installing}>
			{$_('update.prompt.later')}
		</Button>
		<Button onclick={() => void installPromptedUpdate()} disabled={installing}>
			{installing ? $_('update.prompt.installing') : $_('update.prompt.installNow')}
		</Button>
	</div>
</DialogShell>
