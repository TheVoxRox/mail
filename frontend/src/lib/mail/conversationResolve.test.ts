import { describe, expect, it, vi } from 'vitest';
import {
	isExpandable,
	printOrder,
	resolveConversationMembers,
	resolveSelectedConversations,
	selectableMessagesOf,
	toBulkTargets,
	type ConversationScope,
	type ResolvedConversation
} from './conversationResolve.js';
import type { ConversationSummaryResponse, MailSummaryResponse } from '#lib/types.js';

/*
 * What a delete from the conversation grid deletes. Inside the component these
 * rules were reachable only through an e2e run against the mock backend, which
 * covers the happy path of each action and none of the failure branches.
 *
 * Fixture: thread `t1` in the INBOX, `rep` its representative (newest), `m1`
 * and `m2` older inbox members, `arch` a copy of the thread in ARCHIVE.
 */

function message(
	stableId: string,
	options: { folderName?: string; seen?: boolean; receivedAt?: string; subject?: string } = {}
): MailSummaryResponse {
	return {
		id: Number(stableId.replace(/\D/g, '')) || 1,
		stableId,
		folderName: options.folderName ?? 'INBOX',
		subject: options.subject ?? `Subject of ${stableId}`,
		sender: 'someone@example.com',
		recipientsTo: null,
		receivedAt: options.receivedAt ?? '2026-08-28T10:00:00Z',
		seen: options.seen ?? true,
		flagged: false,
		answered: false,
		hasAttachments: false,
		messageId: `<${stableId}@example.com>`
	};
}

const REP = message('rep', { subject: 'Re: Plan', receivedAt: '2026-08-28T12:00:00Z' });
const M1 = message('m1', { seen: false, subject: 'Plan', receivedAt: '2026-08-28T09:00:00Z' });
const M2 = message('m2', { subject: 'Re: Plan', receivedAt: '2026-08-28T10:00:00Z' });
const ARCH = message('arch', { folderName: 'ARCHIVE', receivedAt: '2026-08-28T11:00:00Z' });
/** Thread order, oldest first, as the thread endpoint returns it. */
const THREAD = [M1, M2, ARCH, REP];

function conversation(
	latest: MailSummaryResponse,
	threadId: string | null,
	messageCount: number
): ConversationSummaryResponse {
	return { threadId, latest, messageCount, unreadCount: 0 };
}

const THREAD_ROW = conversation(REP, 't1', 4);

function scope(
	threads: Record<string, readonly MailSummaryResponse[] | null>
): ConversationScope & {
	loadMembers: ReturnType<typeof vi.fn>;
} {
	return {
		folderName: 'INBOX',
		loadMembers: vi.fn(async (threadId: string) => threads[threadId] ?? null)
	};
}

/** A selection stub: whole conversations by representative id, picks by thread. */
function selected(whole: string[], picked: Record<string, string[]> = {}) {
	return {
		isConversationSelected: (repId: string) => whole.includes(repId),
		pickedMembersOf: (threadId: string | null) => (threadId ? (picked[threadId] ?? []) : [])
	};
}

describe('isExpandable', () => {
	it('needs a thread id and more than one message', () => {
		expect(isExpandable(THREAD_ROW)).toBe(true);
		expect(isExpandable(conversation(REP, 't1', 1))).toBe(false);
		expect(isExpandable(conversation(REP, null, 4))).toBe(false);
	});
});

describe('selectableMessagesOf', () => {
	it('is the representative alone while the thread is not loaded', () => {
		expect(selectableMessagesOf(THREAD_ROW, [], 'INBOX')).toEqual([REP]);
	});

	it('keeps the loaded members of the folder in view and leaves the archived copy out', () => {
		expect(selectableMessagesOf(THREAD_ROW, THREAD, 'INBOX')).toEqual([M1, M2, REP]);
	});

	it('puts the representative first when the loaded members lack it', () => {
		expect(selectableMessagesOf(THREAD_ROW, [M1, M2], 'INBOX')).toEqual([REP, M1, M2]);
	});
});

