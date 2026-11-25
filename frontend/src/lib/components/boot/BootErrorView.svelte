<script lang="ts">
	import { Collapsible } from 'bits-ui';
	import BootDiagnosticActions from '#lib/components/boot/BootDiagnosticActions.svelte';
	import { Button } from '#lib/components/ui/button/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { checkForUpdateManually } from '#lib/updates.js';

	interface Props {
		/** What went wrong, in the interface language (bootFailureText.ts). */
		errorMessage: string;
		/**
		 * The error's own English text, for support. Shown only on request,
		 * behind a disclosure, and marked as English (3.1.2).
		 */
		errorDetail: string | null;
		onRetry: () => void;
		onRestart: () => void;
		/**
		 * Required, like the rest of the dump's state, as in BootLoadingView:
		 * while they were optional, the view for a backend that never started was
		 * rendered without them and lost Save logs along with the dump, on the one
		 * screen where only the logs still work. A view with no dump to give says
		 * so through `diagnosticUnavailable`.
		 */
		onDownloadDiagnostic: () => void;
		diagnosticBusy: boolean;
		diagnosticDisabled: boolean;
		diagnosticError: string | null;
		diagnosticUnavailable: boolean;
	}

	let {
		errorMessage,
		errorDetail,
		onRetry,
		onRestart,
		onDownloadDiagnostic,
		diagnosticBusy,
		diagnosticDisabled,
		diagnosticError,
		diagnosticUnavailable
	}: Props = $props();

	let updateCheckBusy = $state(false);
	let updateCheckStatus = $state<string | null>(null);

	/*
	 * The same check as Settings → About, which this screen stands in front of.
	 * A build that cannot start may be fixed by the next one, and the updater
	 * needs nothing from the backend; without this the only way to that fix for
	 * someone who turned the startup check off was a manual download. An update
	 * that is found opens the usual prompt and a failure the usual dialog, both
	 * mounted outside the boot-gated part of the layout.
	 */
	async function handleCheckForUpdates() {
		if (updateCheckBusy) return;
		updateCheckBusy = true;
		updateCheckStatus = null;
		try {
			updateCheckStatus = (await checkForUpdateManually()).status;
		} finally {
			updateCheckBusy = false;
		}
	}
</script>

<main id="main-content" tabindex="-1" class="flex flex-1 items-center justify-center p-8">
	<div class="max-w-md text-center">
		<h1 class="text-lg font-semibold text-destructive-foreground">{$_('app.bootFailed')}</h1>
		<p class="mt-2 text-sm text-muted-foreground">{errorMessage}</p>
		<Button class="mt-4" onclick={onRetry}>{$_('app.retry')}</Button>
		<BootDiagnosticActions
			{onRestart}
			{onDownloadDiagnostic}
			{diagnosticBusy}
			{diagnosticDisabled}
			{diagnosticError}
			{diagnosticUnavailable}
			rowClass="mt-2"
		/>
		<!--
			After the actions, so the way out comes first in the Tab order and the
			developer's text is one more stop, not one in front. bits-ui's
			Collapsible, as the third-party notices in Settings → About: a button
			with aria-expanded that controls the text, closed by default, and the
			closed text `hidden`, out of the view and the accessibility tree until
			someone asks for it.
		-->
		{#if errorDetail}
			<Collapsible.Root class="mt-4 flex flex-col items-center gap-2">
				<Collapsible.Trigger>
					{#snippet child({ props })}
						<Button {...props} variant="outline" size="xs">
							{$_('common.technicalDetails')}
						</Button>
					{/snippet}
				</Collapsible.Trigger>
				<Collapsible.Content>
					<p class="text-xs break-words text-muted-foreground" lang="en">{errorDetail}</p>
				</Collapsible.Content>
			</Collapsible.Root>
		{/if}
		<div class="mt-4 flex flex-col items-center gap-1 border-t border-border pt-4">
			<!-- aria-disabled, not disabled: a disabled button drops the focus it holds, and the
			     person who pressed it would land on the document while the check runs. -->
			<Button
				type="button"
				variant="outline"
				onclick={handleCheckForUpdates}
				aria-disabled={updateCheckBusy ? 'true' : undefined}
			>
				{updateCheckBusy
					? $_('settings.about.versions.checkingUpdates')
					: $_('settings.about.versions.checkUpdates')}
			</Button>
			<!-- Rendered before it has text: a live region inserted together with its
			     text is not reliably announced (as in AboutSettings). -->
			<span
				role="status"
				class={`text-xs ${updateCheckStatus === 'failed' ? 'text-warning-foreground' : 'text-muted-foreground'}`}
			>
				{updateCheckStatus ? $_(`settings.about.versions.update.${updateCheckStatus}`) : ''}
			</span>
		</div>
	</div>
</main>
