import { describe, expect, it, vi } from 'vitest';

vi.mock('$lib/api/mailAction.js', () => ({
	setMessageFlag: vi.fn().mockResolvedValue(undefined)
}));

vi.mock('$lib/stores/messages.js', () => ({
	markSeenLocally: vi.fn()
}));

vi.mock('$lib/stores/selectedMessage.js', () => ({
	invalidateMessage: vi.fn(),
	patchSelectedMessageDetail: vi.fn()
}));

import {
	markMessageSeen,
	resetSeenTrackerForSelection,
	shouldMarkSelectedMessageSeen,
	type MessageSeenTracker
} from './message-seen.js';
import { setMessageFlag } from '$lib/api/mailAction.js';
import { markSeenLocally } from '$lib/stores/messages.js';
import {
	invalidateMessage,
	patchSelectedMessageDetail,
	type SelectedMessage
} from '$lib/stores/selectedMessage.js';

const idleTracker: MessageSeenTracker = {
	selectedStableId: null,
	markingSeenFor: null,
	seenAttemptedFor: null
};

function makeSelected(stableId: string, seen: boolean): SelectedMessage {
	return {
		stableId,
		// minimal detail with the field shouldMarkSelectedMessageSeen reads
		detail: { seen } as unknown as SelectedMessage['detail'],
		content: null,
		loading: false,
		error: null
	};
}

describe('resetSeenTrackerForSelection', () => {
	it('returns the same tracker when stableId is unchanged', () => {
		const tracker: MessageSeenTracker = {
			selectedStableId: 'A',
			markingSeenFor: 'A',
			seenAttemptedFor: 'A'
		};
		expect(resetSeenTrackerForSelection('A', tracker)).toBe(tracker);
	});

	it('resets marking/attempted when selection changes', () => {
		const tracker: MessageSeenTracker = {
			selectedStableId: 'A',
			markingSeenFor: 'A',
			seenAttemptedFor: 'A'
		};
		expect(resetSeenTrackerForSelection('B', tracker)).toEqual({
			selectedStableId: 'B',
			markingSeenFor: null,
			seenAttemptedFor: null
		});
	});

	it('handles transition to null selection', () => {
		const tracker: MessageSeenTracker = {
			selectedStableId: 'A',
			markingSeenFor: null,
			seenAttemptedFor: 'A'
		};
		expect(resetSeenTrackerForSelection(null, tracker)).toEqual({
			selectedStableId: null,
			markingSeenFor: null,
			seenAttemptedFor: null
		});
	});
});

describe('shouldMarkSelectedMessageSeen', () => {
	it('false when message is null', () => {
		expect(shouldMarkSelectedMessageSeen(null, idleTracker)).toBe(false);
	});

	it('false when detail is missing (still loading)', () => {
		const msg: SelectedMessage = {
			stableId: 'A',
			detail: null,
			content: null,
			loading: true,
			error: null
		};
		expect(shouldMarkSelectedMessageSeen(msg, idleTracker)).toBe(false);
	});

	it('false when already seen', () => {
		expect(shouldMarkSelectedMessageSeen(makeSelected('A', true), idleTracker)).toBe(false);
	});

	it('true for unseen message in idle tracker', () => {
		expect(shouldMarkSelectedMessageSeen(makeSelected('A', false), idleTracker)).toBe(true);
	});

	it('false when marking is in flight for the same stableId', () => {
		expect(
			shouldMarkSelectedMessageSeen(makeSelected('A', false), {
				markingSeenFor: 'A',
				seenAttemptedFor: null
			})
		).toBe(false);
	});

	it('false when a previous attempt already happened for the same stableId', () => {
		expect(
			shouldMarkSelectedMessageSeen(makeSelected('A', false), {
				markingSeenFor: null,
				seenAttemptedFor: 'A'
			})
		).toBe(false);
	});

	it('true when in-flight marking is for a different message', () => {
		expect(
			shouldMarkSelectedMessageSeen(makeSelected('A', false), {
				markingSeenFor: 'B',
				seenAttemptedFor: 'B'
			})
		).toBe(true);
	});
});

describe('markMessageSeen', () => {
	it('calls API, local cache update, detail patch and invalidate', async () => {
		await markMessageSeen('msg-42');
		expect(setMessageFlag).toHaveBeenCalledWith('msg-42', 'seen', true);
		expect(markSeenLocally).toHaveBeenCalledWith('msg-42', true);
		expect(patchSelectedMessageDetail).toHaveBeenCalledWith('msg-42', { seen: true });
		expect(invalidateMessage).toHaveBeenCalledWith('msg-42');
	});

	it('propagates API errors before touching local state', async () => {
		// vitest.config has clearMocks:true, so call history is wiped between
		// tests automatically — no need for the per-test mockClear() trio.
		vi.mocked(setMessageFlag).mockRejectedValueOnce(new Error('boom'));
		await expect(markMessageSeen('msg-42')).rejects.toThrow('boom');
		expect(markSeenLocally).not.toHaveBeenCalled();
		expect(patchSelectedMessageDetail).not.toHaveBeenCalled();
		expect(invalidateMessage).not.toHaveBeenCalled();
	});
});
