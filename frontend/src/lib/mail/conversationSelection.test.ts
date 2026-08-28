import { describe, expect, it } from 'vitest';
import { createConversationSelection } from './conversationSelection.js';

/*
 * The transition table of the two-level selection. Reachable inside the
 * component only by clicking, which is how "a conversation is never in both
 * levels" stayed an invariant asserted by a comment.
 *
 * Naming throughout: `rep` is the conversation's representative stableId, `m1`
 * and up are its other messages, and `actionable` is the folder-scoped set the
 * grid would act on — representative first, as selectableMessagesOf builds it.
 */
const REP = 'rep';
const THREAD = 'thread-1';
const ACTIONABLE = [REP, 'm1', 'm2'];

describe('createConversationSelection', () => {
	it('starts empty', () => {
		const selection = createConversationSelection();
		expect(selection.isEmpty).toBe(true);
		expect(selection.conversationCount).toBe(0);
		expect(selection.memberCount).toBe(0);
	});

	describe('whole conversations', () => {
		it('ticks and unticks by representative id', () => {
			const selection = createConversationSelection();
			selection.toggleConversation(REP, THREAD, true);
			expect(selection.isConversationSelected(REP)).toBe(true);
			expect(selection.conversationCount).toBe(1);
			selection.toggleConversation(REP, THREAD, false);
			expect(selection.isConversationSelected(REP)).toBe(false);
			expect(selection.isEmpty).toBe(true);
		});

		it('swallows the individual ticks of the thread when the parent is ticked', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			expect(selection.memberCount).toBe(1);
			selection.toggleConversation(REP, THREAD, true);
			expect(selection.memberCount).toBe(0);
			expect(selection.isConversationSelected(REP)).toBe(true);
		});

		it('leaves the ticks of another thread alone', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: 'rep-2',
				threadId: 'thread-2',
				actionableIds: ['rep-2', 'other'],
				memberId: 'other',
				isSelected: true
			});
			selection.toggleConversation(REP, THREAD, true);
			expect(selection.pickedMembersOf('thread-2')).toEqual(['other']);
		});

		it('handles a row with no thread id (a message the backfill has not reached)', () => {
			const selection = createConversationSelection();
			selection.toggleConversation(REP, null, true);
			expect(selection.isConversationSelected(REP)).toBe(true);
			expect(selection.isMixed(REP, null)).toBe(false);
			expect(selection.pickedMembersOf(null)).toEqual([]);
		});
	});

	describe('individual messages', () => {
		it('ticks one message without selecting the conversation', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			expect(selection.isMemberTicked('m1')).toBe(true);
			expect(selection.isConversationSelected(REP)).toBe(false);
			expect(selection.isMixed(REP, THREAD)).toBe(true);
		});

		it('rewrites a whole-conversation tick as per-message ticks when one is removed', () => {
			const selection = createConversationSelection();
			selection.toggleConversation(REP, THREAD, true);
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: false
			});
			// The rest of the thread survives, and the parent can say so.
			expect(selection.isConversationSelected(REP)).toBe(false);
			expect(selection.pickedMembersOf(THREAD)).toEqual([REP, 'm2']);
			expect(selection.isMixed(REP, THREAD)).toBe(true);
		});

		it('collapses back into the conversation once the last message is ticked', () => {
			const selection = createConversationSelection();
			for (const memberId of ACTIONABLE) {
				selection.toggleMember({
					repId: REP,
					threadId: THREAD,
					actionableIds: ACTIONABLE,
					memberId,
					isSelected: true
				});
			}
			expect(selection.isConversationSelected(REP)).toBe(true);
			expect(selection.memberCount).toBe(0);
			expect(selection.isMixed(REP, THREAD)).toBe(false);
		});

		it('does not collapse a thread whose only actionable message is the representative', () => {
			// A conversation whose other messages all live in another folder: ticking
			// the one message this view can act on must not read as "the whole
			// conversation", which would act on more than the row offers.
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: [REP],
				memberId: REP,
				isSelected: true
			});
			expect(selection.isConversationSelected(REP)).toBe(false);
			expect(selection.pickedMembersOf(THREAD)).toEqual([REP]);
		});

		it('never holds a conversation at both levels', () => {
			const selection = createConversationSelection();
			const bothLevels = (): boolean =>
				selection.isConversationSelected(REP) && selection.pickedMembersOf(THREAD).length > 0;
			// Every step names itself, so a break points at the transition that did it.
			const violations: string[] = [];
			for (const memberId of [...ACTIONABLE, 'm1', 'm2', REP, 'm2']) {
				selection.toggleMember({
					repId: REP,
					threadId: THREAD,
					actionableIds: ACTIONABLE,
					memberId,
					isSelected: !selection.isMemberTicked(memberId)
				});
				if (bothLevels()) violations.push(`toggleMember(${memberId})`);
			}
			// Land on a mixed thread before ticking the parent: absorbing per-message
			// ticks is the transition that can leave a conversation at both levels,
			// and a sequence that happens to end whole-selected never reaches it.
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: false
			});
			if (bothLevels()) violations.push('untick m1');
			selection.toggleConversation(REP, THREAD, true);
			if (bothLevels()) violations.push('toggleConversation');
			expect(violations).toEqual([]);
		});

		it('reports mixed only while the conversation itself is not ticked', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			expect(selection.isMixed(REP, THREAD)).toBe(true);
			selection.toggleConversation(REP, THREAD, true);
			expect(selection.isMixed(REP, THREAD)).toBe(false);
		});
	});

	describe('page-level commands', () => {
		it('select-all takes whole conversations and drops leftover ticks', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			selection.selectAll([REP, 'rep-2']);
			expect(selection.conversationCount).toBe(2);
			expect(selection.memberCount).toBe(0);
		});

		it('clear empties both levels', () => {
			const selection = createConversationSelection();
			selection.selectAll([REP]);
			selection.toggleMember({
				repId: 'rep-2',
				threadId: 'thread-2',
				actionableIds: ['rep-2', 'other'],
				memberId: 'other',
				isSelected: true
			});
			selection.clear();
			expect(selection.isEmpty).toBe(true);
		});
	});

	describe('pruning after a reload', () => {
		it('drops conversations the page no longer shows', () => {
			const selection = createConversationSelection();
			selection.selectAll([REP, 'gone']);
			selection.pruneToPage([REP], () => true);
			expect(selection.isConversationSelected(REP)).toBe(true);
			expect(selection.isConversationSelected('gone')).toBe(false);
		});

		it('drops ticks whose thread left the page', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			selection.pruneToPage([], (threadId) => threadId !== THREAD);
			expect(selection.memberCount).toBe(0);
		});

		it('drops ticks of threads that are no longer expanded', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			selection.toggleMember({
				repId: 'rep-2',
				threadId: 'thread-2',
				actionableIds: ['rep-2', 'other'],
				memberId: 'other',
				isSelected: true
			});
			selection.dropTicksOfCollapsed((threadId) => threadId === THREAD);
			expect(selection.pickedMembersOf(THREAD)).toEqual(['m1']);
			expect(selection.pickedMembersOf('thread-2')).toEqual([]);
		});

		it('drops a tick the refetched thread no longer holds', () => {
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			selection.dropTicksMissingFrom((_threadId, stableId) => stableId !== 'm1');
			expect(selection.memberCount).toBe(0);
		});

		it('keeps a tick whose thread has no members loaded', () => {
			// "Not loaded" is not "gone": resolveSelection re-checks the tick against
			// the thread it loads when the action runs.
			const selection = createConversationSelection();
			selection.toggleMember({
				repId: REP,
				threadId: THREAD,
				actionableIds: ACTIONABLE,
				memberId: 'm1',
				isSelected: true
			});
			selection.dropTicksMissingFrom(() => undefined);
			expect(selection.isMemberTicked('m1')).toBe(true);
		});
	});
});
