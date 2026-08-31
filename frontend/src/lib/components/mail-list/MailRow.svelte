<script lang="ts">
	import { cn } from '$lib/utils.js';
	import type { Snippet } from 'svelte';

	/**
	 * One row of a message grid — the wrapper, not its cells.
	 *
	 * The three grids already shared their mechanisms (rovingGrid, MessageFlags,
	 * MessageRowActionsMenu, focus-ring, messageStatus, formatters) but each
	 * transcribed the row itself, and a transcribed row rots the way any copy
	 * does: the `subgrid` fix had to be made twice with the same comment written
	 * twice, the `min-h-8` track floor likewise, and the search results went a
	 * while with no activatable element in the subject cell at all — the thing
	 * that gets forgotten when a row is copied rather than shared.
	 *
	 * Columns are deliberately not this component's business. The grids do not
	 * agree on their column sets (a select column here, a folder column there)
	 * and their ARIA indices are per-grid constants, so what is shared is the row
	 * and the cells, each told where in the track set to sit. The tracks
	 * themselves live on the container: a row sized to its own content lines up
	 * with the row above it only by coincidence of string width, which is why
	 * every row is `grid-cols-subgrid` and why `check:design` rejects
	 * `grid-cols-[…]` on a `role="row"`.
	 */
	interface Props {
		/** Index within the page — what `focusGridCell` resolves a roving move by. */
		rowIndex: number;
		stableId: string;
		/** 1-based, header row included. */
		ariaRowIndex: number;
		unread: boolean;
		/**
		 * The row whose message is open in the reading pane, or `null` in a grid
		 * that has no such notion. `null` and `false` are not the same thing: a
		 * grid without an open-row concept must not announce `aria-selected` at
		 * all, or every row starts claiming to be selectable.
		 */
		current?: boolean | null;
		/** Ticked for a bulk action. */
		ticked?: boolean;
		onclick: (event: MouseEvent) => void;
		onkeydown: (event: KeyboardEvent) => void;
		children: Snippet;
	}

	let {
		rowIndex,
		stableId,
		ariaRowIndex,
		unread,
		current = null,
		ticked = false,
		onclick,
		onkeydown,
		children
	}: Props = $props();
</script>

<!--
	Nothing here may take horizontal padding: under `subgrid` a padding on the row
	insets every one of its tracks at once, so the last column would slide past
	the grid's right edge. The cells carry their own.

	`grid-rows-[auto_auto]` because the two row tracks are the row's own —
	`subgrid` shares the columns only, which is why the cells that can render
	empty carry a floor of their own.
-->
<div
	role="row"
	tabindex="-1"
	data-row-index={rowIndex}
	data-stable-id={stableId}
	aria-rowindex={ariaRowIndex}
	aria-selected={current == null ? undefined : current ? 'true' : 'false'}
	aria-current={current ? 'page' : undefined}
	class={cn(
		'col-span-full grid cursor-pointer grid-cols-subgrid grid-rows-[auto_auto] border-b border-border/80 transition-colors focus-within:relative focus-within:z-10',
		current
			? 'bg-primary/10 text-foreground shadow-[inset_3px_0_0_var(--primary)]'
			: 'hover:bg-muted/40',
		ticked && !current && 'bg-primary/10',
		unread && 'font-semibold'
	)}
	{onclick}
	{onkeydown}
>
	{@render children()}
</div>
