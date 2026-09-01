import { HttpResponse } from 'msw';
import {
	draftToSummary,
	fixtureState,
	incrementFolderUnreadCount,
	removeMessageEverywhere,
	setAccountSyncError
} from './fixtures.js';
import type {
	SendNotification,
	SyncCycleNotification,
	SyncNotification,
	SyncStatusNotification
} from '$lib/types.js';

type StreamController = ReadableStreamDefaultController<Uint8Array>;

const encoder = new TextEncoder();
const clients = new Set<StreamController>();

type SseLineEnding = 'lf' | 'crlf';

function encodeEvent(event: SyncNotification, lineEnding: SseLineEnding = 'lf'): Uint8Array {
	const newline = lineEnding === 'crlf' ? '\r\n' : '\n';
	return encoder.encode(
		[`event: sync_completed`, `data: ${JSON.stringify(event)}`, '', ''].join(newline)
	);
}

export function pushSyncCompleted(
	event: Partial<SyncNotification> & Pick<SyncNotification, 'accountId' | 'folderName'>
): void {
	const payload: SyncNotification = {
		type: 'sync_completed',
		newMessagesCount: 1,
		timestamp: new Date().toISOString(),
		...event
	};
	incrementFolderUnreadCount(payload.accountId, payload.folderName, payload.newMessagesCount);
	const chunk = encodeEvent(payload);
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

export function pushSyncCompletedCrLf(
	event: Partial<SyncNotification> & Pick<SyncNotification, 'accountId' | 'folderName'>
): void {
	const payload: SyncNotification = {
		type: 'sync_completed',
		newMessagesCount: 1,
		timestamp: new Date().toISOString(),
		...event
	};
	incrementFolderUnreadCount(payload.accountId, payload.folderName, payload.newMessagesCount);
	const chunk = encodeEvent(payload, 'crlf');
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

function pushSendOutcome(
	type: SendNotification['type'],
	errorCode: string | null,
	recoveryDraftStableId: string | null = null
): void {
	const sendId = fixtureState.lastSendId;
	if (!sendId) return;
	const payload: SendNotification = {
		type,
		sendId,
		accountId: fixtureState.lastSendAccountId ?? 1,
		errorCode,
		recoveryDraftStableId
	};
	const chunk = encoder.encode(
		[`event: ${type}`, `data: ${JSON.stringify(payload)}`, '', ''].join('\n')
	);
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

export function pushSendCompleted(): void {
	// Mirror of the backend contract: the superseded draft (or the sent draft
	// itself for a draft-send) is hard-deleted only after successful delivery.
	const superseded = fixtureState.lastSendSupersedesDraftId;
	if (superseded) {
		removeMessageEverywhere(superseded);
		fixtureState.lastSendSupersedesDraftId = null;
	}
	pushSendOutcome('send_completed', null);
}

export function pushSendFailed(errorCode = 'SMTP_SEND_FAILED'): void {
	// B2: a failed send of a brand-new message (no superseding draft) parks the
	// content as a recovery draft and announces its id with the outcome.
	let recoveryDraftStableId: string | null = null;
	const accountId = fixtureState.lastSendAccountId ?? 1;
	if (fixtureState.lastSendSupersedesDraftId == null && fixtureState.lastSendMailRequest) {
		recoveryDraftStableId = draftToSummary(accountId, fixtureState.lastSendMailRequest).stableId;
	}
	fixtureState.lastSendMailRequest = null;
	pushSendOutcome('send_failed', errorCode, recoveryDraftStableId);
}

/**
 * Sync error-state transition. Also writes the standing error onto the account
 * fixture, because the client refetches accounts to render the failure text —
 * pushing the event alone would produce the generic fallback copy and the test
 * would not exercise the real path.
 */
export function pushSyncFailed(
	accountId = 1,
	errorCode = 'MAIL_SYNC_FOLDER_FAILED',
	// In the app the backend localizes this (AccountMapper); the fixture default
	// stays English so this shared file needs no diacritics whitelist entry.
	// Tests that assert on the rendered detail pass their own string.
	detail = 'Folder sync INBOX failed: MessagingException: timeout'
): void {
	setAccountSyncError(accountId, errorCode, detail);
	pushSyncStatus({
		type: 'sync_failed',
		accountId,
		errorCode,
		timestamp: new Date().toISOString()
	});
}

export function pushSyncRecovered(accountId = 1): void {
	setAccountSyncError(accountId, null, null);
	pushSyncStatus({
		type: 'sync_recovered',
		accountId,
		errorCode: null,
		timestamp: new Date().toISOString()
	});
}

/**
 * Whether at least one client is subscribed to the stream.
 *
 * A push into an empty client set is silently dropped, and `waitForShell` only
 * proves the SSR shell exists — the `EventSource` subscribe happens later, after
 * hydration. Tests that drive a notification must gate on this instead of on
 * rendering, or they pass on timing luck and fail on a slower run.
 */
export function syncStreamConnected(): boolean {
	return clients.size > 0;
}

/**
 * End of a user-triggered pass. Unlike {@link pushSyncCompleted} it moves no
 * unread counts on purpose: the case it exists for is the pass that downloaded
 * nothing and therefore emitted no folder event at all.
 */
export function pushSyncCycleCompleted(
	accountId = 1,
	newMessagesCount = 0,
	allFoldersSynced = true
): void {
	const payload: SyncCycleNotification = {
		type: 'sync_cycle_completed',
		accountId,
		newMessagesCount,
		allFoldersSynced,
		timestamp: new Date().toISOString()
	};
	const chunk = encoder.encode(
		[`event: sync_cycle_completed`, `data: ${JSON.stringify(payload)}`, '', ''].join('\n')
	);
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

function pushSyncStatus(payload: SyncStatusNotification): void {
	const chunk = encoder.encode(
		[`event: ${payload.type}`, `data: ${JSON.stringify(payload)}`, '', ''].join('\n')
	);
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

export function openSyncStream(): Response {
	let activeController: StreamController | null = null;
	const stream = new ReadableStream<Uint8Array>({
		start(controller) {
			activeController = controller;
			clients.add(controller);
			controller.enqueue(encoder.encode(': connected\n\n'));
		},
		cancel() {
			if (activeController) clients.delete(activeController);
		}
	});

	return new HttpResponse(stream, {
		headers: {
			'Content-Type': 'text/event-stream',
			'Cache-Control': 'no-cache',
			Connection: 'keep-alive'
		}
	});
}

export function closeSyncStreams(): void {
	for (const client of clients) {
		client.close();
	}
	clients.clear();
}
