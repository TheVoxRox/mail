<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { messagesState, loadPage } from '$lib/stores/messages.js';
	import { folders } from '$lib/stores/folders.js';
	import {
		clearMessageSelection,
		pruneMessageSelection,
		selectedMessageIds,
		selectedMessageIdSet,
		setMessageSelection,
		toggleMessageSelection
	} from '$lib/stores/messageSelection.js';
	import {
		clearListFocusRestore,
		listFocusRestore,
		selectedMessage
	} from '$lib/stores/selectedMessage.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import Pagination from '$lib/components/Pagination.svelte';
	import MailBulkToolbar, {
		type BulkAction
	} from '$lib/components/mail-list/MailBulkToolbar.svelte';
	import MailListState from '$lib/components/mail-list/MailListState.svelte';
	import { createRovingGrid } from '$lib/components/grid/rovingGrid.svelte.js';
	import { cn } from '$lib/utils.js';
	import { formatMessageListDate } from '$lib/formatters.js';
	import { moveTargetsFor } from '$lib/mail/moveTargets.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import type { FolderResponse, MailSummaryResponse } from '$lib/types.js';
	import { requestBodyFocus, suppressBodyFocus } from '$lib/mail/bodyFocus.js';
	import { deleteMessages, markMessagesSeen, moveMessages } from '$lib/mail/mailbox.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import {
		EFFECTIVE_READING_PANE_CONTEXT_KEY,
		type EffectiveReadingPaneContext
	} from '$lib/mail/readingPaneContext.js';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { getContext } from 'svelte';
	import { get } from 'svelte/store';

	const COL_SELECT = 0;
	const COL_STATUS = 1;
	const COL_SUBJECT = 2;
	const COL_SENDER = 3;
	const COL_DATE = 4;
	const COL_ACTIONS = 5;
	const MAX_COL = COL_ACTIONS;

	let gridElement = $state<HTMLDivElement | null>(null);
	let emptyStateElement = $state<HTMLParagraphElement | null>(null);
	let bulkAction = $state<BulkAction | null>(null);
	let bulkError = $state<string | null>(null);
	const grid = createRovingGrid({
		element: () => gridElement,
		initialCol: COL_SUBJECT,
		maxCol: MAX_COL
	});

	const selectedCount = $derived($selectedMessageIds.length);
	const pageStableIds = $derived(
		$messagesState.status === 'ready'
			? $messagesState.page.content.map((message) => message.stableId)
			: []
	);
	const allPageMessagesSelected = $derived(
		pageStableIds.length > 0 &&
			pageStableIds.every((stableId) => $selectedMessageIdSet.has(stableId))
	);
	const someSelectedOnPage = $derived(
		pageStableIds.some((stableId) => $selectedMessageIdSet.has(stableId)) &&
			!allPageMessagesSelected
	);
	const currentFolderName = $derived(
		$messagesState.status === 'idle' ? '' : $messagesState.context.folderName
	);
	const moveTargets = $derived(moveTargetsFor($folders, currentFolderName));
	// In Drafts/Sent the sender is always the account owner, so show the recipient (To) instead.
	const currentFolderRole = $derived(
		$folders.find((folder: FolderResponse) => folder.folderRef === currentFolderName)?.role
	);
	const showRecipients = $derived(currentFolderRole === 'DRAFTS' || currentFolderRole === 'SENT');

	// Effective pane mode from the mail layout; the `off` fallback keeps arrow
	// keys from navigating if the list ever renders outside that layout.
	const readingPaneCtx =
		getContext<EffectiveReadingPaneContext>(EFFECTIVE_READING_PANE_CONTEXT_KEY) ??
		({ pane: 'off' } satisfies EffectiveReadingPaneContext);

	function messageHref(accountId: number, folderName: string, stableId: string): string {
		return resolve('/mail/[accountId]/[folderName]/[stableId]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName),
			stableId: encodeURIComponent(stableId)
		});
	}

	function draftHref(stableId: string): string {
		return `${resolve('/compose')}?draft=${encodeURIComponent(stableId)}`;
	}

	/**
	 * Where the row's subject link points. Mirrors handleSelect — including the
	 * Drafts detour into the composer — so an assistive technology that follows
	 * the link natively instead of firing a click lands in the same place.
	 */
	function rowHref(message: MailSummaryResponse): string {
		if ($messagesState.status !== 'ready') return '';
		const { accountId, folderName } = $messagesState.context;
		return currentFolderRole === 'DRAFTS'
			? draftHref(message.stableId)
			: messageHref(accountId, folderName, message.stableId);
	}

	/**
	 * Opens `message`. `focusBody` marks a deliberate open (Enter/Space, click)
	 * — only then does the reading cursor move into the message body. A row
	 * change that follows the roving focus in a split pane opens the message
	 * with the opposite intent, so focus stays on the grid cell and the next
	 * Arrow key keeps navigating the list (see mail/bodyFocus.ts).
	 */
	async function handleSelect(
		message: MailSummaryResponse,
		options: { focusBody?: boolean } = {}
	): Promise<void> {
		if ($messagesState.status !== 'ready') return;
		const { accountId, folderName } = $messagesState.context;
		// Drafts open in the composer (with a Send button), not the read-only viewer.
		const folder = $folders.find((f: FolderResponse) => f.folderRef === folderName);
		if (folder?.role === 'DRAFTS') {
			await goto(draftHref(message.stableId));
			return;
		}
		if (options.focusBody) requestBodyFocus(message.stableId);
		else suppressBodyFocus(message.stableId);
		await goto(messageHref(accountId, folderName, message.stableId));
	}

	function handleKeydown(event: KeyboardEvent, message: MailSummaryResponse, rowIndex: number) {
		if (event.key === 'Enter' || event.key === ' ') {
			if (grid.col === COL_SELECT || grid.col === COL_ACTIONS) return;
			event.preventDefault();
			void handleSelect(message, { focusBody: true });
			return;
		}
		// Plain Delete only — matches the open-message handler in globalShortcuts.ts
		// so a modifier combo (Shift/Ctrl+Delete) behaves the same whether focus is
		// on a list row or in the reading pane. Modified Delete falls through to the
		// navigation branch, where nextCell returns null for 'Delete' → no-op.
		if (
			event.key === 'Delete' &&
			!event.ctrlKey &&
			!event.metaKey &&
			!event.altKey &&
			!event.shiftKey
		) {
			event.preventDefault();
			void deleteMessages([message.stableId]);
			return;
		}
		if ($messagesState.status !== 'ready') return;
		const items = $messagesState.page.content;
		const next = grid.nextCell(event, rowIndex, items.length);
		if (!next) return;
		event.preventDefault();
		// A row change moves the reading-pane selection with focus; a column-only
		// move just shifts the roving cell within the current row.
		if (next.row !== rowIndex) {
			/*
			 * Selection may follow focus only while a reading pane is showing next
			 * to the list. In effective `off` mode the detail route replaces the
			 * list, and Drafts open the composer — there, navigating on a row
			 * change would tear the user out of the list, so arrows only move
			 * focus and the message opens on Enter/Space.
			 */
			if (readingPaneCtx.pane === 'off' || currentFolderRole === 'DRAFTS') {
				grid.moveTo(next.row, next.col);
			} else {
				selectAndFocus(next.row, next.col, items[next.row]);
			}
		} else {
			grid.moveTo(next.row, next.col);
		}
	}

	/*
	 * The mouse follows the web-mail model (Gmail, Outlook Web): a click anywhere
	 * on the row opens the message and moves the reading cursor into the body,
	 * and the checkbox is the only thing that selects. #201 briefly made a single
	 * click select instead — the Outlook *desktop* model — and that silently
	 * broke Enter for a screen reader in browse mode: the reader keeps the
	 * unmodified keys for its own navigation and never delivers Enter as a
	 * keydown, it activates the row instead, and the activation arrives here as
	 * an ordinary click. Treating that click as "select" made Enter look dead.
	 *
	 * The checkbox and the actions menu stop their own clicks before this, and
	 * the subject link handles its own — see handleSubjectClick.
	 */
	function handleRowClick(event: MouseEvent, message: MailSummaryResponse): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		openDeliberately(message);
	}

	/**
	 * The subject is a real link, the one affordance every screen reader can
	 * activate in browse mode regardless of how it chooses to do it (a synthetic
	 * click, a simulated mouse click, or following the href). Its own handler
	 * keeps the navigation client-side and records the body-focus intent, which a
	 * native follow of the href could not.
	 */
	function handleSubjectClick(event: MouseEvent, message: MailSummaryResponse): void {
		event.preventDefault();
		openDeliberately(message);
	}

	function openDeliberately(message: MailSummaryResponse): void {
		// Invalidate a refocus an in-flight selectAndFocus queued, so the body wins.
		selectToken += 1;
		void handleSelect(message, { focusBody: true });
	}

	function handleSelectAll(checked: boolean): void {
		setMessageSelection(checked ? pageStableIds : []);
	}

	function selectionLabel(message: MailSummaryResponse): string {
		return $_('messages.selectMessage', {
			values: { subject: message.subject || $_('messages.noSubject') }
		});
	}

	async function runBulkAction(
		action: BulkAction,
		run: (stableIds: readonly string[]) => Promise<unknown>
	): Promise<void> {
		const ids = $selectedMessageIds;
		if (ids.length === 0 || bulkAction) return;
		bulkAction = action;
		bulkError = null;
		try {
			await run(ids);
		} catch (err) {
			bulkError = toErrorMessage(err);
		} finally {
			bulkAction = null;
		}
	}

	function handleBulkMarkSeen(seen: boolean): void {
		void runBulkAction(seen ? 'read' : 'unread', (stableIds) => markMessagesSeen(stableIds, seen));
	}

	function handleBulkDelete(): void {
		void runBulkAction('delete', deleteMessages);
	}

	function handleBulkMoveTo(folderRef: string): void {
		void runBulkAction('move', (stableIds) => moveMessages(stableIds, folderRef));
	}

	// Bumped on every selection. handleSelect() navigates, and SvelteKit cancels
	// an in-flight navigation when a newer one starts (rapid Arrow/Page keys). The
	// superseded navigation's promise still settles and would re-focus its now-stale
	// row last, bouncing focus backwards — so a `.finally` only re-focuses while it
	// is still the latest selection.
	let selectToken = 0;
	function selectAndFocus(rowIndex: number, col: number, message: MailSummaryResponse): void {
		grid.moveTo(rowIndex, col);
		const token = ++selectToken;
		void handleSelect(message).finally(() => {
			if (token === selectToken) grid.moveTo(rowIndex, col);
		});
	}

	$effect(() => {
		const restore = $listFocusRestore;
		if (!restore || $messagesState.status !== 'ready') return;

		/*
		 * The mutation took the last row with it: the grid is gone and the
		 * empty-state message is the only thing left to receive focus. Without
		 * this focus falls to <body> — the deletion would be silent and the
		 * reading cursor would be nowhere.
		 */
		if (restore.kind === 'emptied') {
			if (!emptyStateElement) return;
			const target = emptyStateElement;
			const frame = requestAnimationFrame(() => {
				target.focus();
				clearListFocusRestore();
			});
			return () => cancelAnimationFrame(frame);
		}

		if (!gridElement) return;
		const idx = $messagesState.page.content.findIndex((m) => m.stableId === restore.stableId);
		if (idx < 0) return;

		const frame = requestAnimationFrame(() => {
			/*
			 * Restored focus always lands on a content cell: the actions column
			 * would announce a *different* message's menu trigger, and the select
			 * column its checkbox ("Select message X") — after a delete the
			 * selection is gone and the checkbox says nothing about where focus
			 * moved. The subject cell is the row's reading anchor.
			 */
			const anchorCol =
				grid.col === COL_ACTIONS || grid.col === COL_SELECT ? COL_SUBJECT : grid.col;
			grid.moveTo(idx, anchorCol);
			clearListFocusRestore();
		});

		return () => cancelAnimationFrame(frame);
	});

	$effect(() => {
		if ($messagesState.status !== 'ready') return;
		grid.clampRow($messagesState.page.content.length);
	});

	$effect(() => {
		if ($messagesState.status !== 'ready') {
			clearMessageSelection();
			return;
		}
		pruneMessageSelection(pageStableIds);
	});

	/*
	 * Announce the available bulk actions the first time a selection starts.
	 * The action buttons render only once something is selected and the focus
	 * stays on the row, so without this a screen-reader user ticks a checkbox
	 * and has no signal that Mark read / Move / Delete just appeared. Resets
	 * when the selection empties so the next session announces again. The flag
	 * is a plain (non-reactive) variable to avoid an effect self-dependency.
	 */
	let bulkActionsAnnounced = false;
	$effect(() => {
		if (selectedCount > 0) {
			if (!bulkActionsAnnounced) {
				bulkActionsAnnounced = true;
				announcePolite($_('messages.bulkActionsAvailable'));
			}
		} else {
			bulkActionsAnnounced = false;
		}
	});

	async function navigateToPage(target: number) {
		if ($messagesState.status !== 'ready') return;
		const ctx = $messagesState.context;
		const lastPage = Math.max(0, $messagesState.page.totalPages - 1);
		const next = Math.min(Math.max(0, target), lastPage);
		if (next === ctx.page) return;
		await loadPage(ctx.accountId, ctx.folderName, next, ctx.size);
		announcePageChange();
	}

	function announcePageChange() {
		const snapshot = get(messagesState);
		if (snapshot.status !== 'ready') return;
		announcePolite(messagesPageInfo($_, snapshot.page));
	}
