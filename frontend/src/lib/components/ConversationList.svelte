<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { conversationsState, loadConversationsPage } from '$lib/stores/conversations.js';
	import { folders } from '$lib/stores/folders.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import Pagination from '$lib/components/Pagination.svelte';
	import { StateMessage } from '$lib/components/ui/state-message/index.js';
	import { Surface } from '$lib/components/ui/surface/index.js';
	import {
		computeNextCell,
		focusGridCell,
		ROW_NAV_PAGE_STEP
	} from '$lib/components/grid/rowNavigation.js';
	import { cn } from '$lib/utils.js';
	import { formatMessageListDate } from '$lib/formatters.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import { requestBodyFocus } from '$lib/mail/bodyFocus.js';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import type { ConversationSummaryResponse, FolderResponse } from '$lib/types.js';
	import { tick } from 'svelte';
	import { get } from 'svelte/store';

	// Collapsed conversation grid — one row per thread. Unlike MessageList it has
	// no select / actions columns and no multi-select: selecting a whole thread
	// (and thread-scoped bulk actions) is a follow-up increment, so grouped mode
	// is read/navigate only here. Same roving-cell grid a11y model otherwise.
	const COL_STATUS = 0;
	const COL_SUBJECT = 1;
	const COL_SENDER = 2;
	const COL_DATE = 3;
	const MAX_COL = COL_DATE;

	let gridElement = $state<HTMLDivElement | null>(null);
	let focusedRow = $state(0);
	let focusedCol = $state(COL_SUBJECT);

	const currentFolderName = $derived(
		$conversationsState.status === 'idle' ? '' : $conversationsState.context.folderName
	);
	const currentFolderRole = $derived(
		$folders.find((folder: FolderResponse) => folder.folderRef === currentFolderName)?.role
	);
	// In Drafts/Sent the sender is always the account owner, so show the recipient.
	const showRecipients = $derived(currentFolderRole === 'DRAFTS' || currentFolderRole === 'SENT');

	function messageHref(accountId: number, folderName: string, stableId: string): string {
		return resolve('/mail/[accountId]/[folderName]/[stableId]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName),
			stableId: encodeURIComponent(stableId)
		});
	}

	/** Opens a conversation by its representative (newest) message. */
	async function openConversation(row: ConversationSummaryResponse): Promise<void> {
		if ($conversationsState.status !== 'ready') return;
		const { accountId, folderName } = $conversationsState.context;
		const folder = $folders.find((f: FolderResponse) => f.folderRef === folderName);
		if (folder?.role === 'DRAFTS') {
			await goto(`${resolve('/compose')}?draft=${encodeURIComponent(row.latest.stableId)}`);
			return;
		}
		requestBodyFocus(row.latest.stableId);
		await goto(messageHref(accountId, folderName, row.latest.stableId));
	}

	function conversationLabel(row: ConversationSummaryResponse): string {
		const size = $_('messages.grouping.threadSize', { values: { count: row.messageCount } });
		if (row.unreadCount > 0) {
			return `${size}, ${$_('messages.grouping.threadUnread', { values: { count: row.unreadCount } })}`;
		}
		return size;
	}

	function handleKeydown(
		event: KeyboardEvent,
		row: ConversationSummaryResponse,
		rowIndex: number
	): void {
		if (event.key === 'Enter' || event.key === ' ') {
			event.preventDefault();
			void openConversation(row);
			return;
		}
		if ($conversationsState.status !== 'ready') return;
		const items = $conversationsState.page.content;
		const next = computeNextCell(event.key, {
			row: rowIndex,
			col: focusedCol,
			maxRow: items.length - 1,
			maxCol: MAX_COL,
			ctrl: event.ctrlKey,
			pageStep: ROW_NAV_PAGE_STEP
		});
		if (!next) return;
		event.preventDefault();
		setFocus(next.row, next.col);
	}

	function handleRowClick(event: MouseEvent, row: ConversationSummaryResponse): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		void openConversation(row);
	}

	function handleCellFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
	}

	function setFocus(rowIndex: number, col: number): void {
		focusedRow = rowIndex;
		focusedCol = col;
		void tick().then(() => focusGridCell(gridElement, rowIndex, col));
	}

	$effect(() => {
		if ($conversationsState.status !== 'ready') return;
		const items = $conversationsState.page.content;
		if (focusedRow >= items.length) {
			focusedRow = Math.max(0, items.length - 1);
		}
	});

	async function navigateToPage(target: number): Promise<void> {
		if ($conversationsState.status !== 'ready') return;
		const ctx = $conversationsState.context;
		const lastPage = Math.max(0, $conversationsState.page.totalPages - 1);
		const next = Math.min(Math.max(0, target), lastPage);
		if (next === ctx.page) return;
		await loadConversationsPage(ctx.accountId, ctx.folderName, next, ctx.size);
		const snapshot = get(conversationsState);
		if (snapshot.status === 'ready') announcePolite(messagesPageInfo($_, snapshot.page));
	}
