<script lang="ts">
	import { _, appLocale } from '$lib/i18n/index.js';
	import { folders } from '$lib/stores/folders.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import {
		computeNextCell,
		focusGridCell,
		ROW_NAV_PAGE_STEP
	} from '$lib/components/grid/rowNavigation.js';
	import { cn } from '$lib/utils.js';
	import { formatNumericDate } from '$lib/formatters.js';
	import type { FolderResponse, MailSummaryResponse, PagedResponse } from '$lib/types.js';
	import { tick } from 'svelte';

	interface Props {
		results: PagedResponse<MailSummaryResponse>;
		onSelect: (message: MailSummaryResponse) => void;
		/** Re-run the search after a row action mutates a result (move/delete/flag/seen). */
		onAfterAction: () => void;
	}

	let { results, onSelect, onAfterAction }: Props = $props();

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
	let focusedRow = $state(0);
	let focusedCol = $state(COL_SUBJECT);

	function labelForFolderRef(folderRef: string): string {
		const f = $folders.find((entry: FolderResponse) => entry.folderRef === folderRef);
		return f ? folderLabel(f, $_) : folderRef;
	}

	function focusCell(rowIndex: number, col: number): void {
		void tick().then(() => focusGridCell(gridElement, rowIndex, col));
	}

	function setFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
		focusCell(rowIndex, col);
	}

	function handleCellFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
	}

	function handleKeydown(
		event: KeyboardEvent,
		message: MailSummaryResponse,
		rowIndex: number
	): void {
		if (event.key === 'Enter' || event.key === ' ') {
			// The actions cell owns Enter/Space to open its menu.
			if (focusedCol === COL_ACTIONS) return;
			event.preventDefault();
			onSelect(message);
			return;
		}
		const next = computeNextCell(event.key, {
			row: rowIndex,
			col: focusedCol,
			maxRow: results.content.length - 1,
			maxCol: MAX_COL,
			ctrl: event.ctrlKey,
			pageStep: ROW_NAV_PAGE_STEP
		});
		if (!next) return;
		event.preventDefault();
		setFocus(next.row, next.col);
	}

	function handleRowClick(event: MouseEvent, message: MailSummaryResponse): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		onSelect(message);
	}

	// Keep the roving focus index inside the page when results shrink.
	$effect(() => {
		const max = results.content.length - 1;
		if (focusedRow > max) focusedRow = Math.max(0, max);
	});
</script>

<div
	bind:this={gridElement}
	role="grid"
	aria-label={$_('search.resultsLandmark')}
	aria-rowcount={results.totalElements + 1}
	aria-colcount={COL_COUNT}
	class="flex-1 overflow-y-auto bg-background"
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
		{@const statusLabel = messageStatusLabel(message, $_)}
		{@const formattedDate = formatNumericDate(message.receivedAt, $appLocale ?? 'cs')}
		<div
			role="row"
			tabindex="-1"
			data-row-index={rowIndex}
			data-stable-id={message.stableId}
			aria-rowindex={results.page * results.size + rowIndex + 2}
			class={cn(
				'grid cursor-pointer grid-cols-[auto_minmax(0,1fr)_auto_auto] grid-rows-[auto_auto] border-b border-border/80 transition-colors hover:bg-muted/45 focus-within:relative focus-within:z-10',
				!message.seen && 'font-semibold'
			)}
			onclick={(e) => handleRowClick(e, message)}
			onkeydown={(e) => handleKeydown(e, message, rowIndex)}
		>
			<div
				role="gridcell"
				aria-colindex={COL_STATUS + 1}
				data-cell-target
				data-col={COL_STATUS}
				tabindex={focusedRow === rowIndex && focusedCol === COL_STATUS ? 0 : -1}
				aria-label={statusLabel}
				onfocus={() => handleCellFocus(rowIndex, COL_STATUS)}
				class="row-span-2 flex items-center gap-1 rounded-sm px-2 text-[0.72rem] text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
			>
				<MessageFlags {message} />
			</div>
			<div
				role="gridcell"
				aria-colindex={COL_SUBJECT + 1}
				data-cell-target
				data-col={COL_SUBJECT}
				tabindex={focusedRow === rowIndex && focusedCol === COL_SUBJECT ? 0 : -1}
				onfocus={() => handleCellFocus(rowIndex, COL_SUBJECT)}
				class={cn(
					'col-start-2 row-start-1 truncate rounded-sm px-2 pt-3 text-[0.9rem] outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
					!message.seen ? 'text-foreground' : 'text-muted-foreground'
				)}
			>
				{#if !message.seen}
					<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
				{/if}
				{message.subject || $_('messages.noSubject')}
			</div>
			<div
				role="gridcell"
				aria-colindex={COL_SENDER + 1}
				data-cell-target
				data-col={COL_SENDER}
				tabindex={focusedRow === rowIndex && focusedCol === COL_SENDER ? 0 : -1}
				onfocus={() => handleCellFocus(rowIndex, COL_SENDER)}
				class={cn(
					'col-start-2 row-start-2 truncate rounded-sm px-2 pb-3 text-[0.82rem] outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
					!message.seen ? 'text-foreground' : 'text-muted-foreground'
				)}
			>
				{message.sender}
			</div>
			<div
				role="gridcell"
				aria-colindex={COL_DATE + 1}
				data-cell-target
				data-col={COL_DATE}
				tabindex={focusedRow === rowIndex && focusedCol === COL_DATE ? 0 : -1}
				onfocus={() => handleCellFocus(rowIndex, COL_DATE)}
				class="col-start-3 row-start-1 flex items-center justify-end rounded-sm px-3 pt-3 text-[0.72rem] text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
			>
				<time datetime={message.receivedAt}>{formattedDate}</time>
			</div>
			<div
				role="gridcell"
				aria-colindex={COL_FOLDER + 1}
				data-cell-target
				data-col={COL_FOLDER}
				tabindex={focusedRow === rowIndex && focusedCol === COL_FOLDER ? 0 : -1}
				onfocus={() => handleCellFocus(rowIndex, COL_FOLDER)}
				class="col-start-3 row-start-2 flex items-center justify-end truncate rounded-sm px-3 pb-3 text-[0.72rem] text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
			>
				{labelForFolderRef(message.folderName)}
			</div>
			<!-- svelte-ignore a11y_click_events_have_key_events -->
			<div
				role="gridcell"
				aria-colindex={COL_ACTIONS + 1}
				tabindex="-1"
				class="col-start-4 row-span-2 flex items-center justify-center pr-2"
				onclick={(e) => e.stopPropagation()}
			>
				<MessageRowActionsMenu
					{message}
					col={COL_ACTIONS}
					focused={focusedRow === rowIndex && focusedCol === COL_ACTIONS}
					onCellFocus={() => handleCellFocus(rowIndex, COL_ACTIONS)}
					currentFolderRef={message.folderName}
					{onAfterAction}
				/>
			</div>
		</div>
	{/each}
</div>
