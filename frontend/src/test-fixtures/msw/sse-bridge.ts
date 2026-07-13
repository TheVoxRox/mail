import { HttpResponse } from 'msw';
import {
	draftToSummary,
	fixtureState,
	incrementFolderUnreadCount,
	removeMessageEverywhere
} from './fixtures.js';
import type { SendNotification, SyncNotification } from '$lib/types.js';

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
