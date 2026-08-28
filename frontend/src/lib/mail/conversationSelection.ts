/**
 * The conversation grid's two-level selection: whole conversations and the
 * individually ticked messages of a thread, mutually exclusive per conversation.
 *
 * Whole conversations are held by representative stableId — resolved to the
 * folder's members only when an action runs, so a collapsed thread can be
 * selected without fetching it. Ticked messages are held as stableId -> thread
 * id: the tick lives on the message, but resolving it later must not depend on
 * the member cache still being warm, and the thread id is what re-fetches it.
 *
 * Ticking a parent collapses its member entries into the conversation-level
 * selection; unticking one message of a selected conversation expands that
 * selection into per-message entries, so the rest of the thread stays selected
 * and the parent can announce `mixed`. A conversation is never in both.
 *
 * Kept out of ConversationList.svelte because that is a state machine, not
 * markup: the invariant above and the collapse/expand transitions below are
 * exactly what a unit test can pin down and an e2e run can only sample by
 * clicking. Nothing here knows about messages, folders or stores — the caller
 * decides which messages of a thread this view may act on and passes their ids.
 */
import { SvelteMap, SvelteSet } from 'svelte/reactivity';

export interface ToggleMemberOptions {
	/** Representative stableId — what a whole-conversation tick is stored under. */
	repId: string;
	threadId: string;
	/**
	 * The thread's messages this view can act on, representative included, in the
	 * order the caller wants them ticked. This is what "the whole conversation is
	 * ticked" is measured against, so it must be the same folder-scoped set the
	 * action will later resolve to.
	 */
	actionableIds: readonly string[];
	/** The message being ticked or unticked. */
	memberId: string;
	isSelected: boolean;
}

export interface ConversationSelection {
	/** How many whole conversations are selected. */
	readonly conversationCount: number;
	/** How many individual messages are ticked. */
	readonly memberCount: number;
	readonly isEmpty: boolean;
	isConversationSelected(repId: string): boolean;
	isMemberTicked(stableId: string): boolean;
	/** The stableIds of one conversation's individually ticked messages. */
	pickedMembersOf(threadId: string | null): string[];
	/** Some but not all of the conversation's messages are ticked. */
	isMixed(repId: string, threadId: string | null): boolean;
	toggleConversation(repId: string, threadId: string | null, isSelected: boolean): void;
	toggleMember(options: ToggleMemberOptions): void;
	/** Select-all over the page: whole conversations, no leftover ticks. */
	selectAll(repIds: readonly string[]): void;
	clear(): void;
	/**
	 * Drops what the page in view no longer shows, after a same-view reload
	 * (sync_completed, a bulk action refetch). A selection nothing on screen
	 * represents must not still act.
	 */
	pruneToPage(repIds: readonly string[], hasThread: (threadId: string) => boolean): void;
	/**
	 * Drops the ticks of threads that are not expanded. Their members are gone
	 * from the cache, so the ticks could no longer be shown anywhere — not even
	 * as a mixed parent.
	 */
	dropTicksOfCollapsed(isExpanded: (threadId: string) => boolean): void;
	/**
	 * Drops a tick the freshly loaded thread no longer holds (the message was
	 * moved, deleted or synced away). `holdsMember` answers `undefined` for a
	 * thread whose members are not loaded: there the tick stands, and the caller
	 * re-checks it against the thread it loads when the action runs.
	 */
	dropTicksMissingFrom(
		holdsMember: (threadId: string, stableId: string) => boolean | undefined
	): void;
}

export function createConversationSelection(): ConversationSelection {
	const wholeConversations = new SvelteSet<string>();
	const tickedMembers = new SvelteMap<string, string>();

	function pickedMembersOf(threadId: string | null): string[] {
		if (threadId == null) return [];
		const picked: string[] = [];
		for (const [stableId, id] of tickedMembers) {
			if (id === threadId) picked.push(stableId);
		}
		return picked;
	}

	function hasPickedMembers(threadId: string | null): boolean {
		if (threadId == null) return false;
		for (const id of tickedMembers.values()) {
			if (id === threadId) return true;
		}
		return false;
	}

	return {
		get conversationCount() {
			return wholeConversations.size;
		},
		get memberCount() {
			return tickedMembers.size;
		},
		get isEmpty() {
			return wholeConversations.size === 0 && tickedMembers.size === 0;
		},
		isConversationSelected: (repId) => wholeConversations.has(repId),
		isMemberTicked: (stableId) => tickedMembers.has(stableId),
		pickedMembersOf,
		isMixed(repId, threadId) {
			if (wholeConversations.has(repId)) return false;
			return hasPickedMembers(threadId);
		},
		toggleConversation(repId, threadId, isSelected) {
			for (const stableId of pickedMembersOf(threadId)) tickedMembers.delete(stableId);
			if (isSelected) wholeConversations.add(repId);
			else wholeConversations.delete(repId);
		},
		toggleMember({ repId, threadId, actionableIds, memberId, isSelected }) {
			// Unticking a message of a conversation selected as a whole rewrites that
			// selection as explicit per-message ticks first, so the rest of the thread
			// survives.
			if (wholeConversations.has(repId)) {
				wholeConversations.delete(repId);
				for (const stableId of actionableIds) tickedMembers.set(stableId, threadId);
			}
			if (isSelected) tickedMembers.set(memberId, threadId);
			else tickedMembers.delete(memberId);
			// Ticking the last missing message collapses the ticks back into the
			// conversation-level selection, which is the state a collapsed row can
			// still represent. A one-message thread has no whole-conversation form to
			// collapse into — its single tick is the conversation.
			if (actionableIds.length > 1 && actionableIds.every((id) => tickedMembers.has(id))) {
				for (const stableId of actionableIds) tickedMembers.delete(stableId);
				wholeConversations.add(repId);
			}
		},
		selectAll(repIds) {
			tickedMembers.clear();
			for (const repId of repIds) wholeConversations.add(repId);
		},
		clear() {
			wholeConversations.clear();
			tickedMembers.clear();
		},
		pruneToPage(repIds, hasThread) {
			const visible = new Set(repIds);
			for (const repId of [...wholeConversations]) {
				if (!visible.has(repId)) wholeConversations.delete(repId);
			}
			for (const [stableId, threadId] of tickedMembers) {
				if (!hasThread(threadId)) tickedMembers.delete(stableId);
			}
		},
		dropTicksOfCollapsed(isExpanded) {
			for (const [stableId, threadId] of tickedMembers) {
				if (!isExpanded(threadId)) tickedMembers.delete(stableId);
			}
		},
		dropTicksMissingFrom(holdsMember) {
			for (const [stableId, threadId] of tickedMembers) {
				if (holdsMember(threadId, stableId) === false) tickedMembers.delete(stableId);
			}
		}
	};
}
