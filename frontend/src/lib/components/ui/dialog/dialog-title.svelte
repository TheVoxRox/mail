<script lang="ts">
	/**
	 * The accessible name of a `DialogShell`. Thin on purpose: it exists so the
	 * heading style cannot drift between dialogs, and so bits-ui still gets the
	 * `Dialog.Title` it needs to wire `aria-labelledby`.
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

<Dialog.Title {id} class={cn('text-base font-semibold', className)}>
	{@render children()}
</Dialog.Title>
