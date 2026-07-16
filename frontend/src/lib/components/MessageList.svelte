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
		restoreListFocusStableId,
		selectedMessage
	} from '$lib/stores/selectedMessage.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import Icon from '$lib/components/Icon.svelte';
	import Pagination from '$lib/components/Pagination.svelte';
	import { Button, buttonVariants } from '$lib/components/ui/button/index.js';
	import { DropdownMenu } from 'bits-ui';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import {
		computeNextCell,
		focusGridCell,
		ROW_NAV_PAGE_STEP
	} from '$lib/components/grid/rowNavigation.js';
	import { cn } from '$lib/utils.js';
	import { formatMessageListDate } from '$lib/formatters.js';
	import { folderLabel } from '$lib/mail/folderLabel.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import type { FolderResponse, MailSummaryResponse } from '$lib/types.js';
	import { deleteMessages, markMessagesSeen, moveMessages } from '$lib/mail/mailbox.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import {
		EFFECTIVE_READING_PANE_CONTEXT_KEY,
		type EffectiveReadingPaneContext
	} from '$lib/mail/readingPaneContext.js';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { getContext, tick } from 'svelte';
	import { get } from 'svelte/store';

	const COL_SELECT = 0;
	const COL_STATUS = 1;
	const COL_SUBJECT = 2;
	const COL_SENDER = 3;
	const COL_DATE = 4;
	const COL_ACTIONS = 5;
	const MAX_COL = COL_ACTIONS;
	const PAGE_KEY_STEP = ROW_NAV_PAGE_STEP;

	let gridElement = $state<HTMLDivElement | null>(null);
	let moveMenuOpen = $state(false);
	let seenMenuOpen = $state(false);
	let bulkAction = $state<'read' | 'unread' | 'delete' | 'move' | null>(null);
	let bulkError = $state<string | null>(null);
	let focusedRow = $state(0);
	let focusedCol = $state(COL_SUBJECT);

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
	let selectAllInput = $state<HTMLInputElement | null>(null);

	$effect(() => {
		if (selectAllInput) selectAllInput.indeterminate = someSelectedOnPage;
	});
	const currentFolderName = $derived(
		$messagesState.status === 'idle' ? '' : $messagesState.context.folderName
	);
	const moveTargets = $derived(
		$folders.filter((folder: FolderResponse) => folder.folderRef !== currentFolderName)
	);
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

	async function handleSelect(message: MailSummaryResponse): Promise<void> {
		if ($messagesState.status !== 'ready') return;
		const { accountId, folderName } = $messagesState.context;
		// Drafts open in the composer (with a Send button), not the read-only viewer.
		const folder = $folders.find((f: FolderResponse) => f.folderRef === folderName);
		if (folder?.role === 'DRAFTS') {
			await goto(`${resolve('/compose')}?draft=${encodeURIComponent(message.stableId)}`);
			return;
		}
		await goto(messageHref(accountId, folderName, message.stableId));
	}

	function handleKeydown(event: KeyboardEvent, message: MailSummaryResponse, rowIndex: number) {
		if (event.key === 'Enter' || event.key === ' ') {
			if (focusedCol === COL_SELECT || focusedCol === COL_ACTIONS) return;
			event.preventDefault();
			void handleSelect(message);
			return;
		}
		// Plain Delete only — matches the open-message handler in globalShortcuts.ts
		// so a modifier combo (Shift/Ctrl+Delete) behaves the same whether focus is
		// on a list row or in the reading pane. Modified Delete falls through to the
		// navigation branch, where computeNextCell returns null for 'Delete' → no-op.
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
		const next = computeNextCell(event.key, {
			row: rowIndex,
			col: focusedCol,
			maxRow: items.length - 1,
			maxCol: MAX_COL,
			ctrl: event.ctrlKey,
			pageStep: PAGE_KEY_STEP
		});
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
				setFocus(next.row, next.col);
			} else {
				selectAndFocus(next.row, next.col, items[next.row]);
			}
		} else {
			setFocus(next.row, next.col);
		}
	}

	function handleRowClick(event: MouseEvent, message: MailSummaryResponse): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		void handleSelect(message);
	}

	function handleCellFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
	}

	function handleSelectAll(event: Event): void {
		const checked = (event.currentTarget as HTMLInputElement).checked;
		setMessageSelection(checked ? pageStableIds : []);
	}

	function selectionLabel(message: MailSummaryResponse): string {
		return $_('messages.selectMessage', {
			values: { subject: message.subject || $_('messages.noSubject') }
		});
	}

	async function runBulkAction(
		action: 'read' | 'unread' | 'delete' | 'move',
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

	function focusCell(rowIndex: number, col: number): void {
		void tick().then(() => focusGridCell(gridElement, rowIndex, col));
	}

	function setFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
		focusCell(rowIndex, col);
	}

	// Bumped on every selection. handleSelect() navigates, and SvelteKit cancels
	// an in-flight navigation when a newer one starts (rapid Arrow/Page keys). The
	// superseded navigation's promise still settles and would re-focus its now-stale
	// row last, bouncing focus backwards — so a `.finally` only re-focuses while it
	// is still the latest selection.
	let selectToken = 0;
	function selectAndFocus(rowIndex: number, col: number, message: MailSummaryResponse): void {
		focusedRow = rowIndex;
		focusedCol = col;
		focusCell(rowIndex, col);
		const token = ++selectToken;
		void handleSelect(message).finally(() => {
			if (token === selectToken) focusCell(rowIndex, col);
		});
	}

	$effect(() => {
		const stableId = $restoreListFocusStableId;
		if (!stableId || !gridElement) return;
		if ($messagesState.status !== 'ready') return;

		const idx = $messagesState.page.content.findIndex((m) => m.stableId === stableId);
		if (idx < 0) return;

		const frame = requestAnimationFrame(() => {
			/*
			 * Never park restored focus on the actions column: after a row-menu
			 * delete the restore target is a *different* message's menu trigger,
			 * and a screen reader would announce that button instead of the row
			 * the focus moved on to. The subject cell is the row's reading anchor.
			 */
			setFocus(idx, focusedCol === COL_ACTIONS ? COL_SUBJECT : focusedCol);
			clearListFocusRestore();
		});

		return () => cancelAnimationFrame(frame);
	});

	$effect(() => {
		if ($messagesState.status !== 'ready') return;
		const items = $messagesState.page.content;
		if (focusedRow >= items.length) {
			focusedRow = Math.max(0, items.length - 1);
		}
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

{#if $messagesState.status === 'idle' || $messagesState.status === 'loading'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage padding="none" role="status">{$_('messages.loading')}</StateMessage>
		</Surface>
	</div>
{:else if $messagesState.status === 'error'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="danger" padding="sm" class="max-w-md">
			<StateMessage variant="error" padding="none" role="alert">
				{$_('messages.errorPrefix', {
					values: { message: toErrorMessage($messagesState.error) }
				})}
			</StateMessage>
		</Surface>
	</div>
{:else if $messagesState.page.content.length === 0}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage padding="none" role="status">{$_('messages.empty')}</StateMessage>
		</Surface>
	</div>
{:else}
	{@const pageData = $messagesState.page}
	<div class="flex min-h-0 flex-1 flex-col bg-background">
		<div
			role="toolbar"
			aria-label={$_('messages.bulkToolbarLabel')}
			class="flex min-h-11 flex-wrap items-center gap-2 border-b border-border/80 bg-muted/20 px-3 py-2"
		>
			<label class="flex items-center gap-2 text-xs font-medium text-muted-foreground">
				<input
					bind:this={selectAllInput}
					type="checkbox"
					class="size-4 accent-primary"
					checked={allPageMessagesSelected}
					aria-checked={someSelectedOnPage ? 'mixed' : allPageMessagesSelected ? 'true' : 'false'}
					onchange={handleSelectAll}
				/>
				<span>{$_('messages.selectAll')}</span>
			</label>

			{#if selectedCount > 0}
				<span class="text-xs text-muted-foreground" role="status">
					{$_('messages.selectedCount', { values: { count: selectedCount } })}
				</span>
				<Button
					type="button"
					variant="ghost"
					size="xs"
					onclick={() => clearMessageSelection()}
					disabled={bulkAction !== null}
				>
					{$_('messages.clearSelection')}
				</Button>
				<Button
					type="button"
					variant="destructive"
					size="xs"
					onclick={handleBulkDelete}
					disabled={bulkAction !== null}
				>
					<Icon name="trash" />
					<span
						>{bulkAction === 'delete'
							? $_('messages.bulkDeleting')
							: $_('messages.bulkDelete')}</span
					>
				</Button>
				<DropdownMenu.Root bind:open={seenMenuOpen}>
					<DropdownMenu.Trigger
						class={cn(
							buttonVariants({ variant: 'outline', size: 'xs' }),
							'data-[state=open]:bg-muted'
						)}
						disabled={bulkAction !== null}
					>
						<Icon name="envelope" />
						<span
							>{bulkAction === 'read' || bulkAction === 'unread'
								? $_('messages.bulkMarkingRead')
								: $_('messages.bulkSeenMenu')}</span
						>
						<Icon name="chevron-down" size={16} />
					</DropdownMenu.Trigger>
					<DropdownMenu.Portal>
						<DropdownMenu.Content
							align="start"
							sideOffset={4}
							loop
							class="z-10 min-w-44 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
						>
							<DropdownMenu.Item
								class="flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-none data-[highlighted]:bg-muted data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground"
								onSelect={() => handleBulkMarkSeen(true)}
							>
								{$_('messages.bulkMarkRead')}
							</DropdownMenu.Item>
							<DropdownMenu.Item
								class="flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-none data-[highlighted]:bg-muted data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground"
								onSelect={() => handleBulkMarkSeen(false)}
							>
								{$_('messages.bulkMarkUnread')}
							</DropdownMenu.Item>
						</DropdownMenu.Content>
					</DropdownMenu.Portal>
				</DropdownMenu.Root>
				<DropdownMenu.Root bind:open={moveMenuOpen}>
					<DropdownMenu.Trigger
						class={cn(
							buttonVariants({ variant: 'outline', size: 'xs' }),
							'data-[state=open]:bg-muted'
						)}
						disabled={bulkAction !== null || moveTargets.length === 0}
					>
						<Icon name="folder" />
						<span>{bulkAction === 'move' ? $_('toolbar.moving') : $_('messages.bulkMove')}</span>
						<Icon name="chevron-down" size={16} />
					</DropdownMenu.Trigger>
					<DropdownMenu.Portal>
						<DropdownMenu.Content
							align="start"
							sideOffset={4}
							loop
							class="z-10 max-h-64 min-w-44 overflow-y-auto rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-lg"
						>
							{#each moveTargets as folder (folder.folderRef)}
								{@const label = folderLabel(folder, $_)}
								<DropdownMenu.Item
									class="flex w-full cursor-pointer rounded-md px-3 py-2 text-left text-sm outline-none data-[highlighted]:bg-muted data-[disabled]:cursor-not-allowed data-[disabled]:text-muted-foreground"
									title={label}
									onSelect={() => handleBulkMoveTo(folder.folderRef)}
								>
									<span class="truncate">{label}</span>
								</DropdownMenu.Item>
							{/each}
						</DropdownMenu.Content>
					</DropdownMenu.Portal>
				</DropdownMenu.Root>
			{/if}
			{#if bulkError}
				<p class="basis-full text-xs text-destructive" role="alert">{bulkError}</p>
			{/if}
		</div>

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
						<input
							id={`message-select-${message.stableId}`}
							type="checkbox"
							data-cell-target
							data-col={COL_SELECT}
							class="mt-1 size-4 accent-primary"
							checked={multiSelected}
							tabindex={focusedRow === rowIndex && focusedCol === COL_SELECT ? 0 : -1}
							aria-label={selectionLabel(message)}
							onfocus={() => handleCellFocus(rowIndex, COL_SELECT)}
							onchange={(event) =>
								toggleMessageSelection(
									message.stableId,
									(event.currentTarget as HTMLInputElement).checked
								)}
						/>
					</div>
					<div
						role="gridcell"
						aria-colindex={COL_STATUS + 1}
						data-cell-target
						data-col={COL_STATUS}
						tabindex={focusedRow === rowIndex && focusedCol === COL_STATUS ? 0 : -1}
						aria-label={statusLabel}
						onfocus={() => handleCellFocus(rowIndex, COL_STATUS)}
						class="col-start-2 row-span-2 flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
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
							'col-start-3 row-start-1 truncate rounded-sm px-2 pt-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
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
							'col-start-3 row-start-2 truncate rounded-sm px-2 pb-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
							!message.seen ? 'text-foreground' : 'text-muted-foreground'
						)}
					>
						{showRecipients ? (message.recipientsTo ?? '') : message.sender}
					</div>
					<div
						role="gridcell"
						aria-colindex={COL_DATE + 1}
						data-cell-target
						data-col={COL_DATE}
						tabindex={focusedRow === rowIndex && focusedCol === COL_DATE ? 0 : -1}
						onfocus={() => handleCellFocus(rowIndex, COL_DATE)}
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
							focused={focusedRow === rowIndex && focusedCol === COL_ACTIONS}
							onCellFocus={() => handleCellFocus(rowIndex, COL_ACTIONS)}
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
{/if}
