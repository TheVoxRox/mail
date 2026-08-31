<script lang="ts">
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	/**
	 * The trailing actions cell. It holds the menu rather than being it: the
	 * trigger already absorbs the per-grid differences through its own optional
	 * props (`currentFolderRef`, `onAfterAction`, `actions`, `triggerLabel`), and
	 * routing those through a second component would only add a layer that has to
	 * be kept in step with the first.
	 *
	 * What is shared is the cell: it stops the click so the row does not open the
	 * message underneath the menu, and it is `tabindex="-1"` because the roving
	 * position belongs to the trigger inside it, not to the cell.
	 */
	interface Props {
		/** 1-based `aria-colindex` — a per-grid constant, hence a prop. */
		colIndex: number;
		/** Placement in the container's tracks; see MailStatusCell. */
		placement: string;
		children: Snippet;
	}

	let { colIndex, placement, children }: Props = $props();
</script>

<!-- svelte-ignore a11y_click_events_have_key_events -->
<div
	role="gridcell"
	aria-colindex={colIndex}
	tabindex="-1"
	class={cn('flex items-center justify-center pr-2', placement)}
	onclick={(e) => e.stopPropagation()}
>
	{@render children()}
</div>
