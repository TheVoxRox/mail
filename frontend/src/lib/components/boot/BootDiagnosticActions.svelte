<script lang="ts">
	/**
	 * The recovery actions both boot views offer once startup has clearly gone
	 * wrong: restart the backend, download a diagnostic dump, save the logs, and
	 * say what happened to each.
	 *
	 * These two views are the app's only face while it fails to start, so the
	 * one path a stuck user has out of it must not depend on which of the two
	 * they are looking at — and with a copy in each, adding a state to one and
	 * forgetting the other is a silent way to lose it.
	 *
	 * The dump needs the backend; saving the logs does not (the shell packs the
	 * folder itself), which makes it the one that still works when the backend
	 * never comes up. Its state lives here rather than in the views: nothing
	 * else needs it.
	 *
	 * The loading view puts a Retry button in the same row, which is what
	 * `leading` is for; the error view keeps its Retry above, on its own.
	 */
	import type { Snippet } from 'svelte';
	import { _ } from '#lib/i18n/index.js';
	import { toErrorMessage } from '#lib/api/errors.js';
	import { Button } from '#lib/components/ui/button/index.js';
	import { saveLogs } from '#lib/logs.js';
	import { hasNativeShell } from '#lib/nativeShell.js';
	import { cn } from '#lib/utils.js';

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

	const logsAvailable = hasNativeShell();
	let logsBusy = $state(false);
	let logsSavedTo = $state<string | null>(null);
	let logsError = $state<string | null>(null);

	async function handleSaveLogs() {
		if (logsBusy) return;
		logsBusy = true;
		logsSavedTo = null;
		logsError = null;
		try {
			logsSavedTo = await saveLogs();
		} catch (err) {
			// The reason is the shell's, in English; the shell's log keeps it.
			logsError = toErrorMessage(err);
		} finally {
			logsBusy = false;
		}
	}
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
	{#if logsAvailable}
		<!-- Not disabled while busy: a focused button that turns disabled drops focus to the page, and the handler ignores a second press anyway. -->
		<Button type="button" variant="outline" onclick={handleSaveLogs}>
			{logsBusy ? $_('app.savingLogs') : $_('app.saveLogs')}
		</Button>
	{/if}
</div>
{#if diagnosticError}
	<p class="mt-2 text-xs text-destructive-foreground" role="alert">{diagnosticError}</p>
{:else if diagnosticUnavailable}
	<p class="mt-2 text-xs text-muted-foreground">{$_('app.diagnosticUnavailable')}</p>
{/if}
{#if logsAvailable}
	<!--
		Rendered before the result lands: a live region inserted into the DOM
		together with its text is not reliably announced.
	-->
	<p class={cn('text-xs text-muted-foreground', logsSavedTo && 'mt-2')} role="status">
		{logsSavedTo ? $_('app.logsSaved', { values: { path: logsSavedTo } }) : ''}
	</p>
	{#if logsError}
		<p class="mt-2 text-xs text-destructive-foreground" role="alert">
			{$_('app.logsSaveFailed')}
		</p>
	{/if}
{/if}
