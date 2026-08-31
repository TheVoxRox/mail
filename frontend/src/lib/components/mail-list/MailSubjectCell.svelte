<script lang="ts">
	import { _ } from '$lib/i18n/index.js';
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import { cn } from '$lib/utils.js';
	import type { RovingCellProps } from '$lib/components/grid/rovingGrid.svelte.js';

	/**
	 * The subject cell — and the row's one activatable element.
	 *
	 * A real link, not a clickable cell: browse mode never delivers Enter to the
	 * grid, so a screen reader can only activate what it recognises as
	 * interactive, and a link is the one thing every reader activates there. It
	 * carries the roving tabindex, so the arrow-key model is unchanged.
	 *
	 * A link and not a button because opening a message *is* a navigation: it has
	 * an address, Back closes it and a reload reopens it. The search grid used a
	 * button back when an open result lived only in a store, which made the same
	 * action read as "button" there and as "link" in the mail list, for no reason
	 * the user could see.
	 *
	 * The row's keydown handler takes Enter first and calls `preventDefault`, so
	 * the browser follows no href on top of it, and the row's click handler
	 * ignores anything inside an anchor — every path opens the message once.
	 */
	interface Props {
		subject: string;
		unread: boolean;
		href: string;
		/**
		 * The link's own click. Every caller `preventDefault`s and navigates
		 * client-side: the href alone cannot carry the body-focus intent that
		 * decides whether the reading cursor moves into the message.
		 */
		onclick: (event: MouseEvent) => void;
		/** 1-based `aria-colindex` — a per-grid constant, hence a prop. */
		colIndex: number;
		/** Spread from `grid.cell(rowIndex, COL_SUBJECT)` — onto the link, not the cell. */
		cell: RovingCellProps;
		/** Placement in the container's tracks; see MailStatusCell. */
		placement: string;
	}

	let { subject, unread, href, onclick, colIndex, cell, placement }: Props = $props();
</script>

<div role="gridcell" aria-colindex={colIndex} class={cn('min-w-0 px-2 pt-3', placement)}>
	<a
		{href}
		{...cell}
		{onclick}
		class={cn(
			'block truncate rounded-sm text-sm no-underline hover:underline',
			unread ? 'text-foreground' : 'text-muted-foreground',
			focusRingInset
		)}
	>
		{#if unread}
			<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
		{/if}
		{subject || $_('messages.noSubject')}
	</a>
</div>
