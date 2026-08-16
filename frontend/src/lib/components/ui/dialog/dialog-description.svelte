<script lang="ts">
	/**
	 * The supporting line under a `DialogTitle`. Rendering one is enough for a
	 * screen reader to read it after the title — bits-ui points the dialog's
	 * `aria-describedby` at it. Pass an `id` only when something else on the page
	 * needs to reference the same text.
	 */
	import { Dialog } from 'bits-ui';
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	interface Props {
		id?: string;
		class?: string;
		children: Snippet;
	}

	let { id, class: className, children }: Props = $props();
</script>

<Dialog.Description {id} class={cn('mt-2 text-sm text-muted-foreground', className)}>
	{@render children()}
</Dialog.Description>
