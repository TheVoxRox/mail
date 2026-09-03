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
	 * interactive, and a link is the one thing every reader activates there.
	 *
	 * The roving tabindex sits on the CELL, not on that link, and the difference
	 * is audible. This cell is the only one in the grid holding both text and a
	 * focusable element with the same text, so with focus on the link a screen
	 * reader announced the cell it had entered and then the element inside it,
	 * and every arrow key read the subject twice. Measured in the running app,
	 * not inferred: one focus event per press, no live region involved — the
	 * duplication was structural, which is why it survived three separate fixes
	 * aimed at extra announcements.
	 *
	 * Earlier revisions put the tabindex on the link on the grounds that browse
	 * mode could otherwise not activate it. Listening says otherwise: with
	 * `tabindex="-1"` the anchor keeps its href and its link role, so the virtual
	 * cursor still finds it and Enter still opens the message. Focus mode is
	 * unaffected either way, because the row's keydown handler takes Enter before
	 * the browser sees the href.
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
		/** Spread from `grid.cell(rowIndex, COL_SUBJECT)` — onto the cell, not the link. */
		cell: RovingCellProps;
		/** Placement in the container's tracks; see MailStatusCell. */
		placement: string;
	}

	let { subject, unread, href, onclick, colIndex, cell, placement }: Props = $props();
</script>

<div
	role="gridcell"
	aria-colindex={colIndex}
	{...cell}
	class={cn('min-w-0 rounded-sm px-2 pt-3', placement, focusRingInset)}
>
	<a
		{href}
		tabindex="-1"
		{onclick}
		class={cn(
			'block truncate text-sm no-underline hover:underline',
			unread ? 'text-foreground' : 'text-muted-foreground'
		)}
	>
		{#if unread}
			<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
		{/if}
		{subject || $_('messages.noSubject')}
	</a>
</div>
