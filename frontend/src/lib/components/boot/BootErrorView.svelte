<script lang="ts">
	import BootDiagnosticActions from '#lib/components/boot/BootDiagnosticActions.svelte';
	import { Button } from '#lib/components/ui/button/index.js';
	import { _ } from '#lib/i18n/index.js';
	import { checkForUpdateManually } from '#lib/updates.js';

	interface Props {
		errorMessage: string;
		onRetry: () => void;
		onRestart: () => void;
		/** Second phase (boot failed after sidecar) — adds the download-diagnostic action. */
		onDownloadDiagnostic?: () => void;
		diagnosticBusy?: boolean;
		diagnosticDisabled?: boolean;
		diagnosticError?: string | null;
		diagnosticUnavailable?: boolean;
	}

	let {
		errorMessage,
		onRetry,
		onRestart,
		onDownloadDiagnostic,
		diagnosticBusy = false,
		diagnosticDisabled = false,
		diagnosticError = null,
		diagnosticUnavailable = false
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
		{#if onDownloadDiagnostic}
			<BootDiagnosticActions
				{onRestart}
				{onDownloadDiagnostic}
				{diagnosticBusy}
				{diagnosticDisabled}
				{diagnosticError}
				{diagnosticUnavailable}
				rowClass="mt-2"
			/>
		{:else}
			<Button class="mt-2" variant="outline" onclick={onRestart}>{$_('app.restartBackend')}</Button>
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