</script>

{#if $conversationsState.status === 'idle' || $conversationsState.status === 'loading'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage padding="none" role="status">{$_('messages.loading')}</StateMessage>
		</Surface>
	</div>
{:else if $conversationsState.status === 'error'}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="danger" padding="sm" class="max-w-md">
			<StateMessage variant="error" padding="none" role="alert">
				{$_('messages.errorPrefix', {
					values: { message: toErrorMessage($conversationsState.error) }
				})}
			</StateMessage>
		</Surface>
	</div>
{:else if $conversationsState.page.content.length === 0}
	<div class="flex flex-1 items-center justify-center bg-background p-6">
		<Surface variant="subtle" padding="lg" class="max-w-sm text-center">
			<StateMessage padding="none" role="status">{$_('messages.empty')}</StateMessage>
		</Surface>
	</div>
{:else}
	{@const pageData = $conversationsState.page}
	<div class="flex min-h-0 flex-1 flex-col bg-background">
		<div
			bind:this={gridElement}
			role="grid"
			aria-label={$_('messages.grouping.listLabel')}
			aria-rowcount={pageData.totalElements + 1}
			aria-colcount={4}
			class="flex-1 overflow-y-auto bg-background"
		>
			<div role="row" aria-rowindex={1} class="sr-only">
				<span role="columnheader" aria-colindex={1}>{$_('messages.columnHeaderStatus')}</span>
				<span role="columnheader" aria-colindex={2}>{$_('messages.columnHeaderSubject')}</span>
				<span role="columnheader" aria-colindex={3}
					>{showRecipients
						? $_('messages.columnHeaderRecipient')
						: $_('messages.columnHeaderSender')}</span
				>
				<span role="columnheader" aria-colindex={4}>{$_('messages.columnHeaderDate')}</span>
			</div>
			{#each pageData.content as row, rowIndex (row.latest.stableId)}
				{@const message = row.latest}
				{@const unread = row.unreadCount > 0}
				{@const statusLabel = messageStatusLabel(message, $_)}
				{@const formattedDate = formatMessageListDate(message.receivedAt, $appLocale ?? 'cs')}
				<div
					role="row"
					tabindex="-1"
					data-row-index={rowIndex}
					data-stable-id={message.stableId}
					aria-rowindex={pageData.page * pageData.size + rowIndex + 2}
					class={cn(
						'grid cursor-pointer grid-cols-[auto_minmax(0,1fr)_auto] grid-rows-[auto_auto] border-b border-border/80 transition-colors hover:bg-muted/45 focus-within:relative focus-within:z-10',
						unread && 'font-semibold'
					)}
					onclick={(e) => handleRowClick(e, row)}
					onkeydown={(e) => handleKeydown(e, row, rowIndex)}
				>
					<div
						role="gridcell"
						aria-colindex={COL_STATUS + 1}
						data-cell-target
						data-col={COL_STATUS}
						tabindex={focusedRow === rowIndex && focusedCol === COL_STATUS ? 0 : -1}
						aria-label={statusLabel}
						onfocus={() => handleCellFocus(rowIndex, COL_STATUS)}
						class="row-span-2 flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
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
							'col-start-2 row-start-1 flex items-center gap-2 truncate rounded-sm px-2 pt-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
							unread ? 'text-foreground' : 'text-muted-foreground'
						)}
					>
						{#if unread}
							<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
						{/if}
						<span class="truncate">{message.subject || $_('messages.noSubject')}</span>
						{#if row.messageCount > 1}
							<span
								class="shrink-0 rounded-full bg-primary/12 px-1.5 py-0.5 text-caption font-semibold text-primary"
								aria-hidden="true"
							>
								{row.messageCount}
							</span>
							<span class="sr-only">{conversationLabel(row)}.</span>
						{/if}
					</div>
					<div
						role="gridcell"
						aria-colindex={COL_SENDER + 1}
						data-cell-target
						data-col={COL_SENDER}
						tabindex={focusedRow === rowIndex && focusedCol === COL_SENDER ? 0 : -1}
						onfocus={() => handleCellFocus(rowIndex, COL_SENDER)}
						class={cn(
							'col-start-2 row-start-2 truncate rounded-sm px-2 pb-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
							unread ? 'text-foreground' : 'text-muted-foreground'
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
						class="col-start-3 row-span-2 flex items-center rounded-sm px-3 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
					>
						<time datetime={message.receivedAt}>{formattedDate}</time>
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
