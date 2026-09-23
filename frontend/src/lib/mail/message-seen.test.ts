import { beforeEach, describe, expect, it, vi } from 'vitest';

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
	overruleAutoMarkSeen,
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
		error: null,
		notFound: false
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
			error: null,
			notFound: false
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
	beforeEach(() => {
		// What the user asked for lives until the selection moves, so each test
		// starts from a fresh open of the message rather than from the last one's
		// record (the module keeps it, vitest's mock reset does not reach it).
		resetSeenTrackerForSelection('msg-42', {
			selectedStableId: 'other',
			markingSeenFor: null,
			seenAttemptedFor: null
		});
	});

	it('calls API, local cache update, detail patch and invalidate', async () => {
		await markMessageSeen('msg-42');
		expect(setMessageFlag).toHaveBeenCalledWith('msg-42', 'seen', true);
		expect(markSeenLocally).toHaveBeenCalledWith('msg-42', true);
		expect(patchSelectedMessageDetail).toHaveBeenCalledWith('msg-42', { seen: true });
		expect(invalidateMessage).toHaveBeenCalledWith('msg-42');
	});

	it('yields to a user who asked for unread while it was in flight', async () => {
		/*
		 * The window is the request itself: a reader who opens a message and puts
		 * it back as unread presses inside it. Patching the detail to read here
		 * would undo that on screen, and leaving the server alone would let the
		 * flag settle on whichever of the two requests the backend saw last - so
		 * the state the user asked for is re-sent once this one has landed.
		 */
		let finish = () => {};
		vi.mocked(setMessageFlag).mockImplementationOnce(
			() => new Promise<void>((resolve) => (finish = resolve))
		);
		const inFlight = markMessageSeen('msg-42');

		overruleAutoMarkSeen('msg-42', false);
		finish();
		await inFlight;

		expect(patchSelectedMessageDetail).not.toHaveBeenCalled();
		expect(markSeenLocally).not.toHaveBeenCalled();
		expect(vi.mocked(setMessageFlag).mock.calls).toEqual([
			['msg-42', 'seen', true],
			['msg-42', 'seen', false]
		]);
		expect(invalidateMessage).toHaveBeenCalledWith('msg-42');
	});

	it('marks read as usual when the user asked for read as well', async () => {
		// Ctrl+Q inside the same window asks for what the open was doing anyway;
		// nothing to undo, and no second request to send.
		let finish = () => {};
		vi.mocked(setMessageFlag).mockImplementationOnce(
			() => new Promise<void>((resolve) => (finish = resolve))
		);
		const inFlight = markMessageSeen('msg-42');

		overruleAutoMarkSeen('msg-42', true);
		finish();
		await inFlight;

		expect(patchSelectedMessageDetail).toHaveBeenCalledWith('msg-42', { seen: true });
		expect(vi.mocked(setMessageFlag).mock.calls).toEqual([['msg-42', 'seen', true]]);
	});

	it('does not run at all when the user got there first', async () => {
		// The other side of the same window: the press can also land before this
		// starts, and then sending it would ask the backend for the opposite of
		// what the user just asked for - and write the answer over their state.
		overruleAutoMarkSeen('msg-42', false);

		await markMessageSeen('msg-42');

		expect(setMessageFlag).not.toHaveBeenCalled();
		expect(patchSelectedMessageDetail).not.toHaveBeenCalled();
	});

	it('holds nothing against the next open of the same message', async () => {
		// The record belongs to one open: a message overruled, left and opened
		// again is marked read like any other.
		overruleAutoMarkSeen('msg-42', false);
		resetSeenTrackerForSelection('msg-42', {
			selectedStableId: null,
			markingSeenFor: null,
			seenAttemptedFor: null
		});

		await markMessageSeen('msg-42');

		expect(patchSelectedMessageDetail).toHaveBeenCalledWith('msg-42', { seen: true });
		expect(vi.mocked(setMessageFlag).mock.calls).toEqual([['msg-42', 'seen', true]]);
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
