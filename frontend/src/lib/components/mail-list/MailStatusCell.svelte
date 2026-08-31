<script lang="ts">
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { cn } from '$lib/utils.js';
	import type { RovingCellProps } from '$lib/components/grid/rovingGrid.svelte.js';
	import type { MailSummaryResponse } from '$lib/types.js';

	/**
	 * The status cell: the row's flag icons, named by the label its column
	 * stands for. See MailRow for why the cells are shared one by one rather
	 * than as a whole row.
	 */
	interface Props {
		message: MailSummaryResponse;
		/** 1-based `aria-colindex` — a per-grid constant, hence a prop. */
		colIndex: number;
		/** Spread from `grid.cell(rowIndex, COL_STATUS)`. */
		cell: RovingCellProps;
		/**
		 * What this cell announces — `mail/messageStatus.ts`. `undefined` when the
		 * row carries no flag at all, and it has to stay `undefined` rather than
		 * become an empty string: an empty `aria-label` leaves the cell named by
		 * its contents, which is nothing, while an attribute that is absent lets
		 * the column header speak for it.
		 */
		label: string | undefined;
		/**
		 * Where in the container's track set this cell sits: `col-start-*`,
		 * `row-*`, and the alignment or edge padding that follows from sitting
		 * there — a cell cannot work out for itself that it has a neighbour above
		 * it in the same track. What never travels this way is the look: colour,
		 * type scale, border, focus ring. Those belong to the component, or the
		 * grids drift apart again by the back door.
		 */
		placement: string;
	}

	let { message, colIndex, cell, label, placement }: Props = $props();
</script>

<div
	role="gridcell"
	aria-colindex={colIndex}
	{...cell}
	aria-label={label}
	class={cn(
		'flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground',
		placement,
		focusRingInset
	)}
>
	<MessageFlags {message} />
</div>
