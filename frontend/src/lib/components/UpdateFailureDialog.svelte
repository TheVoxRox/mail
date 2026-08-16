<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '$lib/components/ui/dialog/index.js';
	import { pushToast } from '$lib/stores/toasts.js';
	import { dismissUpdateFailure, updateFailureState } from '$lib/updates.js';

	let open = $derived($updateFailureState.status === 'failed');
	let message = $derived(
		$updateFailureState.status === 'failed' ? $updateFailureState.message : ''
	);
	let releasesUrl = $derived(
		$updateFailureState.status === 'failed' ? $updateFailureState.releasesUrl : null
	);

	let lastToastMessage = $state<string | null>(null);

	$effect(() => {
		if ($updateFailureState.status !== 'failed') return;
		if (message === lastToastMessage) return;
		lastToastMessage = message;
		pushToast($_('update.failure.title'), { tone: 'error' });
	});
</script>

<DialogShell {open} onOpenChange={(nextOpen) => !nextOpen && dismissUpdateFailure()}>
	<DialogTitle>
		{$_('update.failure.title')}
	</DialogTitle>
	<DialogDescription class="space-y-2">
		<span class="block">{$_('update.failure.description')}</span>
		{#if message}
			<span class="block break-words text-xs text-muted-foreground">{message}</span>
		{/if}
	</DialogDescription>

	<div class="mt-5 flex flex-wrap justify-end gap-2">
		{#if releasesUrl}
			<Button href={releasesUrl} target="_blank" rel="noreferrer" variant="outline">
				{$_('update.failure.openReleases')}
				<span class="sr-only">{$_('common.opensInNewWindow')}</span>
			</Button>
		{/if}
		<Button onclick={dismissUpdateFailure}>{$_('common.ok')}</Button>
	</div>
</DialogShell>
