<script lang="ts">
	import { _, appLocale } from '$lib/i18n/index.js';
	import { folders } from '$lib/stores/folders.js';
	import { folderLabelByRef } from '$lib/mail/folderLabel.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import MailActionsCell from '$lib/components/mail-list/MailActionsCell.svelte';
	import MailDateCell from '$lib/components/mail-list/MailDateCell.svelte';
	import MailRow from '$lib/components/mail-list/MailRow.svelte';
	import MailSenderCell from '$lib/components/mail-list/MailSenderCell.svelte';
	import MailStatusCell from '$lib/components/mail-list/MailStatusCell.svelte';
	import MailSubjectCell from '$lib/components/mail-list/MailSubjectCell.svelte';
	import { createRovingGrid } from '$lib/components/grid/rovingGrid.svelte.js';
	import { isRowBackgroundClick } from '$lib/components/grid/rowActivation.js';
	import { cn } from '$lib/utils.js';
	import { formatNumericDate } from '$lib/formatters.js';
	import type { MailSummaryResponse, PagedResponse } from '$lib/types.js';
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';

	interface Props {
		results: PagedResponse<MailSummaryResponse>;
		/**
		 * Where a result's subject link points — its address inside the search
		 * context, built by the screen that owns the URL. The grid does not
		 * assemble it itself: the query and page it would have to carry are the
		 * route's state, not the grid's.
		 */
		hrefFor: (message: MailSummaryResponse) => string;
		/**
		 * Opens a result. The link above navigates on its own; this keeps the
		 * navigation client-side and records the body-focus intent, which a
		 * native follow of the href could not.
		 */
		onSelect: (message: MailSummaryResponse) => void;
		/**
		 * Re-run the search after a row action mutated a result
		 * (move/delete/flag/seen). Carries the row it was invoked on so the
		 * caller can hand focus to a neighbour if that row is now gone.
		 */
		onAfterAction: (message: MailSummaryResponse) => void;
		/**
		 * Row to put focus back on once the grid renders — the result whose
		 * detail was just closed, or the neighbour of a row an action removed.
		 * The grid unmounts both while the detail shows and while the search
		 * reloads, so a roving position cannot survive inside it.
		 */
		restoreFocusStableId?: string | null;
		/** Fired once the restore above was attempted, so the caller can clear it. */
		onFocusRestored?: () => void;
	}

	let {
		results,
		hrefFor,
		onSelect,
		onAfterAction,
		restoreFocusStableId = null,
		onFocusRestored
	}: Props = $props();

	/*
	 * Search results reuse the same ARIA grid + roving cell navigation as the
	 * inbox (MessageList) so a screen-reader user can review each field
	 * (status, subject, sender, date, folder, actions) separately instead of
	 * hearing one flattened aria-label per row. The trailing actions column
	 * mirrors the inbox; there is no select column (search has no bulk mode).
	 */
	const COL_STATUS = 0;
	const COL_SUBJECT = 1;
	const COL_SENDER = 2;
	const COL_DATE = 3;
	const COL_FOLDER = 4;
	const COL_ACTIONS = 5;
	const MAX_COL = COL_ACTIONS;
	const COL_COUNT = 6;

	let gridElement = $state<HTMLDivElement | null>(null);
	const grid = createRovingGrid({
		element: () => gridElement,
		initialCol: COL_SUBJECT,
		maxCol: MAX_COL
	});

	function handleKeydown(
		event: KeyboardEvent,
		message: MailSummaryResponse,
		rowIndex: number
	): void {
		if (event.key === 'Enter' || event.key === ' ') {
			// The actions cell owns Enter/Space to open its menu.
			if (grid.col === COL_ACTIONS) return;
			event.preventDefault();
			onSelect(message);
			return;
		}
		grid.navigate(event, rowIndex, results.content.length);
	}

	/**
	 * The subject link's own click. Mirrors MessageList: `preventDefault` so the
	 * browser does not follow the href, then the screen's opener runs — it
	 * navigates client-side *and* records the body-focus intent the href alone
	 * cannot carry.
	 */
	function handleSubjectClick(event: MouseEvent, message: MailSummaryResponse): void {
		event.preventDefault();
		onSelect(message);
	}

	function handleRowClick(event: MouseEvent, message: MailSummaryResponse): void {
		if (isRowBackgroundClick(event)) onSelect(message);
	}

	// Keep the roving focus index inside the page when results shrink.
	$effect(() => {
		grid.clampRow(results.content.length);
	});

	/**
	 * Moves the roving focus onto `stableId`'s subject cell. Reports back
	 * whether it happened — the row may not be on this page (a stale request
	 * after the query changed), and the caller must only retire a request that
	 * was actually honoured.
	 */
	function focusRowSubject(stableId: string): boolean {
		const idx = results.content.findIndex((message) => message.stableId === stableId);
		if (idx < 0) return false;
		return grid.focusNow(idx, COL_SUBJECT);
	}

	/*
	 * Coming back from a result's detail, or from a row action that removed a
	 * row: the grid mounts fresh, so focus would start over at the first row
	 * (or fall to <body>). Deferred a frame so the move lands after the page
	 * finished swapping the detail out.
	 */
	$effect(() => {
		const stableId = restoreFocusStableId;
		if (!stableId) return;
		requestAnimationFrame(() => {
			if (focusRowSubject(stableId)) onFocusRestored?.();
		});
	});
