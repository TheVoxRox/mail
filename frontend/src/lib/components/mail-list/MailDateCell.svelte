<script lang="ts">
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { cn } from '$lib/utils.js';
	import type { RovingCellProps } from '$lib/components/grid/rovingGrid.svelte.js';

	/**
	 * The date cell. Formatting is the caller's: the flat list shortens a date to
	 * whatever tells it from today, while the search grid keeps a numeric one
	 * because its results span months. What is shared is the `<time datetime>`
	 * element around it — the part that is semantics rather than taste.
	 */
	interface Props {
		/** ISO timestamp for `datetime`; the machine-readable half. */
		receivedAt: string;
		/** What the cell reads out — already formatted for the locale. */
		formatted: string;
		/** 1-based `aria-colindex` — a per-grid constant, hence a prop. */
		colIndex: number;
		/** Spread from `grid.cell(rowIndex, COL_DATE)`. */
		cell: RovingCellProps;
		/**
		 * Placement in the container's tracks, alignment inside them included:
		 * the search grid stacks its date over the folder name and right-aligns
		 * both, the flat list centres a single date over both row tracks.
		 */
		placement: string;
	}

	let { receivedAt, formatted, colIndex, cell, placement }: Props = $props();
</script>

<div
	role="gridcell"
	aria-colindex={colIndex}
	{...cell}
	class={cn(
		'flex items-center rounded-sm px-3 text-caption text-muted-foreground',
		placement,
		focusRingInset
	)}
>
	<time datetime={receivedAt}>{formatted}</time>
</div>
