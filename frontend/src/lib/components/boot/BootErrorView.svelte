<script lang="ts">
	import BootDiagnosticActions from '$lib/components/boot/BootDiagnosticActions.svelte';
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';

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
</script>

<main id="main-content" tabindex="-1" class="flex flex-1 items-center justify-center p-8">
	<div class="max-w-md text-center">
		<h1 class="text-title font-semibold text-destructive-foreground">{$_('app.bootFailed')}</h1>
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
	</div>
</main>
