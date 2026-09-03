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
	import { createBulkAnnouncer } from '$lib/components/grid/bulkAnnouncer.js';
	import {
		createLatestSelection,
		isRowBackgroundClick
	} from '$lib/components/grid/rowActivation.js';

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
	import { createConversationSelection } from '$lib/mail/conversationSelection.js';
	import { createThreadMemberCache } from '$lib/mail/threadMembers.js';
	import type { RowActions } from '$lib/mail/rowActions.js';
	import Icon from '$lib/components/Icon.svelte';
	import MessageFlags from '$lib/components/MessageFlags.svelte';
	import MessageRowActionsMenu from '$lib/components/MessageRowActionsMenu.svelte';
	import { announcePolite } from '$lib/stores/toasts.js';
	import { nativeControlClass } from '$lib/components/ui/native-control/index.js';
	import { focusRingInset } from '$lib/components/ui/focus-ring/index.js';
	import type {
		ConversationSummaryResponse,
		FolderResponse,
		MailSummaryResponse
	} from '$lib/types.js';
	import { getContext, tick } from 'svelte';
	import { get } from 'svelte/store';

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
	const latestSelection = createLatestSelection();

	/*
	 * Expansion and selection are both per-view state machines, and both live in
	 * modules of their own (mail/threadMembers.ts, mail/conversationSelection.ts)
	 * so they can be unit tested — inside a component they were reachable only by
	 * clicking. What stays here is what the view knows and they do not: which
	 * folder is open, and which of a thread's messages this view may act on.
	 */
	const memberCache = createThreadMemberCache({
		context: () =>
			$conversationsState.status === 'ready'
				? {
						accountId: $conversationsState.context.accountId,
						folderName: $conversationsState.context.folderName
					}
				: null,
		/*
		 * Folder-scoped on purpose (see api/mailRead.ts): the server returns
		 * exactly the messages the row's badge counted — the excluded folders
		 * filtered and the copies of one mail (Gmail's INBOX + All Mail) collapsed
		 * — so the child rows always add up to the badge instead of this component
		 * re-deriving the same rule from IMAP roles.
		 */
		fetchMembers: async ({ accountId, folderName }, threadId) =>
			(await getThread(accountId, threadId, folderName)).messages,
		onLoadError: (error) =>
			announcePolite(`${$_('messages.grouping.loadError')} ${toErrorMessage(error)}`)
	});
	const selection = createConversationSelection();
	let bulkAction = $state<BulkAction | null>(null);
	let bulkError = $state<string | null>(null);

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
	const hasSelection = $derived(!selection.isEmpty);
	const allSelected = $derived(
		pageRepIds.length > 0 && pageRepIds.every((id) => selection.isConversationSelected(id))
	);
	const someSelected = $derived(
		!allSelected &&
			(pageRepIds.some((id) => selection.isConversationSelected(id)) || selection.memberCount > 0)
	);
	/*
	 * What the toolbar reports. Whole conversations and individually ticked
	 * messages are counted separately rather than summed: a selected conversation
	 * stands for a number of messages this component does not know until it
	 * resolves the thread, and rounding that off to "3 selected messages" would
	 * announce a number the action then contradicts.
	 */
	const selectionSummary = $derived.by(() => {
		const { conversationCount, memberCount } = selection;
		const conversations =
			conversationCount > 0
				? $_('messages.grouping.selectedConversations', { values: { count: conversationCount } })
				: '';
		const messages =
			memberCount > 0 ? $_('messages.selectedCount', { values: { count: memberCount } }) : '';
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

	/*
	 * Flattened parent + expanded-children list the roving grid navigates over.
	 *
	 * The children are the fetched member list as it came, in thread order — the
	 * representative among them, listed again under the parent row it also fills
	 * (Outlook expands a conversation the same way). Two reasons it is not
	 * dropped: it is a message of the thread like any other, and dropping it left
	 * the newest message as the only one that could not be ticked on its own —
	 * the parent's checkbox stands for the whole conversation, not for that one
	 * message.
	 */
	const visibleRows = $derived.by<VisibleRow[]>(() => {
		if ($conversationsState.status !== 'ready') return [];
		const rows: VisibleRow[] = [];
		for (const conversation of $conversationsState.page.content) {
			rows.push({ kind: 'conversation', conversation });
			const id = conversation.threadId;
			if (id && memberCache.isExpanded(id)) {
				for (const message of memberCache.membersOf(id)) {
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
		const loaded = memberCache.loaded(conversation.threadId);
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
	 * What identifies one message *inside* a thread: who it is with and when.
	 * Not the subject — that belongs to the conversation and every member shares
	 * it, so naming members by it repeats one string per message. The timestamp
	 * is the one the row renders, clock included (formatThreadMemberDate), which
	 * is what tells two replies from the same person on the same day apart;
	 * announcing what is rendered rather than a figure of its own also means the
	 * label needs no separate explanation.
	 *
	 * One source for every label that names a member — the checkbox, the row's
	 * link and the actions menu. Three copies of this reasoning would drift the
	 * way any three copies do.
	 */
	interface MemberIdentity {
		values: { counterpart: string; date: string };
		toRecipient: boolean;
	}

	function memberIdentity(message: MailSummaryResponse): MemberIdentity {
		const toRecipient = showRecipientsFor(message);
		/*
		 * The fallback lives here rather than at each call site because the
		 * counterpart really can be missing — `recipientsTo` is nullable, which a
		 * draft saved without a To header produces — and every label built from it
		 * needs a word there. Until now the checkbox rendered its "select the
		 * message from {counterpart}" string with the slot empty — a stray comma
		 * ahead of the date — and the row's link below would have had no
		 * accessible name at all.
		 */
		const counterpart = (toRecipient ? message.recipientsTo : message.sender) || '';
		return {
			toRecipient,
			values: {
				counterpart:
					counterpart || (toRecipient ? $_('messages.noRecipient') : $_('messages.unknownSender')),
				date: formatThreadMemberDate(message.receivedAt, $appLocale ?? 'cs')
			}
		};
	}

	/**
	 * The checkbox label of a member row — see {@link memberIdentity}. Takes the
	 * resolved identity rather than the message, like its sibling below: the row
	 * template resolves it once and hands it to every label, so the timestamp is
	 * formatted once per row instead of once per label.
	 */
	function memberSelectionLabel({ values, toRecipient }: MemberIdentity): string {
		return toRecipient
			? $_('messages.grouping.selectMemberTo', { values })
			: $_('messages.grouping.selectMemberFrom', { values });
	}

	/** The actions-menu trigger of a member row — see {@link memberIdentity}. */
	function memberActionsLabel({ values, toRecipient }: MemberIdentity): string {
		return toRecipient
			? $_('messages.rowActions.memberTriggerTo', { values })
			: $_('messages.rowActions.memberTriggerFrom', { values });
	}

	/** Whether this row's counterpart is its recipient — see viewShowsRecipients. */
	function showRecipientsFor(message: MailSummaryResponse): boolean {
		const role = folderRoleByRef.get(message.folderName);
		return role === 'DRAFTS' || role === 'SENT';
	}

	/**
	 * Toggles a thread's expansion. Expansion is committed only after members
	 * load, so a failed fetch leaves the row collapsed with an announced error.
	 *
	 * `focus` is where the reading cursor goes once the rows are in, and the two
	 * callers deliberately ask for different things. ArrowRight says `parent`:
	 * that is the WAI-ARIA treegrid contract, where expanding a node leaves focus
	 * on it and a second ArrowRight steps into the children. The toggle button
	 * says `firstMember`, because it is the browse-mode path — a screen reader
	 * never delivers ArrowRight here and sends Enter as a click — and there the
	 * cost is not one keystroke but reading: the cursor would otherwise walk the
	 * rest of the parent row (status, sender, date, the actions trigger, each
	 * naming the same subject) before reaching the message the user expanded the
	 * thread to get to. The oldest message is where a conversation is read from.
	 *
	 * What that costs is the button relabelling itself under a focus that stayed
	 * put ("Sbalit konverzaci …"), which used to be how the keypress confirmed
	 * itself. The polite announcement covers it — it says how many messages
	 * appeared, which is the more useful half — and ArrowLeft on a member returns
	 * to the parent, so the collapse toggle is one key away.
	 *
	 * Collapsing keeps focus exactly where it was for both callers: the button
	 * that collapsed the thread is still under the cursor, and moving off it
	 * would take the user away from the control they just used.
	 */
	async function toggleExpand(
		conversation: ConversationSummaryResponse,
		focus: 'parent' | 'firstMember'
	): Promise<void> {
		const id = conversation.threadId;
		if (id == null || !isExpandable(conversation)) return;
		if (memberCache.isExpanded(id)) {
			memberCache.collapse(id);
			announcePolite($_('messages.grouping.collapsed'));
			if (focus === 'parent') {
				await tick();
				focusRowOfThread(id, 'conversation');
			}
			return;
		}
		if (!(await memberCache.load(id))) return;
		// Silent when the cache refuses: the view moved on while the fetch ran, so
		// the members never reached it. Announcing a count here would name rows the
		// grid is not about to render.
		if (!memberCache.expand(id)) return;
		announcePolite(
			$_('messages.grouping.revealed', {
				values: { count: memberCache.membersOf(id).length }
			})
		);
		await tick();
		// A thread with no member row to land on falls back to its parent rather
		// than leaving focus on a control that has just changed meaning.
		if (focus === 'firstMember' && focusRowOfThread(id, 'member')) return;
		focusRowOfThread(id, 'conversation');
	}

	/**
	 * The cell a row is approached through — whatever carries its identity, so a
	 * cursor arriving there hears which row it is on. That is the subject for a
	 * conversation and the counterpart for a member: a member's subject cell is
	 * deliberately empty, because the subject belongs to the thread, so landing
	 * there would announce nothing at all.
	 */
	function readingAnchorCol(kind: VisibleRow['kind']): number {
		return kind === 'conversation' ? COL_SUBJECT : COL_SENDER;
	}

	/**
	 * Moves the roving cursor to this thread's parent row, or to the first of its
	 * member rows, on that row's reading anchor. Returns whether such a row was
	 * there to move to.
	 */
	function focusRowOfThread(threadId: string, kind: VisibleRow['kind']): boolean {
		const index = visibleRows.findIndex((row) =>
			row.kind === 'conversation'
				? kind === 'conversation' && row.conversation.threadId === threadId
				: kind === 'member' && row.threadId === threadId
		);
		if (index < 0) return false;
		grid.moveTo(index, readingAnchorCol(kind));
		return true;
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
		const own = memberCache
			.membersOf(conversation.threadId)
			.filter((message) => message.folderName === currentFolderName);
		// The representative is one of them once the members are loaded; before
		// that it is all this view has.
		if (!own.some((message) => message.stableId === conversation.latest.stableId)) {
			own.unshift(conversation.latest);
		}
		return own;
	}

	/*
	 * The selection state machine itself is in mail/conversationSelection.ts; what
	 * follows only maps a conversation to the ids it works in — the representative
	 * stableId a whole-thread tick is stored under, the thread id its members hang
	 * off, and (for a member tick) the folder-scoped set above.
	 */
	function conversationChecked(conversation: ConversationSummaryResponse): boolean {
		return selection.isConversationSelected(conversation.latest.stableId);
	}

	function conversationMixed(conversation: ConversationSummaryResponse): boolean {
		return selection.isMixed(conversation.latest.stableId, conversation.threadId);
	}

	function toggleConversation(
		conversation: ConversationSummaryResponse,
		isSelected: boolean
	): void {
		selection.toggleConversation(conversation.latest.stableId, conversation.threadId, isSelected);
	}

	function toggleMember(
		conversation: ConversationSummaryResponse,
		message: MailSummaryResponse,
		isSelected: boolean
	): void {
		const threadId = conversation.threadId;
		if (threadId == null) return;
		selection.toggleMember({
			repId: conversation.latest.stableId,
			threadId,
			actionableIds: selectableMessagesOf(conversation).map((own) => own.stableId),
			memberId: message.stableId,
			isSelected
		});
	}

	function handleSelectAll(checked: boolean): void {
		if (checked) selection.selectAll(pageRepIds);
		else selection.clear();
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
			const wholeConversation = selection.isConversationSelected(conversation.latest.stableId);
			const picked = selection.pickedMembersOf(conversation.threadId);
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
			const threadMembers = await memberCache.load(conversation.threadId);
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
				// The member cache already announced the fetch failure; surface it in
				// the toolbar too and leave the selection untouched so a retry is one
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
			if (done) selection.clear();
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
		// approached through its reading anchor: the actions column would announce
		// a *different* message's menu and the select column a checkbox that says
		// nothing about what just happened. Same rule as the flat list's restore
		// effect.
		const landedOnSameRow = index >= 0;
		if (index < 0) {
			const fallback = anchor.fallback;
			index = fallback ? rows.findIndex((row) => rowKey(row) === fallback) : -1;
		}
		if (index < 0) {
			// An expanded thread's members are refetching after the reload — wait
			// for them rather than grabbing some unrelated row.
			if (memberCache.loadingCount > 0) return;
			index = Math.min(anchor.index, rows.length - 1);
		}

		const target = index;
		// Resolved from the row actually landed on, not from the row that was
		// asked for: which column is the anchor depends on the kind of row, and a
		// fallback can be a conversation where the lost row was a member.
		const col = landedOnSameRow ? anchor.col : readingAnchorCol(rows[target].kind);
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
		/*
		 * The anchor column differs by row kind (see readingAnchorCol), so the
		 * treegrid keys have to answer on whichever one the cursor actually lands
		 * on. A member's is the sender cell — that is where expanding a thread
		 * leaves the cursor, and ArrowLeft has to walk back up to the parent from
		 * exactly there or the contract breaks where the user is standing.
		 */
		if (
			grid.col === COL_SUBJECT ||
			grid.col === COL_EXPAND ||
			(row.kind === 'member' && grid.col === COL_SENDER)
		) {
			if (row.kind === 'conversation' && isExpandable(row.conversation)) {
				const id = row.conversation.threadId as string;
				if (event.key === 'ArrowRight' && !memberCache.isExpanded(id)) {
					event.preventDefault();
					void toggleExpand(row.conversation, 'parent');
					return;
				}
				if (event.key === 'ArrowLeft' && memberCache.isExpanded(id)) {
					event.preventDefault();
					void toggleExpand(row.conversation, 'parent');
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

	// Same mouse model as the flat list: a click on the row opens it, the
	// checkbox alone selects, and the expand toggle and the row's link are real
	// controls that own their clicks. The reasoning is in grid/rowActivation.ts.
	function handleRowClick(event: MouseEvent, row: VisibleRow): void {
		if (isRowBackgroundClick(event)) openDeliberately(row);
	}

	/**
	 * The row's own link — the subject on a conversation row, the counterpart on
	 * a member row, whichever cell carries that row's identity. It is the one
	 * affordance a screen reader can activate in browse mode however it chooses
	 * to do it; handling the click here keeps the navigation client-side and
	 * records the body-focus intent, which a native follow of the href could not.
	 */
	function handleRowLinkClick(event: MouseEvent, row: VisibleRow): void {
		event.preventDefault();
		openDeliberately(row);
	}

	function openDeliberately(row: VisibleRow): void {
		// Retire a refocus an in-flight selectAndFocus queued, so the body wins.
		latestSelection.retire();
		openRow(row);
	}

	// The bulk actions render only once something is selected, so their arrival
	// is a screen-reader signal of its own.
	const announceBulkActions = createBulkAnnouncer(announceBulkActionsAvailable);
	$effect(() => {
		announceBulkActions(hasSelection);
	});

	function selectAndFocus(rowIndex: number, col: number, row: VisibleRow): void {
		grid.moveTo(rowIndex, col);
		const isLatest = latestSelection.begin();
		const message = rowMessage(row);
		void openMessage(message.stableId, message.folderName).finally(() => {
			if (isLatest()) grid.moveTo(rowIndex, col);
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
	 * The page is read from the store rather than from `pageConversations`: this
	 * is the effect that decides the cache is stale, so it has to compare against
	 * the very object the store just handed out, not a derived view of it.
	 */
	$effect(() => {
		if ($conversationsState.status !== 'ready') return;
		const ctx = $conversationsState.context;
		const page = $conversationsState.page;
		const transition = memberCache.syncToView(
			`${ctx.accountId}:${ctx.folderName}:${ctx.page}`,
			page
		);
		if (transition === 'switched') {
			selection.clear();
			return;
		}
		if (transition !== 'reloaded') return;
		/*
		 * Individual ticks outlive the reload only for the threads refetched right
		 * away — the expanded ones. A collapsed thread's members are gone from the
		 * cache, so its ticks could no longer be shown anywhere (not even as a
		 * mixed parent), and a selection nothing on screen represents must not
		 * still act.
		 */
		selection.dropTicksOfCollapsed((threadId) => memberCache.isExpanded(threadId));
		const byThreadId = new Map(
			page.content
				.filter((conversation) => conversation.threadId != null)
				.map((conversation) => [conversation.threadId as string, conversation])
		);
		void memberCache.refreshExpanded((threadId) => {
			const conversation = byThreadId.get(threadId);
			return conversation != null && isExpandable(conversation);
		});
	});

	// Prune selection to still-visible conversations after a same-view reload
	// (e.g. sync_completed or a bulk action refetch).
	$effect(() => {
		selection.pruneToPage(pageRepIds, (threadId) => conversationByThread.has(threadId));
	});

	// A refetched thread may have lost a ticked message (moved, deleted, synced
	// away). Drop the tick once the fresh member list says so; while the cache is
	// empty the tick stands, and resolveSelection re-checks it against the thread
	// it loads then.
	$effect(() => {
		selection.dropTicksMissingFrom((threadId, stableId) => {
			const loaded = memberCache.loaded(threadId);
			if (!loaded) return undefined;
			return loaded.some((message) => message.stableId === stableId);
		});
	});

	$effect(() => {
		grid.clampRow(visibleRows.length);
	});

	async function navigateToPage(target: number): Promise<void> {
		if ($conversationsState.status !== 'ready') return;
		const ctx = $conversationsState.context;
		await loadConversationsPage(ctx.accountId, ctx.folderName, target, ctx.size);
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
				onClear={() => selection.clear()}
				onDelete={handleBulkDelete}
				onMarkSeen={handleBulkMarkSeen}
				onMoveTo={handleBulkMoveTo}
			/>

			<!--
				The column widths live here, on the list, not on each row. They used to
				live on the row, and a track sized to one row's own content lines up
				with the row above it only by accident: the date column measured 1164px
				from the left for `14:32`, 1148px for a weekday name and 1118px for a
				full date, and an expanded parent — whose date cell is deliberately
				empty — collapsed to the padding, 80px away from the very children it
				heads. Rows are `subgrid`, so every one of them resolves against this
				one set of tracks and the columns line up by construction rather than by
				coincidence of string width. A floor on the row's own track was tried
				first and is not enough: it aligns only while every date fits under it,
				which is a property of the font. It held on Windows by 0.45px and lost
				on CI's Linux fonts, where the same date measures 108.5px against a
				104px floor.

				`content-start` because the implicit rows are `auto`: without it a short
				list stretches its rows to fill the viewport.
			-->
			<div
				bind:this={gridElement}
				role="treegrid"
				aria-label={$_('messages.grouping.listLabel')}
				aria-rowcount={visibleRows.length + 1}
				aria-colcount={7}
				class="grid flex-1 grid-cols-[2.5rem_1.75rem_auto_minmax(0,1fr)_auto_2.5rem] content-start overflow-y-auto bg-background"
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
					{@const isOpen = memberCache.isExpanded(threadId)}
					{@const isLoading = memberCache.isLoading(threadId)}
					{@const unread = isConversation ? row.conversation.unreadCount > 0 : !row.message.seen}
					<!--
						An expanded conversation row is a header, not a message. Sender and
						date describe the newest message, which is now listed underneath as
						a child row of its own — rendering them here says the same thing
						twice, and a screen reader reads the row cell by cell, so the second
						time costs speech on the way to the conversation it just opened.
						Collapsed rows keep both: there the row is all there is, and who
						wrote last and when is what the folder is triaged on. The cells
						themselves stay — they are grid columns, and dropping them would
						renumber the roving navigation — but they stay genuinely empty. An
						aria-label would spend the saving on announcing the emptiness, which
						is why the named empty cells further down are named only where a
						control was expected and is missing.
					-->
					{@const conversationHeader = isConversation && isOpen}
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
					<!--
						Resolved once per row: the counterpart below and the actions trigger
						further down both need it, and each call formats the timestamp through
						`Intl`. Conversation rows never read it, so the branch keeps the work
						off them entirely.
					-->
					{@const identity = isConversation ? null : memberIdentity(message)}
					<!--
						Columns come from the list (see the `subgrid` note above); only the two
						row tracks are the row's own. The second of them still needs a floor:
						an expanded parent empties its sender cell, and an empty cell would
						collapse that track from 32px to 14px and leave the conversation header
						the one row in the list shorter than every other. The number is the
						member rows' own — their line height plus the padding they already
						carry.
					-->
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
							'col-span-full grid cursor-pointer grid-cols-subgrid grid-rows-[auto_auto] border-b border-border/80 transition-colors hover:bg-muted/40 focus-within:relative focus-within:z-10',
							!isConversation && 'bg-muted/20',
							isConversation && conversationChecked(row.conversation) && 'bg-primary/10',
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
										class={nativeControlClass}
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
										class={nativeControlClass}
										checked={conversationChecked(parentConversation) ||
											selection.isMemberTicked(row.message.stableId)}
										aria-label={memberSelectionLabel(memberIdentity(row.message))}
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
								class={cn('row-span-2 rounded-sm', focusRingInset)}
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
									onclick={() => void toggleExpand(row.conversation, 'firstMember')}
									class={cn(
										'flex size-5 items-center justify-center rounded-sm text-muted-foreground hover:bg-muted',
										focusRingInset
									)}
								>
									<Icon
										name="chevron-right"
										size={14}
										class={cn('transition-transform', isOpen && 'rotate-90')}
									/>
								</button>
							</div>
						{:else}
							<div
								role="gridcell"
								aria-colindex={COL_EXPAND + 1}
								{...grid.cell(rowIndex, COL_EXPAND)}
								class={cn('col-start-2 row-span-2 rounded-sm', focusRingInset)}
							></div>
						{/if}
						<div
							role="gridcell"
							aria-colindex={COL_STATUS + 1}
							{...grid.cell(rowIndex, COL_STATUS)}
							aria-label={statusLabel}
							class={cn(
								'col-start-3 row-span-2 flex items-center gap-1 rounded-sm px-2 text-caption text-muted-foreground',
								focusRingInset
							)}
						>
							<MessageFlags {message} />
						</div>
						<!--
							The subject track, and the two things that make a member row differ
							from a conversation row.

							**Only a conversation renders the subject.** It belongs to the thread,
							so a member repeating it said the one string in every child cell and
							again inside the name of every child's actions menu — eight times
							across four rows for a three-mail thread, six of them on members.
							Reading a thread top to bottom should say who wrote; the parent
							already said what it is about.

							The cell stays, empty, for the reason the expanded parent's sender and
							date cells do: it is a grid column, and `focusGridCell` resolves a
							roving move by `[data-cell-target][data-col]` inside the target row, so
							a row missing one would swallow an ArrowDown from the column above it.
							Silent, without an `aria-label` — naming it would spend the saving on
							announcing the emptiness. It spans both row tracks so that the focus
							ring has the row's height to draw around: sized to its own (empty)
							content it collapsed to an 8px sliver, measured, and `pointer-events-none`
							keeps the layer off the counterpart link it now sits over.

							**The indent lives on the cells, not the row.** It used to be `pl-5` on
							the row, which worked only while each row was its own grid; under
							`subgrid` a padding there shifts every one of the row's columns,
							actions included, and pushes the last one past the list's right edge.
							`pr-2` with an explicit `pl-*` rather than `px-2` and an override, so
							neither value depends on which utility Tailwind emits last.
						-->
						<div
							role="gridcell"
							aria-colindex={COL_SUBJECT + 1}
							{...grid.cell(rowIndex, COL_SUBJECT)}
							class={cn(
								'col-start-4 min-w-0 rounded-sm pr-2',
								isConversation
									? 'row-start-1 pt-3 pl-2'
									: 'pointer-events-none row-span-2 row-start-1 pl-7',
								focusRingInset
							)}
						>
							{#if isConversation}
								<!--
									A real link, like the flat list: browse mode never delivers Enter
									to the treegrid, and a link is the one thing every screen reader
									activates there.

									The roving tabindex sits on the CELL, not on this link, and the
									difference is audible. This is the only cell in the row holding
									both text and a focusable element carrying that same text, so with
									focus on the link a reader announced the cell it had entered and
									then the link inside it, and every arrow key read the subject
									twice. The flat list carried the identical fault until its subject
									cell took focus; heard here in grouped mode with NVDA before it
									was changed, not inferred from the flat list.

									Earlier revisions put the tabindex on the link on the grounds that
									browse mode could otherwise not activate it. Listening disproved
									that in the treegrid too, not only in the flat list: with
									`tabindex="-1"` the anchor keeps its href and its link role, the
									virtual cursor still finds it, and Enter still opens the message.
									Both halves were heard here — the doubling before the change, the
									activation and the single announcement after it.
								-->
								<a
									href={rowHref(message.stableId, message.folderName)}
									tabindex="-1"
									onclick={(event) => handleRowLinkClick(event, row)}
									class={cn(
										'flex items-center gap-2 text-sm no-underline hover:underline',
										unread ? 'text-foreground' : 'text-muted-foreground'
									)}
								>
									{#if unread}
										<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
									{/if}
									<span class="truncate">{message.subject || $_('messages.noSubject')}</span>
									{#if displayedCount(row.conversation) > 1}
										<span
											class="shrink-0 rounded-full bg-primary/10 px-1.5 py-0.5 text-caption font-semibold text-primary"
											aria-hidden="true"
										>
											{displayedCount(row.conversation)}
										</span>
										<span class="sr-only">{conversationLabel(row.conversation)}.</span>
									{/if}
								</a>
							{/if}
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_SENDER + 1}
							{...grid.cell(rowIndex, COL_SENDER)}
							class={cn(
								'col-start-4 row-start-2 min-h-8 truncate rounded-sm pr-2 pb-3 text-sm',
								isConversation ? 'pl-2' : 'pl-7',
								unread ? 'text-foreground' : 'text-muted-foreground',
								focusRingInset
							)}
						>
							{#if isConversation}
								{#if !conversationHeader}
									{showRecipientsFor(message) ? (message.recipientsTo ?? '') : message.sender}
								{/if}
							{:else}
								<!--
									A member's own label, and the row's link. It sits in the sender
									cell rather than where a conversation puts its subject, so the
									column headers keep telling the truth: moving across the row
									announces the sender header over a person's name, rather than
									the subject header over one. Reading the thread downwards then
									goes subject, then the people — which is how a conversation
									reads.

									The roving tabindex sits on the CELL, not on this link — the
									same structure the subject column above holds, and for the same
									reason: `readingAnchorCol` sends a member's cursor to this
									column, so this is the one cell a member row is read through,
									and it is the only cell in that row holding both text and a
									focusable element carrying that same text. The two subject
									columns were fixed twice before this one was noticed.

									Unlike those two, the doubling was NOT heard here — the flat
									list and the conversation header both were, and this is the
									third instance of a structure whose fix they settled. What is
									covered by a test is the structure and the activation; what a
									listening session still owes is the announcement itself.
								-->
								<a
									href={rowHref(message.stableId, message.folderName)}
									tabindex="-1"
									onclick={(event) => handleRowLinkClick(event, row)}
									class="flex items-center gap-2 no-underline hover:underline"
								>
									{#if unread}
										<span class="sr-only">{$_('messages.unreadIndicatorLabel')}.</span>
									{/if}
									<span class="truncate">{identity?.values.counterpart}</span>
									{#if row.message.folderName !== currentFolderName}
										{@const memberFolderName = folderLabelByRef(
											$folders,
											row.message.folderName,
											$_
										)}
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
							{/if}
						</div>
						<div
							role="gridcell"
							aria-colindex={COL_DATE + 1}
							{...grid.cell(rowIndex, COL_DATE)}
							class={cn(
								'col-start-5 row-span-2 flex items-center rounded-sm px-3 text-caption text-muted-foreground',
								focusRingInset
							)}
						>
							{#if !conversationHeader}
								<time datetime={message.receivedAt}>{formattedDate}</time>
							{/if}
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
								class={cn('col-start-6 row-span-2 rounded-sm', focusRingInset)}
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
									triggerLabel={isConversation
										? isThread(row.conversation)
											? $_('messages.rowActions.conversationTrigger', {
													values: { subject: message.subject || $_('messages.noSubject') }
												})
											: undefined
										: memberActionsLabel(identity!)}
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
			onNavigate={navigateToPage}
			landmarkLabel={$_('messages.paginationLandmark')}
		/>
	{/snippet}
</MailListState>
