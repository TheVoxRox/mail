<script lang="ts">
	import { Button } from '$lib/components/ui/button/index.js';
	import { _ } from '$lib/i18n/index.js';
	import type { BootSlowLevel } from '$lib/stores/boot.js';

	interface Props {
		statusKey: string;
		slowLevel: BootSlowLevel;
		onRetry: () => void;
		onRestart: () => void;
		onDownloadDiagnostic: () => void;
		diagnosticBusy: boolean;
		diagnosticDisabled: boolean;
		diagnosticError: string | null;
		diagnosticUnavailable: boolean;
	}

	let {
		statusKey,
		slowLevel,
		onRetry,
		onRestart,
		onDownloadDiagnostic,
		diagnosticBusy,
		diagnosticDisabled,
		diagnosticError,
		diagnosticUnavailable
	}: Props = $props();
</script>

<!--
	Shell-first loading state: we hide inactive interactive controls during
	startup but show the structural frame (AppRail placeholder + sidebar
	placeholder) immediately so the user sees the app outline instead of a
	blank screen.
-->
<div
	class="flex h-full w-16 shrink-0 flex-col items-center border-r border-sidebar-border bg-sidebar px-2 py-3"
	aria-hidden="true"
>
	<div class="flex w-full flex-col items-center gap-2">
		<div class="h-10 w-10 rounded-lg bg-sidebar-accent/40"></div>
		<div class="h-10 w-10 rounded-lg bg-sidebar-accent/30"></div>
		<div class="h-10 w-10 rounded-lg bg-sidebar-accent/20"></div>
	</div>
</div>
<div
	class="flex h-full w-64 shrink-0 flex-col gap-2 border-r border-sidebar-border bg-sidebar px-3 py-4"
	aria-hidden="true"
>
	<div class="h-6 w-32 rounded bg-sidebar-accent/40"></div>
	<div class="mt-3 flex flex-col gap-2">
		<div class="h-4 w-full rounded bg-sidebar-accent/30"></div>
		<div class="h-4 w-3/4 rounded bg-sidebar-accent/30"></div>
		<div class="h-4 w-5/6 rounded bg-sidebar-accent/30"></div>
		<div class="h-4 w-2/3 rounded bg-sidebar-accent/30"></div>
	</div>
</div>
<main id="main-content" tabindex="-1" class="flex flex-1 items-center justify-center p-8">
	<div class="max-w-md text-center text-sm text-muted-foreground" role="status">
		<p>{$_(statusKey)}</p>
		{#if slowLevel === 'slow'}
			<p class="mt-2 text-xs">{$_('app.startingBackendHint')}</p>
		{:else if slowLevel === 'verySlow'}
			<p class="mt-2">{$_('app.startingBackendVerySlow')}</p>
			<div class="mt-4 flex flex-wrap justify-center gap-2">
				<Button type="button" onclick={onRetry}>{$_('app.retry')}</Button>
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
				<p class="mt-2 text-xs">{$_('app.diagnosticUnavailable')}</p>
			{/if}
		{/if}
	</div>
</main>
