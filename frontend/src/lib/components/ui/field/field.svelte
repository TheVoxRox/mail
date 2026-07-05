<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils.js';

	interface Props {
		class?: string;
		label?: string;
		for?: string;
		/**
		 * Rendered with id="{for}-hint" so the control inside can reference it
		 * via aria-describedby — without the link, screen readers skip the
		 * hint entirely when the user tabs through the form (focus mode).
		 */
		hint?: string | null;
		error?: string | null;
		errorId?: string;
		labelClass?: string;
		children?: Snippet;
	}

	let {
		class: className,
		label,
		for: forId,
		hint = null,
		error = null,
		errorId,
		labelClass,
		children
	}: Props = $props();
</script>

<div class={cn('space-y-1', className)}>
	{#if label}
		<label for={forId} class={cn('block text-sm font-medium text-foreground', labelClass)}>
			{label}
		</label>
	{/if}

	{@render children?.()}

	{#if hint}
		<p id={forId ? `${forId}-hint` : undefined} class="text-xs text-muted-foreground">{hint}</p>
	{/if}
	{#if error}
		<p id={errorId} class="text-xs text-destructive" role="alert">{error}</p>
	{/if}
</div>