</script>

<!--
	Shared column tracks, same as MessageList and ConversationList — this grid
	has the same fault and one of its own. The status cell is `auto` around
	`MessageFlags`, so a result with a flag measures 30px there and one
	without 16px, and the subject starts at 366px against 352px. On top of
	that the third track carries the date and the folder name stacked, so its
	width is whichever of the two is wider in that row — folder names differ
	per result by construction here, which a search result list makes routine
	rather than incidental.

	Four tracks against the six of `aria-colcount`: subject and sender share
	the second, date and folder the third. `content-start` keeps a short
	result list from stretching its rows over the viewport.
-->
<div
	bind:this={gridElement}
	role="grid"
	aria-label={$_('search.resultsLandmark')}
	aria-rowcount={results.totalElements + 1}
	aria-colcount={COL_COUNT}
	class="grid flex-1 grid-cols-[auto_minmax(0,1fr)_auto_auto] content-start overflow-y-auto bg-background"
>
	<div role="row" aria-rowindex={1} class="sr-only">
		<span role="columnheader" aria-colindex={1}>{$_('messages.columnHeaderStatus')}</span>
		<span role="columnheader" aria-colindex={2}>{$_('messages.columnHeaderSubject')}</span>
		<span role="columnheader" aria-colindex={3}>{$_('messages.columnHeaderSender')}</span>
		<span role="columnheader" aria-colindex={4}>{$_('messages.columnHeaderDate')}</span>
		<span role="columnheader" aria-colindex={5}>{$_('search.columnHeaderFolder')}</span>
		<span role="columnheader" aria-colindex={6}>{$_('messages.columnHeaderActions')}</span>
	</div>
	{#each results.content as message, rowIndex (message.stableId)}
		{@const unread = !message.seen}
		<MailRow
			{rowIndex}
			stableId={message.stableId}
			ariaRowIndex={results.page * results.size + rowIndex + 2}
			{unread}
			onclick={(e) => handleRowClick(e, message)}
			onkeydown={(e) => handleKeydown(e, message, rowIndex)}
		>
			<MailStatusCell
				{message}
				colIndex={COL_STATUS + 1}
				cell={grid.cell(rowIndex, COL_STATUS)}
				label={messageStatusLabel(message, $_)}
				placement="row-span-2"
			/>
			<MailSubjectCell
				subject={message.subject}
				{unread}
				href={hrefFor(message)}
				onclick={(event) => handleSubjectClick(event, message)}
				colIndex={COL_SUBJECT + 1}
				cell={grid.cell(rowIndex, COL_SUBJECT)}
				placement="col-start-2 row-start-1"
			/>
			<MailSenderCell
				text={message.sender}
				{unread}
				colIndex={COL_SENDER + 1}
				cell={grid.cell(rowIndex, COL_SENDER)}
				placement="col-start-2 row-start-2"
			/>
			<MailDateCell
				receivedAt={message.receivedAt}
				formatted={formatNumericDate(message.receivedAt, $appLocale ?? 'cs')}
				colIndex={COL_DATE + 1}
				cell={grid.cell(rowIndex, COL_DATE)}
				placement="col-start-3 row-start-1 justify-end pt-3"
			/>
			<!--
				The folder name is this grid's own column: results span folders, which
				is the one thing a search row says that a folder listing never has to.
			-->
			<div
				role="gridcell"
				aria-colindex={COL_FOLDER + 1}
				{...grid.cell(rowIndex, COL_FOLDER)}
				class={cn(
					'col-start-3 row-start-2 flex items-center justify-end truncate rounded-sm px-3 pb-3 text-caption text-muted-foreground',
					focusRingInset
				)}
			>
				{folderLabelByRef($folders, message.folderName, $_)}
			</div>
			<MailActionsCell colIndex={COL_ACTIONS + 1} placement="col-start-4 row-span-2">
				<MessageRowActionsMenu
					{message}
					col={COL_ACTIONS}
					focused={grid.isAt(rowIndex, COL_ACTIONS)}
					onCellFocus={() => grid.track(rowIndex, COL_ACTIONS)}
					currentFolderRef={message.folderName}
					onAfterAction={() => onAfterAction(message)}
				/>
			</MailActionsCell>
		</MailRow>
	{/each}
</div>
