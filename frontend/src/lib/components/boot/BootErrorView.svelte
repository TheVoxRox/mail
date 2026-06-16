<script lang="ts">
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
		<h1 class="text-title font-semibold text-destructive">{$_('app.bootFailed')}</h1>
		<p class="mt-2 text-sm text-muted-foreground">{errorMessage}</p>
		<Button class="mt-4" onclick={onRetry}>{$_('app.retry')}</Button>
		{#if onDownloadDiagnostic}
			<div class="mt-2 flex flex-wrap justify-center gap-2">
				<Button type="button" variant="outline" onclick={onRestart}>
					{$_('app.restartBackend')}
				</Button>
				<Button
					type="button"
					variant="outline"
					onclick={onDownloadDiagnostic}
					disabled={diagnosticDisabled}
				>
					{diagnosticBusy ? $_('app.downloadingDiagnostic') : $_('app.downloadDiagnostic')}
				</Button>
			</div>
			{#if diagnosticError}
				<p class="mt-2 text-xs text-destructive" role="alert">{diagnosticError}</p>
			{:else if diagnosticUnavailable}
				<p class="mt-2 text-xs text-muted-foreground">{$_('app.diagnosticUnavailable')}</p>
			{/if}
		{:else}
			<Button class="mt-2" variant="outline" onclick={onRestart}>{$_('app.restartBackend')}</Button>
		{/if}
	</div>
</main>
