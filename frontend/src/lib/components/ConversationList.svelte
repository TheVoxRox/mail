<script lang="ts">
	import { goto } from '$app/navigation';
	import { resolve } from '$app/paths';
	import { conversationsState, loadConversationsPage } from '$lib/stores/conversations.js';
	import { folders } from '$lib/stores/folders.js';
	import { _, appLocale } from '$lib/i18n/index.js';
	import { toErrorMessage } from '$lib/api/errors.js';
	import { getThread } from '$lib/api/mailRead.js';
	import Pagination from '$lib/components/Pagination.svelte';
	import MailBulkToolbar, {
		type BulkAction
	} from '$lib/components/mail-list/MailBulkToolbar.svelte';
	import MailListState from '$lib/components/mail-list/MailListState.svelte';
	import { createRovingGrid } from '$lib/components/grid/rovingGrid.svelte.js';

	import { cn } from '$lib/utils.js';
	import { formatMessageListDate, formatThreadMemberDate } from '$lib/formatters.js';
	import { folderLabelByRef } from '$lib/mail/folderLabel.js';
	import { moveTargetsFor } from '$lib/mail/moveTargets.js';
	import { messageStatusLabel } from '$lib/mail/messageStatus.js';
	import { messagesPageInfo } from '$lib/mail/pageInfoAnnouncement.js';
	import { requestBodyFocus, suppressBodyFocus } from '$lib/mail/bodyFocus.js';
	import {
		EFFECTIVE_READING_PANE_CONTEXT_KEY,
		type EffectiveReadingPaneContext
	} from '$lib/mail/readingPaneContext.js';
	import {
		announceBulkActionsAvailable,
		deleteConversationMembers,
		flagConversationMembers,
		markConversationMembersSeen,
		moveConversationMembers,
		type ConversationBulkContext
	} from '$lib/mail/conversationBulk.js';
	import { forwardMessage, replyToMessage } from '$lib/mail/actions.js';
	import type { RowActions } from '$lib/mail/rowActions.js';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import type {
		ConversationSummaryResponse,
		FolderResponse,
		MailSummaryResponse
	} from '$lib/types.js';
	import { getContext, tick } from 'svelte';
	import { get } from 'svelte/store';
	import { SvelteMap, SvelteSet } from 'svelte/reactivity';

	// Conversation treegrid — one top-level row per thread (its newest message in
	// the folder + count badge). An expanded thread lists every message it holds
	// as a level-2 child row, the newest one included: the parent row shows that
	// message but stands for the conversation, so leaving it out of the children
	// made it the one message that could not be ticked by itself. Singletons are
	// leaves. Cross-folder scope: in a regular folder the children span the whole
	// thread minus trash, junk, drafts and sent (a copy that lives elsewhere — an
	// archived reply inside an inbox thread — shows inline, tagged with its
	// folder), while Trash, Junk, Drafts and Sent views stay folder-scoped: a
	// received conversation is about the mail that arrived, and the user's own
	// replies are a folder of their own. That scope is decided by the server and
	// asked for by folderRef — the thread endpoint hands back exactly the messages
	// the row's badge counted, so the child rows always match the badge instead of
	// depending on this component re-deriving the same rule from IMAP roles. Same
	// roving-cell model as MessageList (Enter/click opens), plus treegrid
	// expand/collapse and a select column driving conversation bulk actions:
	// selecting a conversation targets its members in the folder in view only
	// (resolved at action time) — a delete from the inbox must never reach the
	// archived copies. Expanded member rows carry a checkbox of their own, so a
	// single message of a thread can be acted on; a partly ticked thread announces
	// itself as `mixed`. Rows open under their own folder, not the folder in view.
	// The expand toggle owns a column of its own, like the flat list's row-actions
	// menu: a screen reader has to be able to reach and operate it as a button.
	// aria-expanded on the row plus ArrowRight/ArrowLeft is the WAI-ARIA treegrid
	// contract, but it is not reachable in a screen reader's browse mode, which
	// swallows unmodified arrow keys for its own navigation -- the caret used to be
	// an aria-hidden span, so the only way to expand a thread was the mouse.
	const COL_SELECT = 0;
	const COL_EXPAND = 1;
	const COL_STATUS = 2;
	const COL_SUBJECT = 3;
	const COL_SENDER = 4;
	const COL_DATE = 5;
	const COL_ACTIONS = 6;
	const MAX_COL = COL_ACTIONS;

	let gridElement = $state<HTMLDivElement | null>(null);
	let emptyStateElement = $state<HTMLParagraphElement | null>(null);
	const grid = createRovingGrid({
		element: () => gridElement,
		initialCol: COL_SUBJECT,
		maxCol: MAX_COL
	});

	// Expansion is per-view: expanded thread ids, their loaded thread members as
	// the API returned them (unfiltered — the view-dependent filtering happens at
	// render time), and in-flight fetches. Members are added to `expanded` only
	// after they load, so an expanded id always has a `members` entry.
	const expanded = new SvelteSet<string>();
	const members = new SvelteMap<string, MailSummaryResponse[]>();
	const loadingThreads = new SvelteSet<string>();
	// In-flight member fetches, so concurrent callers share one request.
	const memberRequests = new SvelteMap<string, Promise<MailSummaryResponse[] | null>>();
	// Generation counter for the member cache; a fetch that started before an
	// invalidation must not write its stale response into the new generation.
	let membersToken = 0;
	let viewKey = '';
	// Identity of the page the cached members were derived from. Every load
	// returns a fresh page object, so a changed reference means the folder was
	// refetched (sync_completed, a bulk action) and the cache is stale.
	let membersPage: unknown = null;

	/*
	 * Selection is per-view and has two levels, mutually exclusive per
	 * conversation. `selected` holds whole conversations by representative
	 * stableId — resolved to the folder's members only when an action runs, so a
	 * collapsed thread can be selected without fetching it. `selectedMembers`
	 * holds the individually ticked messages of a thread, mapped to their thread
	 * id: the tick lives on the message, but resolving it later must not depend on
	 * the member cache still being warm, and the id is what re-fetches it.
	 *
	 * Ticking a parent collapses its member entries into the conversation-level
	 * selection; unticking one message of a selected conversation expands that
	 * selection into per-message entries, so the rest of the thread stays selected
	 * and the parent can announce `mixed`. A conversation is never in both.
	 */
	const selected = new SvelteSet<string>();
	const selectedMembers = new SvelteMap<string, string>();
	let bulkAction = $state<BulkAction | null>(null);
	let bulkError = $state<string | null>(null);
	let bulkActionsAnnounced = false;

	const currentFolderName = $derived(
		$conversationsState.status === 'idle' ? '' : $conversationsState.context.folderName
	);
	const currentFolderRole = $derived(
		$folders.find((folder: FolderResponse) => folder.folderRef === currentFolderName)?.role
	);
	const folderRoleByRef = $derived(
		new Map($folders.map((folder: FolderResponse) => [folder.folderRef, folder.role]))
	);
	// In Drafts/Sent the sender is always the account owner, so the column shows
	// the recipient. The header follows the view, but each cell follows its own
	// message: those two views are folder-scoped today, yet a cell that read the
	// view instead of its own row would label a foreign member with the account's
	// own address the moment the scope changes again.
	const viewShowsRecipients = $derived(
		currentFolderRole === 'DRAFTS' || currentFolderRole === 'SENT'
	);
	const moveTargets = $derived(moveTargetsFor($folders, currentFolderName));

	const pageConversations = $derived(
		$conversationsState.status === 'ready' ? $conversationsState.page.content : []
	);
	const pageRepIds = $derived(
		pageConversations.map((conversation) => conversation.latest.stableId)
	);
	const conversationByThread = $derived(
		new Map(
			pageConversations
				.filter((conversation) => conversation.threadId != null)
				.map((conversation) => [conversation.threadId as string, conversation])
		)
	);
	const hasSelection = $derived(selected.size > 0 || selectedMembers.size > 0);
	const allSelected = $derived(pageRepIds.length > 0 && pageRepIds.every((id) => selected.has(id)));
	const someSelected = $derived(
		!allSelected && (pageRepIds.some((id) => selected.has(id)) || selectedMembers.size > 0)
	);
	/*
	 * What the toolbar reports. Whole conversations and individually ticked
	 * messages are counted separately rather than summed: a selected conversation
	 * stands for a number of messages this component does not know until it
	 * resolves the thread, and rounding that off to "3 selected messages" would
	 * announce a number the action then contradicts.
	 */
	const selectionSummary = $derived.by(() => {
		const conversations =
			selected.size > 0
				? $_('messages.grouping.selectedConversations', { values: { count: selected.size } })
				: '';
		const messages =
			selectedMembers.size > 0
				? $_('messages.selectedCount', { values: { count: selectedMembers.size } })
				: '';
		if (conversations && messages) return `${conversations}, ${messages}`;
		return conversations || messages;
	});

	// Effective pane mode from the mail layout; the `off` fallback keeps arrow
	// keys from navigating if the list ever renders outside that layout.
	const readingPaneCtx =
		getContext<EffectiveReadingPaneContext>(EFFECTIVE_READING_PANE_CONTEXT_KEY) ??
		({ pane: 'off' } satisfies EffectiveReadingPaneContext);

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
				for (const message of visibleMembersOf(conversation)) {
					rows.push({ kind: 'member', threadId: id, message });
				}
			}
		}
		return rows;
	});

	function isExpandable(conversation: ConversationSummaryResponse): boolean {
		return conversation.threadId != null && conversation.messageCount > 1;
	}

	/**
	 * The count the row shows. Once the members are loaded it is their number
	 * rather than the listing's, because the two come from separate calls to the
	 * same server-side scope: if the role resolution degraded between them (IMAP
	 * went down, so the second call fell back to folder-scoped) the listing's count
	 * would contradict the rows rendered underneath it. Collapsed rows have nothing
	 * to contradict and keep the listing's count.
	 */
	function displayedCount(conversation: ConversationSummaryResponse): number {
		const id = conversation.threadId;
		const loaded = id == null ? undefined : members.get(id);
		return loaded ? loaded.length : conversation.messageCount;
	}

	function messageHref(accountId: number, folderName: string, stableId: string): string {
		return resolve('/mail/[accountId]/[folderName]/[stableId]', {
			accountId: String(accountId),
			folderName: encodeURIComponent(folderName),
			stableId: encodeURIComponent(stableId)
		});
	}

	/**
	 * Opens a message that lives in `folderName` (a drafts folder opens the
	 * composer instead). `focusBody` marks a deliberate open (Enter/Space, double
	 * click) — only then does the reading cursor move into the message body. A row
	 * change that follows the roving focus in a split pane opens with the opposite
	 * intent, so focus stays on the grid cell and the next Arrow key keeps
	 * navigating (mail/bodyFocus.ts).
	 *
	 * The folder comes from the message, never from the view: an expanded thread
	 * shows members of other folders, and routing those through the folder in view
	 * would open a draft read-only instead of in the composer and hand the message
	 * route a folder the message is not in — which is what the layout header, the
	 * back link and the move control all read.
	 */
	async function openMessage(
		stableId: string,
		folderName: string,
		options: { focusBody?: boolean } = {}
	): Promise<void> {
		if ($conversationsState.status !== 'ready') return;
		const { accountId } = $conversationsState.context;
		if (folderRoleByRef.get(folderName) === 'DRAFTS') {
			await goto(rowHref(stableId, folderName));
			return;
		}
		if (options.focusBody) requestBodyFocus(stableId);
		else suppressBodyFocus(stableId);
		await goto(messageHref(accountId, folderName, stableId));
	}

	/**
	 * Where a row's subject link points. Mirrors openMessage — the message's own
	 * folder, the Drafts detour into the composer — so an assistive technology
	 * that follows the link natively lands in the same place as activating it.
	 */
	function rowHref(stableId: string, folderName: string): string {
		if ($conversationsState.status !== 'ready') return '';
		if (folderRoleByRef.get(folderName) === 'DRAFTS') {
			return `${resolve('/compose')}?draft=${encodeURIComponent(stableId)}`;
		}
		return messageHref($conversationsState.context.accountId, folderName, stableId);
	}

	/** Opens a conversation by its representative (newest in-folder) message. */
	function openConversation(
		row: ConversationSummaryResponse,
		options: { focusBody?: boolean } = {}
	): Promise<void> {
		return openMessage(row.latest.stableId, row.latest.folderName, options);
	}

	function conversationLabel(row: ConversationSummaryResponse): string {
		const size = $_('messages.grouping.threadSize', { values: { count: displayedCount(row) } });
		if (row.unreadCount > 0) {
			return `${size}, ${$_('messages.grouping.threadUnread', { values: { count: row.unreadCount } })}`;
		}
		return size;
	}

	/**
	 * Whether a top-level row really stands for a thread. A row holding a single
	 * message is a message, not a conversation — calling it one promises a thread
	 * the row does not have and tells a screen-reader user that an action will
	 * reach more than the one mail it actually touches. Same threshold as the
	 * count badge, so what is announced matches what is rendered; every label
	 * that has to choose a word for the row goes through here so the checkbox and
	 * the actions menu cannot drift apart.
	 */
	function isThread(conversation: ConversationSummaryResponse): boolean {
		return displayedCount(conversation) > 1;
	}

	/** The checkbox label — see {@link isThread}. */
	function selectionLabel(conversation: ConversationSummaryResponse): string {
		const values = { subject: conversation.latest.subject || $_('messages.noSubject') };
		return isThread(conversation)
			? $_('messages.grouping.selectConversation', { values })
			: $_('messages.selectMessage', { values });
	}

	/**
	 * The checkbox label of a member row. Naming it the way the parent is named
	 * would repeat the subject every message of the thread shares, so it goes by
	 * counterpart and timestamp — the same timestamp the row renders, clock
	 * included (formatThreadMemberDate), which is what tells two replies from the
	 * same person on the same day apart. Announcing what is rendered rather than
	 * a number of its own also means the label needs no separate explanation.
	 */
	function memberSelectionLabel(message: MailSummaryResponse): string {
		const values = {
			counterpart: (showRecipientsFor(message) ? message.recipientsTo : message.sender) ?? '',
			date: formatThreadMemberDate(message.receivedAt, $appLocale ?? 'cs')
		};
		return showRecipientsFor(message)
			? $_('messages.grouping.selectMemberTo', { values })
			: $_('messages.grouping.selectMemberFrom', { values });
	}

	/** Whether this row's counterpart is its recipient — see viewShowsRecipients. */
	function showRecipientsFor(message: MailSummaryResponse): boolean {
		const role = folderRoleByRef.get(message.folderName);
		return role === 'DRAFTS' || role === 'SENT';
	}

	/**
	 * The members this view shows under `conversation` — the fetched list as it
	 * came, in thread order. It is already the folder's conversation scope: the
	 * server filtered the excluded folders and collapsed the copies of one mail
	 * (Gmail's INBOX + All Mail) exactly as the badge counted them, so the child
	 * rows always add up to the badge.
	 *
	 * The representative is among them, listed again under the parent row it also
	 * fills (Outlook expands a conversation the same way). Two reasons it is not
	 * dropped: it is a message of the thread like any other, and dropping it left
	 * the newest message as the only one that could not be ticked on its own —
	 * the parent's checkbox stands for the whole conversation, not for that one
	 * message.
	 */
	function visibleMembersOf(conversation: ConversationSummaryResponse): MailSummaryResponse[] {
		const id = conversation.threadId;
		const raw = id == null ? undefined : members.get(id);
		return raw ?? [];
	}

	/**
	 * Fetches a thread's member list once per loaded page, scoped to the folder in
	 * view (see api/mailRead.ts — the server returns exactly what the row's badge
	 * counted). Concurrent callers (a second ArrowRight, a bulk action running
	 * while the row expands) share the in-flight request instead of firing a second
	 * fetch. Resolves to null when the fetch failed — the caller must not treat
	 * that as "the thread has no other members".
	 */
	function loadMembers(
		conversation: ConversationSummaryResponse
	): Promise<MailSummaryResponse[] | null> {
		const id = conversation.threadId;
		if (id == null || $conversationsState.status !== 'ready') return Promise.resolve(null);
		const cached = members.get(id);
		if (cached) return Promise.resolve(cached);
		const inFlight = memberRequests.get(id);
		if (inFlight) return inFlight;

		const { accountId, folderName } = $conversationsState.context;
		// Snapshot of the cache generation this fetch belongs to — a page reload
		// that lands mid-flight must not have the older response written over it.
		const token = membersToken;
		loadingThreads.add(id);
		const request = (async () => {
			try {
				const thread = await getThread(accountId, id, folderName);
				if (token === membersToken) members.set(id, thread.messages);
				return thread.messages;
			} catch (error) {
				announcePolite(`${$_('messages.grouping.loadError')} ${toErrorMessage(error)}`);
				return null;
			} finally {
				loadingThreads.delete(id);
				memberRequests.delete(id);
			}
		})();
		memberRequests.set(id, request);
		return request;
	}

	/**
	 * Toggles a thread's expansion. Expansion is committed only after members
	 * load, so a failed fetch leaves the row collapsed with an announced error.
	 * When `focusAfter` is set (keyboard toggles) focus returns to the parent
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
			const loaded = await loadMembers(conversation);
			if (!loaded) return;
			expanded.add(id);
			announcePolite(
				$_('messages.grouping.revealed', {
					values: { count: visibleMembersOf(conversation).length }
				})
			);
		}
		if (focusAfter) {
			await tick();
			const parentIndex = visibleRows.findIndex(
				(row) => row.kind === 'conversation' && row.conversation.threadId === id
			);
			if (parentIndex >= 0) grid.moveTo(parentIndex, COL_SUBJECT);
		}
	}

	/** Moves focus from a child row up to its parent conversation row. */
	function focusParentRow(rowIndex: number): void {
		for (let index = rowIndex - 1; index >= 0; index -= 1) {
			if (visibleRows[index].kind === 'conversation') {
				grid.moveTo(index, COL_SUBJECT);
				return;
			}
		}
	}

	/**
	 * The messages of `conversation` a bulk action fired from this view can reach:
	 * the representative plus the loaded members that live in the folder in view.
	 * Cross-folder members are excluded for the same reason resolveSelection drops
	 * them — an action fired from the inbox must not reach the archived copy — so
	 * they are also what "the whole conversation is ticked" is measured against.
	 * Collapsed threads have no loaded members and yield the representative alone,
	 * which is all this is used for there.
	 */
	function selectableMessagesOf(conversation: ConversationSummaryResponse): MailSummaryResponse[] {
		const own = visibleMembersOf(conversation).filter(
			(message) => message.folderName === currentFolderName
		);
		// The representative is one of them once the members are loaded; before
		// that it is all this view has.
		if (!own.some((message) => message.stableId === conversation.latest.stableId)) {
			own.unshift(conversation.latest);
		}
		return own;
	}

	/** The stableIds of this conversation's individually ticked messages. */
	function pickedMembersOf(conversation: ConversationSummaryResponse): string[] {
		const threadId = conversation.threadId;
		if (threadId == null) return [];
		const picked: string[] = [];
		for (const [stableId, id] of selectedMembers) {
			if (id === threadId) picked.push(stableId);
		}
		return picked;
	}

	function conversationChecked(conversation: ConversationSummaryResponse): boolean {
		return selected.has(conversation.latest.stableId);
	}

	/** Some but not all of the conversation's messages are ticked. */
	function conversationMixed(conversation: ConversationSummaryResponse): boolean {
		if (conversationChecked(conversation)) return false;
		return pickedMembersOf(conversation).length > 0;
	}

	function toggleConversation(
		conversation: ConversationSummaryResponse,
		isSelected: boolean
	): void {
		const repId = conversation.latest.stableId;
		for (const stableId of pickedMembersOf(conversation)) selectedMembers.delete(stableId);
		if (isSelected) selected.add(repId);
		else selected.delete(repId);
	}

	/**
	 * Ticks or unticks one message of a thread. Unticking a message of a
	 * conversation selected as a whole rewrites that selection as explicit
	 * per-message ticks first, so the rest of the thread survives; ticking the last
	 * missing message collapses the ticks back into the conversation-level
	 * selection, which is the state a collapsed row can still represent.
	 */
	function toggleMember(
		conversation: ConversationSummaryResponse,
		message: MailSummaryResponse,
		isSelected: boolean
	): void {
		const threadId = conversation.threadId;
		if (threadId == null) return;
		const actionable = selectableMessagesOf(conversation);
		if (conversationChecked(conversation)) {
			selected.delete(conversation.latest.stableId);
			for (const own of actionable) selectedMembers.set(own.stableId, threadId);
		}
		if (isSelected) selectedMembers.set(message.stableId, threadId);
		else selectedMembers.delete(message.stableId);
		if (actionable.length > 1 && actionable.every((own) => selectedMembers.has(own.stableId))) {
			for (const own of actionable) selectedMembers.delete(own.stableId);
			selected.add(conversation.latest.stableId);
		}
	}

	function handleSelectAll(checked: boolean): void {
		selectedMembers.clear();
		if (checked) for (const id of pageRepIds) selected.add(id);
		else selected.clear();
	}

	function clearSelection(): void {
		selected.clear();
		selectedMembers.clear();
	}

	/**
	 * Resolves the selection — whole conversations and individually ticked
	 * messages alike — to the union of their member stableIds in the folder in
	 * view, loading members as needed, and which of those are unread (for the
	 * optimistic folder badge).
	 *
	 * Individual ticks are re-resolved against a freshly loaded thread rather than
	 * trusted from the cache: a message ticked before a sync may be gone by the
	 * time the action runs, and acting on a stableId the thread no longer holds
	 * would report a success the folder does not show.
	 *
	 * Deliberately folder-scoped even though the expanded list is cross-folder:
	 * a bulk delete/move/mark from the inbox acts on the conversation's inbox
	 * messages only — reaching into Sent (or any other folder) from here would
	 * be surprising and destructive. Cross-folder members are filtered out.
	 *
	 * Returns null when any thread's members could not be fetched. That has to
	 * abort the whole action: a failed fetch is indistinguishable from "no other
	 * members", so proceeding would silently act on the representative alone —
	 * deleting the newest message of a thread and leaving the rest behind while
	 * reporting success.
	 */
	async function resolveSelection(): Promise<{
		memberIds: string[];
		unreadMemberIds: string[];
	} | null> {
		if ($conversationsState.status !== 'ready') return null;
		// Members are naturally unique across threads (each message belongs to one
		// thread; a thread's members exclude its representative), so plain arrays
		// need no dedup.
		const memberIds: string[] = [];
		const unread: string[] = [];
		for (const conversation of pageConversations) {
			const wholeConversation = selected.has(conversation.latest.stableId);
			const picked = pickedMembersOf(conversation);
			if (!wholeConversation && picked.length === 0) continue;
			const resolved = await resolveConversationMembers(
				conversation,
				(stableId) => wholeConversation || picked.includes(stableId)
			);
			if (!resolved) return null;
			memberIds.push(...resolved.memberIds);
			unread.push(...resolved.unreadMemberIds);
		}
		return { memberIds, unreadMemberIds: unread };
	}

	/**
	 * One conversation's members in the folder in view, filtered by `include`.
	 * Shared by the bulk bar (which passes the selection) and the row actions
	 * menu (which takes the whole thread) so both agree on what "this
	 * conversation" means — the same folder-scoped set, resolved against a
	 * freshly loaded thread.
	 */
	async function resolveConversationMembers(
		conversation: ConversationSummaryResponse,
		include: (stableId: string) => boolean
	): Promise<{ memberIds: string[]; unreadMemberIds: string[] } | null> {
		if ($conversationsState.status !== 'ready') return null;
		const { folderName } = $conversationsState.context;
		const representative = conversation.latest;
		const memberIds: string[] = [];
		const unread: string[] = [];
		const take = (message: MailSummaryResponse): void => {
			memberIds.push(message.stableId);
			if (!message.seen) unread.push(message.stableId);
		};
		if (include(representative.stableId)) take(representative);
		if (isExpandable(conversation) && conversation.threadId) {
			const threadMembers = await loadMembers(conversation);
			if (!threadMembers) return null;
			for (const message of threadMembers) {
				if (message.folderName !== folderName) continue;
				if (message.stableId === representative.stableId) continue;
				if (!include(message.stableId)) continue;
				take(message);
			}
		}
		return { memberIds, unreadMemberIds: unread };
	}

	async function runBulk(
		action: BulkAction,
		run: (memberIds: string[], ctx: ConversationBulkContext) => Promise<boolean>
	): Promise<void> {
		if (!hasSelection || bulkAction || $conversationsState.status !== 'ready') return;
		bulkAction = action;
		bulkError = null;
		try {
			const resolved = await resolveSelection();
			if (!resolved) {
				// loadMembers already announced the fetch failure; surface it in the
				// toolbar too and leave the selection untouched so a retry is one
				// click away.
				bulkError = $_('messages.grouping.bulkResolveFailed');
				return;
			}
			const { memberIds, unreadMemberIds } = resolved;
			const { accountId, folderName } = $conversationsState.context;
			const ctx: ConversationBulkContext = {
				accountId,
				folderName,
				folderRole: currentFolderRole,
				unreadMemberIds
			};
			const done = await run(memberIds, ctx);
			if (done) clearSelection();
		} catch (err) {
			bulkError = toErrorMessage(err);
		} finally {
			bulkAction = null;
		}
	}

	function handleBulkDelete(): void {
		void runBulk('delete', (ids, ctx) => deleteConversationMembers(ids, ctx));
	}

	function handleBulkMoveTo(folderRef: string): void {
		void runBulk('move', (ids, ctx) => moveConversationMembers(ids, folderRef, ctx));
	}

	function handleBulkMarkSeen(seen: boolean): void {
		void runBulk(seen ? 'read' : 'unread', (ids, ctx) =>
			markConversationMembersSeen(ids, seen, ctx)
		);
	}

	/** Read state the row displays: a conversation is unread while any member is. */
	function rowSeen(row: VisibleRow): boolean {
		return row.kind === 'conversation' ? row.conversation.unreadCount === 0 : row.message.seen;
	}

	/**
	 * Whether this row can be selected and acted on from here. Actions in this
	 * view are folder-scoped by design, so a member living elsewhere (an archived
	 * reply inside an inbox thread) is off limits — and its parent has to be on
	 * the page, because every action resolves through that conversation. The
	 * selection cell and the actions cell both ask this one question: two cells
	 * that disagreed would offer a destructive menu on a row whose own checkbox
	 * says it is not actionable.
	 */
	function isRowActionableHere(row: VisibleRow): boolean {
		if (row.kind === 'conversation') return true;
		return row.message.folderName === currentFolderName && conversationByThread.has(row.threadId);
	}

	/*
	 * Row actions menu. Same pipeline as the bulk bar above — including the
	 * permanent-delete confirmation in the trash, which is why this cannot go
	 * through `mailbox.ts` like the flat list does — but scoped to one row
	 * instead of the selection. Failures land in the same toolbar error slot.
	 */
	let rowActionBusy = $state(false);

	/**
	 * Where focus goes once a row action settles, captured before it runs.
	 *
	 * Every row action costs the focused element: a delete or move takes the row
	 * away, and even a star or read toggle unmounts the member rows for a moment,
	 * because the reload clears the member cache before refetching the expanded
	 * threads. Without this the menu trigger dies under the cursor and focus
	 * falls to `<body>` — the same hole the flat list closes with
	 * `listFocusRestore` (see the restore effect in MessageList.svelte).
	 *
	 * Rows are addressed by `rowKey`, not by stableId: the representative fills
	 * both a parent row and a child row of its expanded thread, so a stableId
	 * alone would be ambiguous here. `index` is the last resort for a row that
	 * never comes back.
	 */
	type RowFocusAnchor = { preferred: string; fallback: string | null; index: number; col: number };
	let pendingRowFocus = $state<RowFocusAnchor | null>(null);

	function rowThreadId(row: VisibleRow): string | null {
		return row.kind === 'conversation' ? row.conversation.threadId : row.threadId;
	}

	/**
	 * The acted-on row first — a toggle keeps it — and a neighbour as the
	 * fallback for the actions that remove it. The neighbour deliberately skips
	 * the rest of the same thread: deleting a conversation takes its member rows
	 * with it, so the row right below is usually just as gone.
	 */
	function focusAnchorFor(row: VisibleRow): RowFocusAnchor {
		const rows = visibleRows;
		const key = rowKey(row);
		const index = rows.findIndex((candidate) => rowKey(candidate) === key);
		const threadId = rowThreadId(row);
		const survivor = (candidate: VisibleRow): boolean => rowThreadId(candidate) !== threadId;
		const after = rows.slice(index + 1).find(survivor);
		const before = [...rows.slice(0, Math.max(0, index))].reverse().find(survivor);
		const neighbour = after ?? before ?? null;
		return {
			preferred: key,
			fallback: neighbour ? rowKey(neighbour) : null,
			index: Math.max(0, index),
			col: grid.col
		};
	}

	$effect(() => {
		const anchor = pendingRowFocus;
		if (!anchor || $conversationsState.status !== 'ready' || !gridElement) return;
		const rows = visibleRows;
		if (rows.length === 0) {
			// The grid itself is gone; the empty state takes focus instead (below).
			pendingRowFocus = null;
			return;
		}

		let index = rows.findIndex((row) => rowKey(row) === anchor.preferred);
		// Landing back on the same row keeps the column the user was in — it is
		// the same message, so the menu trigger still names it. Any other row is
		// approached through its subject cell, the row's reading anchor: the
		// actions column would announce a *different* message's menu and the
		// select column a checkbox that says nothing about what just happened.
		// Same rule as the flat list's restore effect.
		let col = anchor.col;
		if (index < 0) {
			const fallback = anchor.fallback;
			index = fallback ? rows.findIndex((row) => rowKey(row) === fallback) : -1;
			col = COL_SUBJECT;
		}
		if (index < 0) {
			// An expanded thread's members are refetching after the reload — wait
			// for them rather than grabbing some unrelated row.
			if (loadingThreads.size > 0) return;
			index = Math.min(anchor.index, rows.length - 1);
		}

		const target = index;
		const frame = requestAnimationFrame(() => {
			grid.moveTo(target, col);
			pendingRowFocus = null;
		});
		return () => cancelAnimationFrame(frame);
	});

	/** The last row went with the mutation — only the empty state is left. */
	$effect(() => {
		if (!pendingRowFocus || !emptyStateElement) return;
		const target = emptyStateElement;
		const frame = requestAnimationFrame(() => {
			target.focus();
			pendingRowFocus = null;
		});
		return () => cancelAnimationFrame(frame);
	});

	async function runRowAction(
		row: VisibleRow,
		action: (memberIds: string[], ctx: ConversationBulkContext) => Promise<boolean>
	): Promise<void> {
		if (rowActionBusy || bulkAction || $conversationsState.status !== 'ready') return;
		rowActionBusy = true;
		bulkError = null;
		const anchor = focusAnchorFor(row);
		try {
			/*
			 * A conversation row stands for every member in this folder — the set its
			 * badge counts and the bulk bar would act on — so the menu acts on that
			 * same set. A member row is one message. Cross-folder members get no menu
			 * at all (see the actions cell), so nothing here reaches outside the
			 * folder in view.
			 */
			const resolved =
				row.kind === 'conversation'
					? await resolveConversationMembers(row.conversation, () => true)
					: {
							memberIds: [row.message.stableId],
							unreadMemberIds: row.message.seen ? [] : [row.message.stableId]
						};
			if (!resolved) {
				bulkError = $_('messages.grouping.bulkResolveFailed');
				return;
			}
			const { accountId, folderName } = $conversationsState.context;
			await action(resolved.memberIds, {
				accountId,
				folderName,
				folderRole: currentFolderRole,
				unreadMemberIds: resolved.unreadMemberIds
			});
			/*
			 * Only now: the action has awaited its own reload, so the rows the
			 * restore looks through are the post-mutation ones. Requested even when
			 * the action reported nothing done (a cancelled trash confirmation, a
			 * failed request) — the menu closed and took focus with it either way.
			 */
			pendingRowFocus = anchor;
		} catch (err) {
			bulkError = toErrorMessage(err);
			pendingRowFocus = anchor;
		} finally {
			rowActionBusy = false;
		}
	}

	/**
	 * Reply, forward and the star act on a single message even on a conversation
	 * row: replying to "a thread" means replying to its newest message, and the
	 * star the row shows is the representative's. Read/move/delete take the whole
	 * row, matching what the row displays.
	 */
	function rowActions(row: VisibleRow): RowActions {
		const message = rowMessage(row);
		return {
			reply: (all) => void replyToMessage(message.stableId, all),
			forward: () => void forwardMessage(message.stableId),
			toggleFlag: () =>
				void runRowAction(row, (_ids, ctx) =>
					flagConversationMembers([message.stableId], !message.flagged, ctx)
				),
			toggleSeen: () =>
				void runRowAction(row, (ids, ctx) => markConversationMembersSeen(ids, !rowSeen(row), ctx)),
			moveTo: (folderRef) =>
				void runRowAction(row, (ids, ctx) => moveConversationMembers(ids, folderRef, ctx)),
			remove: () => void runRowAction(row, (ids, ctx) => deleteConversationMembers(ids, ctx))
		};
	}

	/** The message a row stands for — its own folder included, see openMessage. */
	function rowMessage(row: VisibleRow): MailSummaryResponse {
		return row.kind === 'conversation' ? row.conversation.latest : row.message;
	}

	/**
	 * The `{#each}` key. Not the stableId: the representative fills both its
	 * parent row and a child row of the expanded thread, so the id alone repeats
	 * and a keyed each with a duplicate key is a runtime error.
	 */
	function rowKey(row: VisibleRow): string {
		return row.kind === 'conversation'
			? `conversation:${row.conversation.latest.stableId}`
			: `member:${row.threadId}:${row.message.stableId}`;
	}

	/** Deliberate open of the row — a conversation opens on its representative. */
	function openRow(row: VisibleRow): void {
		if (row.kind === 'conversation') void openConversation(row.conversation, { focusBody: true });
		else void openMessage(row.message.stableId, row.message.folderName, { focusBody: true });
	}

	function handleKeydown(event: KeyboardEvent, row: VisibleRow, rowIndex: number): void {
		if (event.key === 'Enter' || event.key === ' ') {
			// These cells hold a native control (checkbox, expand button, menu
			// trigger) — let the key reach it instead of opening the row, which would
			// otherwise toggle AND navigate on a single Enter.
			if (grid.col === COL_SELECT || grid.col === COL_EXPAND || grid.col === COL_ACTIONS) return;
			event.preventDefault();
			openRow(row);
			return;
		}
		if ($conversationsState.status !== 'ready') return;

		// WAI-ARIA treegrid expand/collapse, on the subject cell and on the toggle
		// button's own cell. This is the sighted-keyboard path; a screen reader in
		// browse mode never delivers these keys, which is why the toggle also exists
		// as a real button.
		if (grid.col === COL_SUBJECT || grid.col === COL_EXPAND) {
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

		const next = grid.nextCell(event, rowIndex, visibleRows.length);
		if (!next) return;
		event.preventDefault();
		// A row change moves the reading-pane selection with focus; a column-only
		// move just shifts the roving cell within the current row. Same rule as the
		// flat list: selection follows focus only while a reading pane is showing.
		if (next.row !== rowIndex && readingPaneCtx.pane !== 'off' && currentFolderRole !== 'DRAFTS') {
			selectAndFocus(next.row, next.col, visibleRows[next.row]);
		} else {
			grid.moveTo(next.row, next.col);
		}
	}

	/*
	 * Same mouse model as the flat list: a click anywhere on the row opens it and
	 * moves the reading cursor into the body, and the checkbox is the only thing
	 * that selects (see MessageList.handleRowClick for why "single click selects"
	 * had to go — it swallowed the click a screen reader sends in place of the
	 * Enter it never delivers). The checkbox and the expand toggle are real
	 * controls that stop their own clicks, and the subject link handles its own.
	 */
	function handleRowClick(event: MouseEvent, row: VisibleRow): void {
		const target = event.target as HTMLElement | null;
		if (target?.closest('input, button, a')) return;
		openDeliberately(row);
	}

	/**
	 * The subject is a real link — the one affordance a screen reader can
	 * activate in browse mode however it chooses to do it. Its own handler keeps
	 * the navigation client-side and records the body-focus intent, which a
	 * native follow of the href could not.
	 */
	function handleSubjectClick(event: MouseEvent, row: VisibleRow): void {
		event.preventDefault();
		openDeliberately(row);
	}

	function openDeliberately(row: VisibleRow): void {
		// Invalidate a refocus an in-flight selectAndFocus queued, so the body wins.
		selectToken += 1;
		openRow(row);
	}

	// Announce the bulk actions the first time a selection starts (they render
	// only once something is selected — a screen-reader signal they appeared).
	$effect(() => {
		if (hasSelection) {
			if (!bulkActionsAnnounced) {
				bulkActionsAnnounced = true;
				announceBulkActionsAvailable();
			}
		} else {
			bulkActionsAnnounced = false;
		}
	});

	// Bumped on every selection. openMessage() navigates, and SvelteKit cancels
	// an in-flight navigation when a newer one starts (rapid Arrow keys). The
	// superseded navigation's promise still settles and would re-focus its
	// now-stale row last, bouncing focus backwards — so the `.finally` only
	// re-focuses while it is still the latest selection.
	let selectToken = 0;
	function selectAndFocus(rowIndex: number, col: number, row: VisibleRow): void {
		grid.moveTo(rowIndex, col);
		const token = ++selectToken;
		const message = rowMessage(row);
		void openMessage(message.stableId, message.folderName).finally(() => {
			if (token === selectToken) grid.moveTo(rowIndex, col);
		});
	}

	/*
	 * Expansion state is per-view. A folder/page switch drops it entirely. A
	 * reload of the *same* view — sync_completed, a bulk action refetch — keeps
	 * the rows expanded but invalidates the cached members: the thread may have
	 * grown a message, so a stale list would no longer match the row's count
	 * badge. The still-expanded threads are refetched right away. Selection is
	 * per-view too and goes with the folder/page switch.
	 *
	 * Every drop bumps `membersToken` so a fetch already in flight cannot write
	 * its response into the fresh generation.
	 */
	$effect(() => {
		if ($conversationsState.status !== 'ready') return;
		const ctx = $conversationsState.context;
		const key = `${ctx.accountId}:${ctx.folderName}:${ctx.page}`;
		const page = $conversationsState.page;
		if (key !== viewKey) {
			viewKey = key;
			membersPage = page;
			membersToken += 1;
			expanded.clear();
			members.clear();
			memberRequests.clear();
			loadingThreads.clear();
			selected.clear();
			selectedMembers.clear();
			return;
		}
		if (page !== membersPage) {
			membersPage = page;
			membersToken += 1;
			members.clear();
			memberRequests.clear();
			/*
			 * Individual ticks outlive the reload only for the threads refetched
			 * right away — the expanded ones. A collapsed thread's members are gone
			 * from the cache, so its ticks could no longer be shown anywhere (not
			 * even as a mixed parent), and a selection nothing on screen represents
			 * must not still act.
			 */
			for (const [stableId, threadId] of selectedMembers) {
				if (!expanded.has(threadId)) selectedMembers.delete(stableId);
			}
			void refreshExpandedMembers(page.content);
		}
	});

	// Prune selection to still-visible conversations after a same-view reload
	// (e.g. sync_completed or a bulk action refetch).
	$effect(() => {
		const visible = new Set(pageRepIds);
		for (const id of [...selected]) {
			if (!visible.has(id)) selected.delete(id);
		}
		for (const [stableId, threadId] of selectedMembers) {
			if (!conversationByThread.has(threadId)) selectedMembers.delete(stableId);
		}
	});

	// A refetched thread may have lost a ticked message (moved, deleted, synced
	// away). Drop the tick once the fresh member list says so; while the cache is
	// empty the tick stands, and resolveSelection re-checks it against the thread
	// it loads then.
	$effect(() => {
		for (const [stableId, threadId] of selectedMembers) {
			const loaded = members.get(threadId);
			if (!loaded) continue;
			if (!loaded.some((message) => message.stableId === stableId)) {
				selectedMembers.delete(stableId);
			}
		}
	});

	/**
	 * Re-fetches members for threads still expanded after a same-view reload, and
	 * collapses the ones that no longer have a row (moved out of the folder) or
	 * whose refetch failed — an expanded id must always have a members entry.
	 */
	async function refreshExpandedMembers(
		conversations: readonly ConversationSummaryResponse[]
	): Promise<void> {
		const byThreadId = new Map(
			conversations
				.filter((conversation) => conversation.threadId != null)
				.map((conversation) => [conversation.threadId as string, conversation])
		);
		await Promise.all(
			[...expanded].map(async (id) => {
				const conversation = byThreadId.get(id);
				if (!conversation || !isExpandable(conversation)) {
					expanded.delete(id);
					return;
				}
				if (!(await loadMembers(conversation))) expanded.delete(id);
			})
		);
	}

	$effect(() => {
		grid.clampRow(visibleRows.length);
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

<MailListState state={$conversationsState} bind:emptyRef={emptyStateElement}>
	{#snippet ready(pageData)}
		<div class="flex min-h-0 flex-1 flex-col bg-background">
			<MailBulkToolbar
				{allSelected}
				{someSelected}
				{hasSelection}
				summary={selectionSummary}
				busy={bulkAction}
				{moveTargets}
				error={bulkError}
				onSelectAll={handleSelectAll}
				onClear={clearSelection}
				onDelete={handleBulkDelete}
				onMarkSeen={handleBulkMarkSeen}
				onMoveTo={handleBulkMoveTo}
			/>

			<div
				bind:this={gridElement}
				role="treegrid"
				aria-label={$_('messages.grouping.listLabel')}
				aria-rowcount={visibleRows.length + 1}
				aria-colcount={7}
				class="flex-1 overflow-y-auto bg-background"
			>
				<div role="row" aria-rowindex={1} class="sr-only">
					<span role="columnheader" aria-colindex={1}>{$_('messages.columnHeaderSelect')}</span>
					<span role="columnheader" aria-colindex={2}
						>{$_('messages.grouping.columnHeaderExpand')}</span
					>
					<span role="columnheader" aria-colindex={3}>{$_('messages.columnHeaderStatus')}</span>
					<span role="columnheader" aria-colindex={4}>{$_('messages.columnHeaderSubject')}</span>
					<span role="columnheader" aria-colindex={5}
						>{viewShowsRecipients
							? $_('messages.columnHeaderRecipient')
							: $_('messages.columnHeaderSender')}</span
					>
					<span role="columnheader" aria-colindex={6}>{$_('messages.columnHeaderDate')}</span>
					<span role="columnheader" aria-colindex={7}>{$_('messages.columnHeaderActions')}</span>
				</div>
				{#each visibleRows as row, rowIndex (rowKey(row))}
					{@const isConversation = row.kind === 'conversation'}
					{@const message = isConversation ? row.conversation.latest : row.message}
					{@const expandable = isConversation && isExpandable(row.conversation)}
					{@const threadId = isConversation ? row.conversation.threadId : row.threadId}
					{@const isOpen = threadId != null && expanded.has(threadId)}
					{@const isLoading = threadId != null && loadingThreads.has(threadId)}
					{@const unread = isConversation ? row.conversation.unreadCount > 0 : !row.message.seen}
					{@const statusLabel = messageStatusLabel(message, $_)}
					<!--
						A member row carries the clock as well: inside one thread the list
						format collapses a day's replies to a single string (a weekday name, a
						bare date), so several rows would read alike and nothing would tell
						them apart. Parent rows keep the compact list format the flat list
						uses.
					-->
					{@const formattedDate = isConversation
						? formatMessageListDate(message.receivedAt, $appLocale ?? 'cs')
						: formatThreadMemberDate(message.receivedAt, $appLocale ?? 'cs')}
					<div
						role="row"
						tabindex="-1"
						data-row-index={rowIndex}
						data-stable-id={message.stableId}
						data-row-kind={isConversation ? 'conversation' : 'member'}
						aria-level={isConversation ? 1 : 2}
						aria-rowindex={rowIndex + 2}
						aria-expanded={expandable ? (isOpen ? 'true' : 'false') : undefined}
						aria-busy={isLoading ? 'true' : undefined}
						class={cn(
							'grid cursor-pointer grid-cols-[40px_28px_auto_minmax(0,1fr)_auto_40px] grid-rows-[auto_auto] border-b border-border/80 transition-colors hover:bg-muted/45 focus-within:relative focus-within:z-10',
							!isConversation && 'bg-muted/20 pl-5',
							isConversation && selected.has(row.conversation.latest.stableId) && 'bg-primary/5',
							unread && 'font-semibold'
						)}
						onclick={(e) => handleRowClick(e, row)}
						onkeydown={(e) => handleKeydown(e, row, rowIndex)}
					>
						{#if isConversation}
							{@const mixed = conversationMixed(row.conversation)}
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<div
								role="gridcell"
								aria-colindex={COL_SELECT + 1}
								tabindex="-1"
								class="row-span-2 flex items-start justify-center py-3"
								onclick={(e) => e.stopPropagation()}
							>
								<!-- 24px pointer target around the 16px box, see MessageList. -->
								<label class="flex size-6 cursor-pointer items-center justify-center">
									<input
										type="checkbox"
										{...grid.cell(rowIndex, COL_SELECT)}
										class="size-4 accent-primary"
										checked={conversationChecked(row.conversation)}
										aria-checked={mixed
											? 'mixed'
											: conversationChecked(row.conversation)
												? 'true'
												: 'false'}
										{@attach (node: HTMLInputElement) => {
											// Part of the thread ticked: the native tri-state, so the
											// box looks the way `aria-checked="mixed"` sounds.
											node.indeterminate = mixed;
										}}
										aria-label={selectionLabel(row.conversation)}
										onchange={(event) =>
											toggleConversation(
												row.conversation,
												(event.currentTarget as HTMLInputElement).checked
											)}
									/>
								</label>
							</div>
						{:else if isRowActionableHere(row)}
							{@const parentConversation = conversationByThread.get(row.threadId)!}
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<div
								role="gridcell"
								aria-colindex={COL_SELECT + 1}
								tabindex="-1"
								class="row-span-2 flex items-start justify-center py-3"
								onclick={(e) => e.stopPropagation()}
							>
								<label class="flex size-6 cursor-pointer items-center justify-center">
									<input
										type="checkbox"
										{...grid.cell(rowIndex, COL_SELECT)}
										class="size-4 accent-primary"
										checked={conversationChecked(parentConversation) ||
											selectedMembers.has(row.message.stableId)}
										aria-label={memberSelectionLabel(row.message)}
										onchange={(event) =>
											toggleMember(
												parentConversation,
												row.message,
												(event.currentTarget as HTMLInputElement).checked
											)}
									/>
								</label>
							</div>
						{:else}
							<!--
								A member living in another folder (an archived reply inside an
								inbox thread). Bulk actions here are folder-scoped by design, so
								there is nothing to tick — and an unnamed empty cell would read to
								a screen reader as a checkbox that went missing.
							-->
							<div
								role="gridcell"
								aria-colindex={COL_SELECT + 1}
								{...grid.cell(rowIndex, COL_SELECT)}
								aria-label={$_('messages.grouping.memberNotSelectable', {
									values: { folder: folderLabelByRef($folders, row.message.folderName, $_) }
								})}
								class="row-span-2 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
							></div>
						{/if}
						{#if expandable}
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<div
								role="gridcell"
								aria-colindex={COL_EXPAND + 1}
								tabindex="-1"
								class="col-start-2 row-span-2 flex items-start justify-center pt-3"
								onclick={(e) => e.stopPropagation()}
							>
								<button
									type="button"
									{...grid.cell(rowIndex, COL_EXPAND)}
									data-expand-toggle
									aria-expanded={isOpen}
									aria-label={isLoading
										? $_('messages.grouping.loading')
										: isOpen
											? $_('messages.grouping.collapseNamed', {
													values: { subject: message.subject || $_('messages.noSubject') }
												})
											: $_('messages.grouping.expandNamed', {
													values: { subject: message.subject || $_('messages.noSubject') }
												})}
									onclick={() => void toggleExpand(row.conversation)}
									class="flex size-5 items-center justify-center rounded-sm text-muted-foreground outline-none hover:bg-muted focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
								>
									<svg
										viewBox="0 0 16 16"
										aria-hidden="true"
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
								</button>
							</div>
						{:else}
							<div
								role="gridcell"
								aria-colindex={COL_EXPAND + 1}
								{...grid.cell(rowIndex, COL_EXPAND)}
								class="col-start-2 row-span-2 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
							></div>
						{/if}
						<div
							role="gridcell"
							aria-colindex={COL_STATUS + 1}
							{...grid.cell(rowIndex, COL_STATUS)}
							aria-label={statusLabel}
							class="col-start-3 row-span-2 flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<MessageFlags {message} />
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_SUBJECT + 1}
							class="col-start-4 row-start-1 min-w-0 px-2 pt-3"
						>
							<!--
								A real link, like the flat list: browse mode never delivers Enter
								to the treegrid, and a link is the one thing every screen reader
								activates there. It carries the roving tabindex, so the arrow keys
								and the expand contract are unchanged.
							-->
							<a
								href={rowHref(message.stableId, message.folderName)}
								{...grid.cell(rowIndex, COL_SUBJECT)}
								onclick={(event) => handleSubjectClick(event, row)}
								class={cn(
									'flex items-center gap-2 rounded-sm text-sm no-underline outline-none hover:underline focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
									unread ? 'text-foreground' : 'text-muted-foreground'
								)}
							>
								{#if unread}
									<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
								{/if}
								<span class="truncate">{message.subject || $_('messages.noSubject')}</span>
								{#if isConversation && displayedCount(row.conversation) > 1}
									<span
										class="shrink-0 rounded-full bg-primary/12 px-1.5 py-0.5 text-caption font-semibold text-primary"
										aria-hidden="true"
									>
										{displayedCount(row.conversation)}
									</span>
									<span class="sr-only">{conversationLabel(row.conversation)}.</span>
								{/if}
								{#if !isConversation && row.message.folderName !== currentFolderName}
									{@const memberFolderName = folderLabelByRef($folders, row.message.folderName, $_)}
									<!-- Cross-folder member (e.g. an archived reply inside the inbox
								     conversation): tag it with its home folder so the row is
								     unambiguous both visually and for a screen reader. -->
									<span
										class="shrink-0 rounded-full bg-muted px-1.5 py-0.5 text-caption font-medium text-muted-foreground"
										aria-hidden="true"
									>
										{memberFolderName}
									</span>
									<span class="sr-only"
										>{$_('messages.grouping.memberFolder', {
											values: { folder: memberFolderName }
										})}.</span
									>
								{/if}
							</a>
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_SENDER + 1}
							{...grid.cell(rowIndex, COL_SENDER)}
							class={cn(
								'col-start-4 row-start-2 truncate rounded-sm px-2 pb-3 text-sm outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50',
								unread ? 'text-foreground' : 'text-muted-foreground'
							)}
						>
							{showRecipientsFor(message) ? (message.recipientsTo ?? '') : message.sender}
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_DATE + 1}
							{...grid.cell(rowIndex, COL_DATE)}
							class="col-start-5 row-span-2 flex items-center rounded-sm px-3 text-caption text-muted-foreground outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
						>
							<time datetime={message.receivedAt}>{formattedDate}</time>
						</div>
						{#if row.kind === 'member' && !isRowActionableHere(row)}
							<!--
								A member living in another folder, same case as its empty selection
								cell: every action here is folder-scoped, and a delete that read the
								*view's* folder role would skip the permanent-delete prompt for a
								member already in the trash. Name the cell instead of leaving a
								silent gap where a screen reader expects the actions column. This
								branch comes first so the member narrowing survives into it.
							-->
							<div
								role="gridcell"
								aria-colindex={COL_ACTIONS + 1}
								{...grid.cell(rowIndex, COL_ACTIONS)}
								aria-label={$_('messages.rowActions.memberNotActionable', {
									values: { folder: folderLabelByRef($folders, row.message.folderName, $_) }
								})}
								class="col-start-6 row-span-2 outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring/50"
							></div>
						{:else}
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<div
								role="gridcell"
								aria-colindex={COL_ACTIONS + 1}
								tabindex="-1"
								class="col-start-6 row-span-2 flex items-center justify-center pr-2"
								onclick={(e) => e.stopPropagation()}
							>
								<MessageRowActionsMenu
									{message}
									col={COL_ACTIONS}
									focused={grid.isAt(rowIndex, COL_ACTIONS)}
									onCellFocus={() => grid.track(rowIndex, COL_ACTIONS)}
									currentFolderRef={currentFolderName}
									actions={rowActions(row)}
									seen={rowSeen(row)}
									triggerLabel={isConversation && isThread(row.conversation)
										? $_('messages.rowActions.conversationTrigger', {
												values: { subject: message.subject || $_('messages.noSubject') }
											})
										: undefined}
								/>
							</div>
						{/if}
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
