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

export async function markMessageSeen(stableId: string): Promise<void> {
	await setMessageFlag(stableId, 'seen', true);
	markSeenLocally(stableId, true);
	patchSelectedMessageDetail(stableId, { seen: true });
	invalidateMessage(stableId);
}
