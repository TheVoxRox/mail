<script lang="ts">
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { cn } from '$lib/utils.js';
	import type { RovingCellProps } from '$lib/components/grid/rovingGrid.svelte.js';

	/**
	 * The counterpart cell: who the message is with. Which side that is belongs
	 * to the caller — the inbox shows the sender, Drafts and Sent the recipient,
	 * and the search grid always the sender — so this takes the resolved text.
	 */
	interface Props {
		/** May be empty: `recipientsTo` is nullable, and a draft saved without a To header has none. */
		text: string;
		unread: boolean;
		/** 1-based `aria-colindex` — a per-grid constant, hence a prop. */
		colIndex: number;
		/** Spread from `grid.cell(rowIndex, COL_SENDER)`. */
		cell: RovingCellProps;
		/** Placement in the container's tracks; see MailStatusCell. */
		placement: string;
	}

	let { text, unread, colIndex, cell, placement }: Props = $props();
</script>

<!--
	`min-h-8` because `subgrid` shares the columns only — the two row tracks stay
	each row's own, so an empty cell still collapses one and leaves the row ~20px
	shorter than its neighbours. The value is the height the filled cell already
	has (20px line + 12px padding), so it costs nothing when there is text.
-->
<div
	role="gridcell"
	aria-colindex={colIndex}
	{...cell}
	class={cn(
		'min-h-8 truncate rounded-sm px-2 pb-3 text-sm',
		placement,
		unread ? 'text-foreground' : 'text-muted-foreground',
		focusRingInset
	)}
>
	{text}
</div>
