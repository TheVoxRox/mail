<script lang="ts">
	import { _, appLocale } from '$lib/i18n/index.js';
	import { formatTime } from '$lib/formatters.js';

	interface Props {
		autosaving: boolean;
		autoSavedAt: Date | null;
		autosaveError?: string;
	}

	let { autosaving, autoSavedAt, autosaveError = '' }: Props = $props();
</script>

{#if autosaving}
	<span class="text-xs text-muted-foreground" role="status" aria-live="polite">
		{$_('compose.autosaving')}
	</span>
{:else if autosaveError}
	<span class="text-xs text-destructive" role="status" aria-live="polite">
		{$_('compose.autosaveFailed')}
	</span>
{:else if autoSavedAt}
	<span class="text-xs text-muted-foreground" role="status" aria-live="polite">
		{$_('compose.autosavedAt', {
			values: { time: formatTime(autoSavedAt, $appLocale ?? 'cs', true) }
		})}
	</span>
{/if}
