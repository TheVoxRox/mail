<script lang="ts">
	import { Collapsible } from 'bits-ui';
	import { _ } from '#lib/i18n/index.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { DialogDescription, DialogShell, DialogTitle } from '#lib/components/ui/dialog/index.js';
	import { dismissUpdateFailure, updateFailureState } from '#lib/updates.js';

	let open = $derived($updateFailureState.status === 'failed');
	let message = $derived(
		$updateFailureState.status === 'failed' ? $updateFailureState.message : ''
	);
	let messageLang = $derived(
		$updateFailureState.status === 'failed' ? $updateFailureState.messageLang : undefined
	);
	let releasesUrl = $derived(
		$updateFailureState.status === 'failed' ? $updateFailureState.releasesUrl : null
	);

	/*
	 * No toast. This dialog used to push one carrying `update.failure.title` —
	 * the same string as its own heading, raised at the same moment the modal
	 * opened, so the failure was announced twice in a row with nothing to
	 * distinguish the two. The modal is the louder and more useful of the pair:
	 * it takes focus, carries the underlying message and the releases-page
	 * fallback, and has to be dismissed.
	 */
</script>

<DialogShell {open} onOpenChange={(nextOpen) => !nextOpen && dismissUpdateFailure()}>
	<DialogTitle>
		{$_('update.failure.title')}
	</DialogTitle>
	<!--
		The description is what a screen reader reads as the dialog opens, so it
		holds the sentence alone. The updater's own words used to sit in it too,
		English read out in an English voice before the user knew what happened.
	-->
	<DialogDescription>{$_('update.failure.description')}</DialogDescription>

	<div class="mt-5 flex flex-wrap justify-end gap-2">
		{#if releasesUrl}
			<Button href={releasesUrl} target="_blank" rel="noreferrer" variant="outline">
				{$_('update.failure.openReleases')}
				<span class="sr-only">{$_('common.opensInNewWindow')}</span>
			</Button>
		{/if}
		<Button onclick={dismissUpdateFailure}>{$_('common.ok')}</Button>
	</div>

	<!--
		The updater's words stay for whoever the user passes them to, behind a
		disclosure as on the boot failure screen: bits-ui's Collapsible, closed,
		its closed text `hidden`, marked with its language once shown (3.1.2).
		After the buttons, because the dialog focuses its first control on open,
		and that has to stay the way out, not the developer's text.
	-->
	{#if message}
		<Collapsible.Root class="mt-4 flex flex-col items-start gap-2">
			<Collapsible.Trigger>
				{#snippet child({ props })}
					<Button {...props} variant="outline" size="xs">{$_('common.technicalDetails')}</Button>
				{/snippet}
			</Collapsible.Trigger>
			<Collapsible.Content>
				<p class="text-xs break-words text-muted-foreground" lang={messageLang}>{message}</p>
			</Collapsible.Content>
		</Collapsible.Root>
	{/if}
</DialogShell>