describe('resolveConversationMembers', () => {
	it('takes every member in the folder in view, representative first, no copy elsewhere', async () => {
		const resolved = await resolveConversationMembers(
			THREAD_ROW,
			() => true,
			scope({ t1: THREAD })
		);
		expect(resolved?.memberIds).toEqual(['rep', 'm1', 'm2']);
		expect(resolved?.messages).toEqual([REP, M1, M2]);
	});

	it('reports which of them are unread, for the folder badge', async () => {
		const resolved = await resolveConversationMembers(
			THREAD_ROW,
			() => true,
			scope({ t1: THREAD })
		);
		expect(resolved?.unreadMemberIds).toEqual(['m1']);
	});

	it('takes only what `include` lets through', async () => {
		const resolved = await resolveConversationMembers(
			THREAD_ROW,
			(stableId) => stableId === 'm2',
			scope({ t1: THREAD })
		);
		expect(resolved?.memberIds).toEqual(['m2']);
	});

	it('takes the representative once, though the thread lists it too', async () => {
		const resolved = await resolveConversationMembers(
			THREAD_ROW,
			() => true,
			scope({ t1: [M1, REP, REP] })
		);
		expect(resolved?.memberIds).toEqual(['rep', 'm1']);
	});

	it('names one message by its own subject and several by the conversation', async () => {
		const one = await resolveConversationMembers(
			THREAD_ROW,
			(stableId) => stableId === 'm1',
			scope({ t1: THREAD })
		);
		expect(one?.subject).toBe('Plan');
		const all = await resolveConversationMembers(THREAD_ROW, () => true, scope({ t1: THREAD }));
		expect(all?.subject).toBe('Re: Plan');
	});

	it('does not load a row that is a single message', async () => {
		const single = scope({});
		const resolved = await resolveConversationMembers(
			conversation(REP, 't1', 1),
			() => true,
			single
		);
		expect(resolved?.memberIds).toEqual(['rep']);
		expect(single.loadMembers).not.toHaveBeenCalled();
	});

	it('is null when the thread cannot be loaded, never the representative alone', async () => {
		const resolved = await resolveConversationMembers(THREAD_ROW, () => true, scope({ t1: null }));
		expect(resolved).toBeNull();
	});
});

describe('resolveSelectedConversations', () => {
	const OTHER_REP = message('other', { receivedAt: '2026-08-27T10:00:00Z' });
	const OTHER_ROW = conversation(OTHER_REP, 't2', 2);
	const OTHER_MEMBER = message('o1', { receivedAt: '2026-08-26T10:00:00Z' });
	const PAGE = [THREAD_ROW, OTHER_ROW];
	const THREADS = { t1: THREAD, t2: [OTHER_MEMBER, OTHER_REP] };

	it('resolves only the conversations with something selected, and loads only those', async () => {
		const loads = scope(THREADS);
		const resolved = await resolveSelectedConversations(PAGE, selected(['other']), loads);
		expect(resolved?.map((entry) => entry.memberIds)).toEqual([['other', 'o1']]);
		expect(loads.loadMembers).toHaveBeenCalledTimes(1);
		expect(loads.loadMembers).toHaveBeenCalledWith('t2');
	});

	it('follows the order of the page, not the order of selecting', async () => {
		const resolved = await resolveSelectedConversations(
			PAGE,
			selected(['other', 'rep']),
			scope(THREADS)
		);
		expect(resolved?.map((entry) => entry.memberIds[0])).toEqual(['rep', 'other']);
	});

	it('takes individually ticked members alone', async () => {
		const resolved = await resolveSelectedConversations(
			PAGE,
			selected([], { t1: ['m1'] }),
			scope(THREADS)
		);
		expect(resolved?.map((entry) => entry.memberIds)).toEqual([['m1']]);
	});

	it('resolves a ticked member the thread no longer holds to nothing', async () => {
		const resolved = await resolveSelectedConversations(
			PAGE,
			selected([], { t1: ['gone'] }),
			scope(THREADS)
		);
		expect(resolved?.map((entry) => entry.memberIds)).toEqual([[]]);
	});

	it('aborts the whole selection when one thread fails to load', async () => {
		const resolved = await resolveSelectedConversations(
			PAGE,
			selected(['rep', 'other']),
			scope({ t1: THREAD, t2: null })
		);
		expect(resolved).toBeNull();
	});
});

describe('toBulkTargets', () => {
	function resolvedOf(
		memberIds: string[],
		subject: string,
		unread: string[] = []
	): ResolvedConversation {
		return {
			memberIds,
			unreadMemberIds: unread,
			messages: memberIds.map((stableId) => message(stableId)),
			subject
		};
	}

	it('joins the conversations, unread members included', () => {
		const targets = toBulkTargets([
			resolvedOf(['rep', 'm1'], 'Re: Plan', ['m1']),
			resolvedOf(['other'], 'Other', ['other'])
		]);
		expect(targets.memberIds).toEqual(['rep', 'm1', 'other']);
		expect(targets.unreadMemberIds).toEqual(['m1', 'other']);
		expect(targets.named).toBeUndefined();
	});

	it('names the subject when one conversation acts', () => {
		expect(toBulkTargets([resolvedOf(['rep', 'm1'], 'Re: Plan')]).named).toEqual({
			subject: 'Re: Plan'
		});
	});

	it('does not count a conversation that resolved to nothing', () => {
		// A tick on a message the thread lost must not turn a one-conversation
		// action into "2 conversations".
		const targets = toBulkTargets([resolvedOf([], 'Gone'), resolvedOf(['rep'], 'Re: Plan')]);
		expect(targets.memberIds).toEqual(['rep']);
		expect(targets.named).toEqual({ subject: 'Re: Plan' });
	});
});

describe('printOrder', () => {
	it('prints a conversation oldest first, whatever order it was resolved in', () => {
		expect(printOrder([REP, M1, M2])).toEqual(['m1', 'm2', 'rep']);
	});
});
