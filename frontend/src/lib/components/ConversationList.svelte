<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { conversationsState, loadConversationsPage } from '$lib/stores/conversations.js';
	import { folders } from '$lib/stores/folders.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { getThread } from '$lib/api/mailRead.js';
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
	import type {
		ConversationSummaryResponse,
		FolderResponse,
		MailSummaryResponse
	} from '$lib/types.js';
	import { tick } from 'svelte';
	import { get } from 'svelte/store';
	import { SvelteMap, SvelteSet } from 'svelte/reactivity';

	// Conversation treegrid — one top-level row per thread (its newest message +
	// count badge). Expandable threads reveal their other folder-scoped members as
	// level-2 child rows; singletons are leaves. Same roving-cell model as
	// MessageList (Enter/click opens), plus treegrid expand/collapse: ArrowRight /
	// ArrowLeft on the subject cell toggle a parent, ArrowLeft on a child jumps to
	// its parent. No select / actions columns and no multi-select yet — grouped
	// mode stays read/navigate only.
	const COL_STATUS = 0;
	const COL_SUBJECT = 1;
	const COL_SENDER = 2;
	const COL_DATE = 3;
	const MAX_COL = COL_DATE;

	let gridElement = $state<HTMLDivElement | null>(null);
	let focusedRow = $state(0);
	let focusedCol = $state(COL_SUBJECT);

	// Expansion is per-view: expanded thread ids, their loaded folder-scoped
	// members (representative excluded — it is the parent row), and in-flight
	// fetches. Members are added to `expanded` only after they load, so an
	// expanded id always has a `members` entry.
	const expanded = new SvelteSet<string>();
	const members = new SvelteMap<string, MailSummaryResponse[]>();
	const loadingThreads = new SvelteSet<string>();
	let viewKey = '';

	const currentFolderName = $derived(
		$conversationsState.status === 'idle' ? '' : $conversationsState.context.folderName
	);
	const currentFolderRole = $derived(
		$folders.find((folder: FolderResponse) => folder.folderRef === currentFolderName)?.role
	);
	// In Drafts/Sent the sender is always the account owner, so show the recipient.
	const showRecipients = $derived(currentFolderRole === 'DRAFTS' || currentFolderRole === 'SENT');

	type VisibleRow =
		| { kind: 'conversation'; conversation: ConversationSummaryResponse }
		| { kind: 'member'; threadId: string; message: MailSummaryResponse };

	// Flattened parent + expanded-children list the roving grid navigates over.
	const visibleRows = $derived.by<VisibleRow[]>(() => {
		if ($conversationsState.status !== 'ready') return [];
		const rows: VisibleRow[] = [];
		for (const conversation of $conversationsState.page.content) {
			rows.push({ kind: 'conversation', conversation });
			const id = conversation.threadId;
			if (id && expanded.has(id)) {
				for (const message of members.get(id) ?? []) {
					rows.push({ kind: 'member', threadId: id, message });
				}
			}
		}
		return rows;
	});

	function isExpandable(conversation: ConversationSummaryResponse): boolean {
		return conversation.threadId != null && conversation.messageCount > 1;
	}

	function messageHref(accountId: number, folderName: string, stableId: string): string {
		return resolve('/mail/[accountId]/[folderName]/[stableId]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName),
			stableId: encodeURIComponent(stableId)
		});
	}

	/** Opens a message in the current folder (draft folders open the composer). */
	async function openMessage(stableId: string): Promise<void> {
		if ($conversationsState.status !== 'ready') return;
		const { accountId, folderName } = $conversationsState.context;
		const folder = $folders.find((f: FolderResponse) => f.folderRef === folderName);
		if (folder?.role === 'DRAFTS') {
			await goto(`${resolve('/compose')}?draft=${encodeURIComponent(stableId)}`);
			return;
		}
		requestBodyFocus(stableId);
		await goto(messageHref(accountId, folderName, stableId));
	}

	/** Opens a conversation by its representative (newest) message. */
	function openConversation(row: ConversationSummaryResponse): Promise<void> {
		return openMessage(row.latest.stableId);
	}

	function conversationLabel(row: ConversationSummaryResponse): string {
		const size = $_('messages.grouping.threadSize', { values: { count: row.messageCount } });
		if (row.unreadCount > 0) {
			return `${size}, ${$_('messages.grouping.threadUnread', { values: { count: row.unreadCount } })}`;
		}
		return size;
	}

	/** Fetches a thread's folder-scoped members (representative excluded) once. */
	async function loadMembers(conversation: ConversationSummaryResponse): Promise<boolean> {
		const id = conversation.threadId;
		if (id == null || $conversationsState.status !== 'ready') return false;
		if (members.has(id)) return true;
		const { accountId, folderName } = $conversationsState.context;
		loadingThreads.add(id);
		try {
			const thread = await getThread(accountId, id);
			// Folder-scoped: keep only members in the folder in view and drop the
			// representative (already shown as the parent). Ascending threadPosition.
			const folderMembers = thread.messages.filter(
				(message) =>
					message.folderName === folderName && message.stableId !== conversation.latest.stableId
			);
			members.set(id, folderMembers);
			return true;
		} catch (error) {
			announcePolite(`${$_('messages.grouping.loadError')} ${toErrorMessage(error)}`);
			return false;
		} finally {
			loadingThreads.delete(id);
		}
	}

	/**
	 * Toggles a thread's expansion. Expansion is committed only after members
	 * load, so a failed fetch leaves the row collapsed with an announced error.
	 * When `focusParent` is set (keyboard toggles) focus returns to the parent
	 * row afterwards.
	 */
	async function toggleExpand(
		conversation: ConversationSummaryResponse,
		focusAfter = false
	): Promise<void> {
		const id = conversation.threadId;
		if (id == null || !isExpandable(conversation)) return;
		if (expanded.has(id)) {
			expanded.delete(id);
			announcePolite($_('messages.grouping.collapsed'));
		} else {
			const ok = await loadMembers(conversation);
			if (!ok) return;
			expanded.add(id);
			announcePolite(
				$_('messages.grouping.revealed', { values: { count: members.get(id)?.length ?? 0 } })
			);
		}
		if (focusAfter) {
			await tick();
			const parentIndex = visibleRows.findIndex(
				(row) => row.kind === 'conversation' && row.conversation.threadId === id
			);
			if (parentIndex >= 0) setFocus(parentIndex, COL_SUBJECT);
		}
	}

	/** Moves focus from a child row up to its parent conversation row. */
	function focusParentRow(rowIndex: number): void {
		for (let index = rowIndex - 1; index >= 0; index -= 1) {
			if (visibleRows[index].kind === 'conversation') {
				setFocus(index, COL_SUBJECT);
				return;
			}
		}
	}

	function rowStableId(row: VisibleRow): string {
		return row.kind === 'conversation' ? row.conversation.latest.stableId : row.message.stableId;
	}

	function handleKeydown(event: KeyboardEvent, row: VisibleRow, rowIndex: number): void {
		if (event.key === 'Enter' || event.key === ' ') {
			event.preventDefault();
			if (row.kind === 'conversation') void openConversation(row.conversation);
			else void openMessage(row.message.stableId);
			return;
		}
		if ($conversationsState.status !== 'ready') return;

		// Treegrid expand/collapse lives on the subject cell (where the caret sits).
		if (focusedCol === COL_SUBJECT) {
			if (row.kind === 'conversation' && isExpandable(row.conversation)) {
				const id = row.conversation.threadId as string;
				if (event.key === 'ArrowRight' && !expanded.has(id)) {
					event.preventDefault();
					void toggleExpand(row.conversation, true);
					return;
				}
				if (event.key === 'ArrowLeft' && expanded.has(id)) {
					event.preventDefault();
					void toggleExpand(row.conversation, true);
					return;
				}
			}
			if (row.kind === 'member' && event.key === 'ArrowLeft') {
				event.preventDefault();
				focusParentRow(rowIndex);
				return;
			}
		}

		const next = computeNextCell(event.key, {
			row: rowIndex,
			col: focusedCol,
			maxRow: visibleRows.length - 1,
			maxCol: MAX_COL,
			ctrl: event.ctrlKey,
			pageStep: ROW_NAV_PAGE_STEP
		});
		if (!next) return;
		event.preventDefault();
		setFocus(next.row, next.col);
	}

	function handleRowClick(event: MouseEvent, row: VisibleRow): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		if (target?.closest('[data-expand-toggle]')) {
			if (row.kind === 'conversation') void toggleExpand(row.conversation);
			return;
		}
		if (row.kind === 'conversation') void openConversation(row.conversation);
		else void openMessage(row.message.stableId);
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

	// Reset expansion when the folder or page changes — expansion is a per-view
	// state and the folder-scoped member filter must not leak across folders.
	$effect(() => {
		if ($conversationsState.status !== 'ready') return;
		const ctx = $conversationsState.context;
		const key = `${ctx.accountId}:${ctx.folderName}:${ctx.page}`;
		if (key !== viewKey) {
			viewKey = key;
			expanded.clear();
			members.clear();
			loadingThreads.clear();
		}
	});

	$effect(() => {
		if (focusedRow >= visibleRows.length) {
			focusedRow = Math.max(0, visibleRows.length - 1);
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
			role="treegrid"
			aria-label={$_('messages.grouping.listLabel')}
			aria-rowcount={visibleRows.length + 1}
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
			{#each visibleRows as row, rowIndex (rowStableId(row))}
				{@const isConversation = row.kind === 'conversation'}
				{@const message = isConversation ? row.conversation.latest : row.message}
				{@const expandable = isConversation && isExpandable(row.conversation)}
				{@const threadId = isConversation ? row.conversation.threadId : row.threadId}
				{@const isOpen = threadId != null && expanded.has(threadId)}
				{@const isLoading = threadId != null && loadingThreads.has(threadId)}
				{@const unread = isConversation ? row.conversation.unreadCount > 0 : !row.message.seen}
				{@const statusLabel = messageStatusLabel(message, $_)}
				{@const formattedDate = formatMessageListDate(message.receivedAt, $appLocale ?? 'cs')}
				<div
					role="row"
					tabindex="-1"
					data-row-index={rowIndex}
					data-stable-id={message.stableId}
					aria-level={isConversation ? 1 : 2}
					aria-rowindex={rowIndex + 2}
					aria-expanded={expandable ? (isOpen ? 'true' : 'false') : undefined}
					aria-busy={isLoading ? 'true' : undefined}
					class={cn(
						'grid cursor-pointer grid-cols-[auto_minmax(0,1fr)_auto] grid-rows-[auto_auto] border-b border-border/80 transition-colors hover:bg-muted/45 focus-within:relative focus-within:z-10',
						!isConversation && 'bg-muted/20 pl-5',
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
						{#if isConversation}
							<span
								class="flex w-4 shrink-0 items-center justify-center text-muted-foreground"
								data-expand-toggle={expandable ? '' : undefined}
								title={expandable
									? isLoading
										? $_('messages.grouping.loading')
										: isOpen
											? $_('messages.grouping.collapse')
											: $_('messages.grouping.expand')
									: undefined}
								aria-hidden="true"
							>
								{#if expandable}
									<svg
										viewBox="0 0 16 16"
										class={cn('h-3.5 w-3.5 transition-transform', isOpen && 'rotate-90')}
									>
										<path
											d="M6 4l4 4-4 4"
											fill="none"
											stroke="currentColor"
											stroke-width="2"
											stroke-linecap="round"
											stroke-linejoin="round"
										/>
									</svg>
								{/if}
							</span>
						{/if}
						{#if unread}
							<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
						{/if}
						<span class="truncate">{message.subject || $_('messages.noSubject')}</span>
						{#if isConversation && row.conversation.messageCount > 1}
							<span
								class="shrink-0 rounded-full bg-primary/12 px-1.5 py-0.5 text-caption font-semibold text-primary"
								aria-hidden="true"
							>
								{row.conversation.messageCount}
							</span>
							<span class="sr-only">{conversationLabel(row.conversation)}.</span>
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
