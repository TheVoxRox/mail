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
	import { createBulkAnnouncer } from '$lib/components/grid/bulkAnnouncer.js';
	import {
		createLatestSelection,
		isRowBackgroundClick
	} from '$lib/components/grid/rowActivation.js';
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
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import MailActionsCell from '$lib/components/mail-list/MailActionsCell.svelte';
	import MailDateCell from '$lib/components/mail-list/MailDateCell.svelte';
	import MailRow from '$lib/components/mail-list/MailRow.svelte';
	import MailSenderCell from '$lib/components/mail-list/MailSenderCell.svelte';
	import MailStatusCell from '$lib/components/mail-list/MailStatusCell.svelte';
	import MailSubjectCell from '$lib/components/mail-list/MailSubjectCell.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
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
	const latestSelection = createLatestSelection();

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

	// A click on the row opens the message; the checkbox alone selects. Which
	// clicks count as the row's own, and why that distinction is load-bearing for
	// a screen reader, is in grid/rowActivation.ts.
	function handleRowClick(event: MouseEvent, message: MailSummaryResponse): void {
		if (isRowBackgroundClick(event)) openDeliberately(message);
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
		// Retire a refocus an in-flight selectAndFocus queued, so the body wins.
		latestSelection.retire();
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

	function selectAndFocus(rowIndex: number, col: number, message: MailSummaryResponse): void {
		grid.moveTo(rowIndex, col);
		const isLatest = latestSelection.begin();
		void handleSelect(message).finally(() => {
			if (isLatest()) grid.moveTo(rowIndex, col);
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
	 * The action buttons render only once something is selected and the focus
	 * stays on the row, so without this a screen-reader user ticks a checkbox
	 * and has no signal that Mark read / Move / Delete just appeared.
	 */
	const announceBulkActions = createBulkAnnouncer(() =>
		announcePolite($_('messages.bulkActionsAvailable'))
	);
	$effect(() => {
		announceBulkActions(selectedCount > 0);
	});

	async function navigateToPage(target: number) {
		if ($messagesState.status !== 'ready') return;
		const ctx = $messagesState.context;
		await loadPage(ctx.accountId, ctx.folderName, target, ctx.size);
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

			<!--
				The column widths live here, on the list, not on each row — the same
				arrangement the grouped list uses (see ConversationList) and for the same
				reason. A track sized to one row's own content agrees with the row above
				it only by coincidence: the status cell is `auto` around `MessageFlags`,
				whose three icons are each conditional, so a row carrying one measures
				30px there and a row carrying none 16px — and the subject column behind
				it starts 14px further right. Measured in the browser, not reasoned
				about.

				What kept it out of sight is the `minmax(0,1fr)` on the subject: it
				absorbs the difference, so the date and actions columns stay put and only
				the left edge of the subject moves. The date column has the same fault
				and shows it whenever the formats differ: with the three shapes
				`formatMessageListDate` can return substituted into one list — a clock,
				a weekday name and a full date — that column measured 1167 / 1154.7 /
				1120.3px from the left. Those three came from substituting the strings
				by hand, because the e2e fixture dates all land on one day and render
				one shape; the 30/16px status figures above are what it renders as
				shipped. Rows are `subgrid`, so every one of them now resolves against
				this one set of tracks and the columns line up by construction rather
				than by coincidence of content width.

				Five tracks, not the six `aria-colcount` reports: subject and sender
				share the third one across the two rows. The ARIA count describes the
				reading order, not the layout, and writing it here would not break the
				grid visibly — it would just shift every cell one column right.

				`content-start` because the implicit rows are `auto`: without it a short
				list stretches its rows to fill the viewport.
			-->
			<div
				bind:this={gridElement}
				role="grid"
				aria-label={$_('messages.listLabel')}
				aria-rowcount={pageData.totalElements + 1}
				aria-colcount={6}
				class="grid flex-1 grid-cols-[2.5rem_auto_minmax(0,1fr)_auto_auto] content-start overflow-y-auto bg-background"
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
					{@const unread = !message.seen}
					{@const multiSelected = $selectedMessageIdSet.has(message.stableId)}
					<MailRow
						{rowIndex}
						stableId={message.stableId}
						ariaRowIndex={pageData.page * pageData.size + rowIndex + 2}
						{unread}
						current={selected}
						ticked={multiSelected}
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
									class={nativeControlClass}
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
						<MailStatusCell
							{message}
							colIndex={COL_STATUS + 1}
							cell={grid.cell(rowIndex, COL_STATUS)}
							label={messageStatusLabel(message, $_)}
							placement="col-start-2 row-span-2"
						/>
						<MailSubjectCell
							subject={message.subject}
							{unread}
							href={rowHref(message)}
							onclick={(event) => handleSubjectClick(event, message)}
							colIndex={COL_SUBJECT + 1}
							cell={grid.cell(rowIndex, COL_SUBJECT)}
							placement="col-start-3 row-start-1"
						/>
						<MailSenderCell
							text={showRecipients ? (message.recipientsTo ?? '') : message.sender}
							{unread}
							colIndex={COL_SENDER + 1}
							cell={grid.cell(rowIndex, COL_SENDER)}
							placement="col-start-3 row-start-2"
						/>
						<MailDateCell
							receivedAt={message.receivedAt}
							formatted={formatMessageListDate(message.receivedAt, $appLocale ?? 'cs')}
							colIndex={COL_DATE + 1}
							cell={grid.cell(rowIndex, COL_DATE)}
							placement="col-start-4 row-span-2"
						/>
						<MailActionsCell colIndex={COL_ACTIONS + 1} placement="col-start-5 row-span-2">
							<MessageRowActionsMenu
								{message}
								col={COL_ACTIONS}
								focused={grid.isAt(rowIndex, COL_ACTIONS)}
								onCellFocus={() => grid.track(rowIndex, COL_ACTIONS)}
							/>
						</MailActionsCell>
					</MailRow>
				{/each}
			</div>
		</div>

		<Pagination
			page={pageData.page}
			totalPages={pageData.totalPages}
			totalElements={pageData.totalElements}
			first={pageData.first}
			last={pageData.last}
			onNavigate={navigateToPage}
			landmarkLabel={$_('messages.paginationLandmark')}
		/>
	{/snippet}
</MailListState>
