<script lang="ts">
	import { Dialog } from 'bits-ui';
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
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

<Dialog.Root {open} onOpenChange={(nextOpen) => !nextOpen && dismissUpdateFailure()}>
	<Dialog.Portal>
		<Dialog.Overlay class="fixed inset-0 z-50 bg-black/40 backdrop-blur-[2px]" />
		<Dialog.Content
			class="fixed left-1/2 top-1/2 z-50 w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-lg border border-border bg-popover p-5 text-popover-foreground shadow-2xl"
			aria-describedby="update-failure-description"
		>
			<Dialog.Title class="text-base font-semibold">
				{$_('update.failure.title')}
			</Dialog.Title>
			<Dialog.Description
				id="update-failure-description"
				class="mt-2 space-y-2 text-sm text-muted-foreground"
			>
				<span class="block">{$_('update.failure.description')}</span>
				{#if message}
					<span class="block break-words text-xs text-muted-foreground">{message}</span>
				{/if}
			</Dialog.Description>

			<div class="mt-5 flex flex-wrap justify-end gap-2">
				{#if releasesUrl}
					<Button href={releasesUrl} target="_blank" rel="noreferrer" variant="outline">
						{$_('update.failure.openReleases')}
						<span class="sr-only">{$_('common.opensInNewWindow')}</span>
					</Button>
				{/if}
				<Button onclick={dismissUpdateFailure}>{$_('common.ok')}</Button>
			</div>
		</Dialog.Content>
	</Dialog.Portal>
</Dialog.Root>
