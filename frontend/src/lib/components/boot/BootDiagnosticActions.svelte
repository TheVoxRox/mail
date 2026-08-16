<script lang="ts">
	/**
	 * The recovery actions both boot views offer once startup has clearly gone
	 * wrong: restart the backend, download a diagnostic dump, and say what
	 * happened to the dump.
	 *
	 * These two views are the app's only face while it fails to start, so the
	 * one path a stuck user has out of it must not depend on which of the two
	 * they are looking at — and with a copy in each, adding a state to one and
	 * forgetting the other is a silent way to lose it.
	 *
	 * The loading view puts a Retry button in the same row, which is what
	 * `leading` is for; the error view keeps its Retry above, on its own.
	 */
	import type { Snippet } from 'svelte';
	import { _ } from '$lib/i18n/index.js';
	import { Button } from '$lib/components/ui/button/index.js';
	import { cn } from '$lib/utils.js';

	interface Props {
		onRestart: () => void;
		onDownloadDiagnostic: () => void;
		diagnosticBusy: boolean;
		diagnosticDisabled: boolean;
		diagnosticError: string | null;
		/** No session yet, so there is nothing to dump — says so instead of failing. */
		diagnosticUnavailable: boolean;
		/** Extra classes for the action row (the two views space it differently). */
		rowClass?: string;
		/** Rendered before Restart, inside the same row. */
		leading?: Snippet;
	}

	let {
		onRestart,
		onDownloadDiagnostic,
		diagnosticBusy,
		diagnosticDisabled,
		diagnosticError,
		diagnosticUnavailable,
		rowClass,
		leading
	}: Props = $props();
</script>

<div class={cn('flex flex-wrap justify-center gap-2', rowClass)}>
	{@render leading?.()}
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
	<p class="mt-2 text-xs text-destructive-foreground" role="alert">{diagnosticError}</p>
{:else if diagnosticUnavailable}
	<p class="mt-2 text-xs text-muted-foreground">{$_('app.diagnosticUnavailable')}</p>
{/if}
