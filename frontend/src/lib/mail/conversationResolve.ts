/**
 * Which messages an action fired from the conversation grid reaches.
 *
 * The grid selects conversations and individual thread members, but every
 * action — the bulk bar, the row menu, print — runs on message stableIds. This
 * module turns the one into the other, and its rules are what decides what a
 * delete deletes:
 *
 * - **Folder-scoped.** An expanded thread lists members from other folders (an
 *   archived reply inside an inbox thread), but an action fired from the inbox
 *   reaches the inbox messages only. Reaching into Sent or the archive from
 *   here would be surprising and destructive.
 * - **Resolved against a freshly loaded thread.** A message ticked before a
 *   sync may be gone by the time the action runs, and acting on a stableId the
 *   thread no longer holds would report a success the folder does not show.
 * - **All or nothing.** A thread whose members could not be fetched aborts the
 *   whole resolution: a failed fetch is indistinguishable from "no other
 *   members", so going on would act on the representative alone — deleting the
 *   newest message of a thread and leaving the rest behind while reporting
 *   success.
 *
 * Kept out of ConversationList.svelte for the reason its selection and member
 * cache are (mail/conversationSelection.ts, mail/threadMembers.ts): inside the
 * component these rules were reachable only by clicking. Nothing here reads a
 * store — the caller passes the folder in view and how to load a thread.
 */
import type { ConversationBulkContext } from '#lib/mail/conversationBulk.js';
import type { ConversationSelection } from '#lib/mail/conversationSelection.js';
import type { ConversationSummaryResponse, MailSummaryResponse } from '#lib/types.js';

/** What a resolution needs from the view it runs in. */
export interface ConversationScope {
	/** The folder in view — the only one an action from it may reach. */
	folderName: string;
	/** A fresh load of the thread's members, or `null` when the fetch failed. */
	loadMembers(threadId: string): Promise<readonly MailSummaryResponse[] | null>;
}

/** One conversation's selected members in the folder in view. */
export interface ResolvedConversation {
	memberIds: string[];
	/** The unread ones among them, for the optimistic folder badge. */
	unreadMemberIds: string[];
	/** The same members as summaries, in the order memberIds lists them. */
	messages: MailSummaryResponse[];
	/**
	 * What an outcome calls these members: the message's own subject when it is
	 * the only one, the conversation's otherwise.
	 */
	subject: string | undefined;
}

/** The selection as the bulk actions take it. */
export interface BulkTargets {
	memberIds: string[];
	unreadMemberIds: string[];
	named: ConversationBulkContext['named'];
}

/**
 * Whether a row stands for a thread that has members to load. A row without a
 * thread id is a message the threading backfill has not reached yet, and one
 * with a single message has nothing beyond its representative.
 */
export function isExpandable(conversation: ConversationSummaryResponse): boolean {
	return conversation.threadId != null && conversation.messageCount > 1;
}

/**
 * The messages of `conversation` an action from the folder in view can reach:
 * the representative plus the loaded members that live in that folder. They are
 * also what "the whole conversation is ticked" is measured against. A collapsed
 * thread has no loaded members and yields the representative alone, which is
 * all this is used for there.
 */
export function selectableMessagesOf(
	conversation: ConversationSummaryResponse,
	loadedMembers: readonly MailSummaryResponse[],
	folderName: string
): MailSummaryResponse[] {
	const own = loadedMembers.filter((message) => message.folderName === folderName);
	// The representative is one of them once the members are loaded; before
	// that it is all this view has.
	if (!own.some((message) => message.stableId === conversation.latest.stableId)) {
		own.unshift(conversation.latest);
	}
	return own;
}

/**
 * One conversation's members in the folder in view, filtered by `include`.
 * Shared by the bulk bar (which passes the selection) and the row actions menu
 * (which takes the whole thread), so both agree on what "this conversation"
 * means. Returns `null` when the thread could not be loaded.
 */
export async function resolveConversationMembers(
	conversation: ConversationSummaryResponse,
	include: (stableId: string) => boolean,
	scope: ConversationScope
): Promise<ResolvedConversation | null> {
	const representative = conversation.latest;
	const memberIds: string[] = [];
	const unreadMemberIds: string[] = [];
	const messages: MailSummaryResponse[] = [];
	const take = (message: MailSummaryResponse): void => {
		memberIds.push(message.stableId);
		messages.push(message);
		if (!message.seen) unreadMemberIds.push(message.stableId);
	};
	if (include(representative.stableId)) take(representative);
	if (isExpandable(conversation) && conversation.threadId) {
		const threadMembers = await scope.loadMembers(conversation.threadId);
		if (!threadMembers) return null;
		for (const message of threadMembers) {
			if (message.folderName !== scope.folderName) continue;
			if (message.stableId === representative.stableId) continue;
			if (!include(message.stableId)) continue;
			take(message);
		}
	}
	const subject = messages.length === 1 ? messages[0]?.subject : representative.subject;
	return { memberIds, unreadMemberIds, messages, subject };
}

/**
 * Resolves the selection — whole conversations and individually ticked
 * messages alike — one entry per conversation that has anything selected, in
 * the order `conversations` lists them. Threads are loaded only for those.
 * Returns `null` as soon as one of them cannot be loaded.
 */
export async function resolveSelectedConversations(
	conversations: readonly ConversationSummaryResponse[],
	selection: Pick<ConversationSelection, 'isConversationSelected' | 'pickedMembersOf'>,
	scope: ConversationScope
): Promise<ResolvedConversation[] | null> {
	const resolvedAll: ResolvedConversation[] = [];
	for (const conversation of conversations) {
		const wholeConversation = selection.isConversationSelected(conversation.latest.stableId);
		const picked = selection.pickedMembersOf(conversation.threadId);
		if (!wholeConversation && picked.length === 0) continue;
		const resolved = await resolveConversationMembers(
			conversation,
			(stableId) => wholeConversation || picked.includes(stableId),
			scope
		);
		if (!resolved) return null;
		resolvedAll.push(resolved);
	}
	return resolvedAll;
}

/**
 * The resolved selection as the bulk actions take it. A ticked member the
 * thread no longer holds resolves to nothing, and must not turn a
 * one-conversation action into a count, so empty conversations are dropped
 * before deciding whether the outcome can name its subject.
 */
export function toBulkTargets(resolvedAll: readonly ResolvedConversation[]): BulkTargets {
	const acting = resolvedAll.filter((resolved) => resolved.memberIds.length > 0);
	// Members are unique across threads (each message belongs to one thread),
	// so plain concatenation needs no dedup.
	return {
		memberIds: acting.flatMap((resolved) => resolved.memberIds),
		unreadMemberIds: acting.flatMap((resolved) => resolved.unreadMemberIds),
		named: acting.length === 1 ? { subject: acting[0]?.subject } : undefined
	};
}

/** One conversation's messages as they go on paper: oldest first, the way a thread reads. */
export function printOrder(messages: readonly MailSummaryResponse[]): string[] {
	return [...messages]
		.sort((a, b) => Date.parse(a.receivedAt) - Date.parse(b.receivedAt))
		.map((message) => message.stableId);
}