</script>

<MailListState state={$messagesState} bind:emptyRef={emptyStateElement}>
	{#snippet ready(pageData)}
		<div class="flex min-h-0 flex-1 flex-col bg-background">
			<MailBulkToolbar
				allSelected={allPageMessagesSelected}
				someSelected={someSelectedOnPage}
				hasSelection={selectedCount > 0}
				summary={$_('messages.selectedCount', { values: { count: selectedCount } })}
				busy={bulkAction}
				{moveTargets}
				error={bulkError}
				onSelectAll={handleSelectAll}
				onClear={clearMessageSelection}
				onDelete={handleBulkDelete}
				onMarkSeen={handleBulkMarkSeen}
				onMoveTo={handleBulkMoveTo}
			/>

			<div
				bind:this={gridElement}
				role="grid"
				aria-label={$_('messages.listLabel')}
				aria-rowcount={pageData.totalElements + 1}
				aria-colcount={6}
				class="flex-1 overflow-y-auto bg-background"
			>
				<div role="row" aria-rowindex={1} class="sr-only">
					<span role="columnheader" aria-colindex={1}>{$_('messages.columnHeaderSelect')}</span>
					<span role="columnheader" aria-colindex={2}>{$_('messages.columnHeaderStatus')}</span>
					<span role="columnheader" aria-colindex={3}>{$_('messages.columnHeaderSubject')}</span>
					<span role="columnheader" aria-colindex={4}
						>{showRecipients
							? $_('messages.columnHeaderRecipient')
							: $_('messages.columnHeaderSender')}</span
					>
					<span role="columnheader" aria-colindex={5}>{$_('messages.columnHeaderDate')}</span>
					<span role="columnheader" aria-colindex={6}>{$_('messages.columnHeaderActions')}</span>
				</div>
				{#each pageData.content as message, rowIndex (message.stableId)}
					{@const selected = $selectedMessage?.stableId === message.stableId}
					{@const multiSelected = $selectedMessageIdSet.has(message.stableId)}
					{@const statusLabel = messageStatusLabel(message, $_)}
					{@const formattedDate = formatMessageListDate(message.receivedAt, $appLocale ?? 'cs')}
					<div
						role="row"
						tabindex="-1"
						data-row-index={rowIndex}
						data-stable-id={message.stableId}
						aria-rowindex={pageData.page * pageData.size + rowIndex + 2}
						aria-selected={selected ? 'true' : 'false'}
						aria-current={selected ? 'page' : undefined}
						class={cn(
							'grid cursor-pointer grid-cols-[40px_auto_minmax(0,1fr)_auto_auto] grid-rows-[auto_auto] border-b border-border/80 transition-colors focus-within:relative focus-within:z-10',
							selected
								? 'bg-primary/8 text-accent-foreground shadow-[inset_3px_0_0_var(--primary)]'
								: 'hover:bg-muted/45',
							multiSelected && !selected && 'bg-primary/5',
							!message.seen && 'font-semibold'
						)}
						onclick={(e) => handleRowClick(e, message)}
						onkeydown={(e) => handleKeydown(e, message, rowIndex)}
					>
						<!-- svelte-ignore a11y_click_events_have_key_events -->
						<div
							role="gridcell"
							aria-colindex={COL_SELECT + 1}
							tabindex="-1"
							class="row-span-2 flex items-start justify-center py-3"
							onclick={(e) => e.stopPropagation()}
						>
							<!--
								The box stays 16px; the label around it is the pointer target and
								carries the 24px minimum (WCAG 2.5.8). Size is the only way to
								satisfy it here — the status cell sits flush against this one, so
								there is no spacing to fall back on. Centring inside the 24px label
								reproduces the old `mt-1` offset, so nothing moves.
							-->
							<label class="flex size-6 cursor-pointer items-center justify-center">
								<input
									id={`message-select-${message.stableId}`}
									type="checkbox"
									{...grid.cell(rowIndex, COL_SELECT)}
									class="size-4 accent-primary"
									checked={multiSelected}
									aria-label={selectionLabel(message)}
									onchange={(event) =>
										toggleMessageSelection(
											message.stableId,
											(event.currentTarget as HTMLInputElement).checked
										)}
								/>
							</label>
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_STATUS + 1}
							{...grid.cell(rowIndex, COL_STATUS)}
							aria-label={statusLabel}
							class="col-start-2 row-span-2 flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<MessageFlags {message} />
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_SUBJECT + 1}
							class="col-start-3 row-start-1 min-w-0 px-2 pt-3"
						>
							<!--
								A real link, not a clickable cell: browse mode never delivers
								Enter to the grid, and a link is the one thing every screen
								reader activates there. It carries the roving tabindex, so the
								arrow-key model is unchanged — same shape as the actions menu
								button in its own cell.
							-->
							<a
								href={rowHref(message)}
								{...grid.cell(rowIndex, COL_SUBJECT)}
								onclick={(event) => handleSubjectClick(event, message)}
								class={cn(
									'block truncate rounded-sm text-sm no-underline outline-none hover:underline focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
									!message.seen ? 'text-foreground' : 'text-muted-foreground'
								)}
							>
								{#if !message.seen}
									<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
								{/if}
								{message.subject || $_('messages.noSubject')}
							</a>
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_SENDER + 1}
							{...grid.cell(rowIndex, COL_SENDER)}
							class={cn(
								'col-start-3 row-start-2 truncate rounded-sm px-2 pb-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
								!message.seen ? 'text-foreground' : 'text-muted-foreground'
							)}
						>
							{showRecipients ? (message.recipientsTo ?? '') : message.sender}
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_DATE + 1}
							{...grid.cell(rowIndex, COL_DATE)}
							class="col-start-4 row-span-2 flex items-center rounded-sm px-3 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<time datetime={message.receivedAt}>{formattedDate}</time>
						</div>
						<!-- svelte-ignore a11y_click_events_have_key_events -->
						<div
							role="gridcell"
							aria-colindex={COL_ACTIONS + 1}
							tabindex="-1"
							class="col-start-5 row-span-2 flex items-center justify-center pr-2"
							onclick={(e) => e.stopPropagation()}
						>
							<MessageRowActionsMenu
								{message}
								col={COL_ACTIONS}
								focused={grid.isAt(rowIndex, COL_ACTIONS)}
								onCellFocus={() => grid.track(rowIndex, COL_ACTIONS)}
							/>
						</div>
					</div>
				{/each}
			</div>
		</div>

		<Pagination
			page={pageData.page}
			totalPages={pageData.totalPages}
			totalElements={pageData.totalElements}
			first={pageData.first}
			last={pageData.last}
			onFirst={() => navigateToPage(0)}
			onPrev={() => navigateToPage(pageData.page - 1)}
			onNext={() => navigateToPage(pageData.page + 1)}
			onLast={() => navigateToPage(pageData.totalPages - 1)}
			onJump={(target) => navigateToPage(target - 1)}
			landmarkLabel={$_('messages.paginationLandmark')}
		/>
	{/snippet}
</MailListState>
