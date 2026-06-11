import { HttpResponse } from 'msw';
import { fixtureState, incrementFolderUnreadCount } from './fixtures.js';
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

function pushSendOutcome(type: SendNotification['type'], errorCode: string | null): void {
	const sendId = fixtureState.lastSendId;
	if (!sendId) return;
	const payload: SendNotification = {
		type,
		sendId,
		accountId: fixtureState.lastSendAccountId ?? 1,
		errorCode
	};
	const chunk = encoder.encode(
		[`event: ${type}`, `data: ${JSON.stringify(payload)}`, '', ''].join('\n')
	);
	for (const client of clients) {
		client.enqueue(chunk);
	}
}

export function pushSendCompleted(): void {
	pushSendOutcome('send_completed', null);
}

export function pushSendFailed(errorCode = 'SMTP_SEND_FAILED'): void {
	pushSendOutcome('send_failed', errorCode);
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
