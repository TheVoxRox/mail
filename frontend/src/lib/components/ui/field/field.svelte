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
		 * entirely when the user tabs through the form (focus mode). The
		 * paragraph itself is aria-hidden: linked, the hint is read with the
		 * control, and left in the tree as well it was read a second time as
		 * the next line in browse mode. A node aria-describedby references
		 * directly still counts when hidden, so the description stays.
		 */
		hint?: string | null;
		error?: string | null;
		errorId?: string;
		/**
		 * Whether the error is announced as it appears (`role="alert"`). A form
		 * that marks several fields at once announces only the one it moves
		 * focus to: the others are read with their fields, and a screen reader
		 * handed every one of them at once loses them to the focus move.
		 */
		announce?: boolean;
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
		announce = true,
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
		<p id={fieldHintId(forId)} aria-hidden="true" class="text-xs text-muted-foreground">{hint}</p>
	{/if}
	{#if error}
		<p
			id={errorId}
			class="text-xs text-destructive-foreground"
			role={announce ? 'alert' : undefined}
		>
			{error}
		</p>
	{/if}
</div>
