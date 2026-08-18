<script lang="ts">
	import type { Snippet } from 'svelte';
	import { cn } from '$lib/utils.js';
	import { fieldControlProps, fieldHintId, type FieldControlProps } from './field.js';

	interface Props {
		class?: string;
		label?: string;
		for?: string;
		/**
		 * Rendered with id="{for}-hint" and handed to the control through the
		 * children snippet — without the link, screen readers skip the hint
		 * entirely when the user tabs through the form (focus mode).
		 */
		hint?: string | null;
		error?: string | null;
		errorId?: string;
		labelClass?: string;
		/**
		 * Receives the `aria-describedby` / `aria-invalid` the control needs to
		 * be described by the hint and error above. Spread it:
		 * `{#snippet children(control)}<Input {...control} />{/snippet}`.
		 */
		children?: Snippet<[FieldControlProps]>;
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

	const control = $derived(fieldControlProps({ for: forId, hint, error, errorId }));
</script>

<div class={cn('space-y-1', className)}>
	{#if label}
		<label for={forId} class={cn('block text-sm font-medium text-foreground', labelClass)}>
			{label}
		</label>
	{/if}

	{@render children?.(control)}

	{#if hint}
		<p id={fieldHintId(forId)} class="text-xs text-muted-foreground">{hint}</p>
	{/if}
	{#if error}
		<p id={errorId} class="text-xs text-destructive-foreground" role="alert">{error}</p>
	{/if}
</div>
