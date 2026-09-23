import { setMessageFlag } from '$lib/api/mailAction.js';
import { markSeenLocally } from '$lib/stores/messages.js';
import {
	invalidateMessage,
	patchSelectedMessageDetail,
	type SelectedMessage
} from '$lib/stores/selectedMessage.js';

export type MessageSeenTracker = {
	selectedStableId: string | null;
	markingSeenFor: string | null;
	seenAttemptedFor: string | null;
};

export function resetSeenTrackerForSelection(
	nextStableId: string | null,
	tracker: MessageSeenTracker
): MessageSeenTracker {
	if (nextStableId === tracker.selectedStableId) {
		return tracker;
	}

	// A different message is open now, so what the user asked for about the last
	// one is spent — and whatever was once recorded about this one belongs to an
	// earlier open of it, not to this one.
	if (tracker.selectedStableId !== null) overruled.delete(tracker.selectedStableId);
	if (nextStableId !== null) overruled.delete(nextStableId);

	return {
		selectedStableId: nextStableId,
		markingSeenFor: null,
		seenAttemptedFor: null
	};
}

export function shouldMarkSelectedMessageSeen(
	message: SelectedMessage | null,
	tracker: Pick<MessageSeenTracker, 'markingSeenFor' | 'seenAttemptedFor'>
): message is SelectedMessage & { detail: NonNullable<SelectedMessage['detail']> } {
	return Boolean(
		message?.detail &&
		!message.detail.seen &&
		tracker.markingSeenFor !== message.stableId &&
		tracker.seenAttemptedFor !== message.stableId
	);
}

/**
 * Messages whose read state the user set while this module was marking them
 * read for having been opened, and what they asked for.
 *
 * Opening a message marks it read by itself, and that request is in flight for
 * as long as the backend takes. A user acting inside that window is acting on
 * the newer intention, so this records it rather than letting the open's own
 * request settle over it.
 */
const overruled = new Map<string, boolean>();

/**
 * Records that the user set the read state of `stableId` themselves, which the
 * open's own mark-as-read then yields to — whether it has already left the
 * ground or has not started yet. Called by every path that sets the flag on the
 * user's behalf; it costs one map entry for a message nobody is opening, and
 * the entry is dropped when the selection moves.
 */
export function overruleAutoMarkSeen(stableId: string, seen: boolean): void {
	overruled.set(stableId, seen);
}

export async function markMessageSeen(stableId: string): Promise<void> {
	/*
	 * The user got there first: they asked for unread before this open's own
	 * mark-as-read left the ground. Sending it would be asking the backend for
	 * the opposite of the newer intention, and the answer would then be written
	 * over their state on screen.
	 */
	if (overruled.get(stableId) === false) return;

	await setMessageFlag(stableId, 'seen', true);
	const asked = overruled.get(stableId);
	overruled.delete(stableId);

	if (asked !== false) {
		markSeenLocally(stableId, true);
		patchSelectedMessageDetail(stableId, { seen: true });
		invalidateMessage(stableId);
		return;
	}

	/*
	 * The user asked for unread while this was in flight. Their request and this
	 * one are on their own connections, so theirs may have reached the backend
	 * first and the flag would settle on what the open did rather than on what
	 * they asked for. Re-sent now that this one has landed, which orders it
	 * last. The local state already says unread — their path wrote it — so this
	 * only reconciles the server, and the cache entry goes with it.
	 */
	await setMessageFlag(stableId, 'seen', false);
	invalidateMessage(stableId);
}
