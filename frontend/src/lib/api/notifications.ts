/**
 * SSE `/api/v1/notifications/stream` – real-time notifications about sync
 * progress.
 *
 * We deliberately do NOT use the native `EventSource`: it cannot attach the
 * `X-API-KEY` header and would also hit CORS under Tauri. Instead a custom
 * `SseClient` reads the stream over `httpFetch` (streaming response body),
 * which lets us send the API key in the `X-API-KEY` request header exactly
 * like every other call (see `loop()` below) and adds reconnect-with-backoff.
 *
 * Security: the API key travels in a header, never in the URL/query string —
 * the backend `ApiKeyFilter` authenticates from the header only, so the key
 * is kept out of access logs, browser history and Referer. Do not move it
 * into the URL "because EventSource would need it there"; that path does not
 * exist on the backend and would leak the key.
 */

import { buildApiUrl } from './client.js';
import { httpFetch } from './http.js';
import { toError } from './errors.js';
import { invalidateSession } from '$lib/stores/session.js';
import type { StreamNotification } from '$lib/types.js';

type Listener = (event: StreamNotification) => void;
type ErrorListener = (err: Error) => void;
type OpenListener = () => void;

// `thread_updated` is whitelisted so the SSE client does not drop the event
// payload at parse time. V0.1.0 has no UI consumer for it yet — the
// notifications store records and discards it, leaving room for the V0.2
// conversation UI to subscribe without a backend-side change.
const KNOWN_EVENT_TYPES = new Set([
	'sync_completed',
	'send_completed',
	'send_failed',
	'thread_updated'
]);
const INITIAL_BACKOFF_MS = 1000;
const MAX_BACKOFF_MS = 30_000;

interface SseClientOptions {
	/** Callback for a successful stream open (including after reconnect). */
	onOpen?: OpenListener;
	/** Error callback (reconnect happens automatically). */
	onError?: ErrorListener;
}

export class SseClient {
	private abort: AbortController | null = null;
	private listeners = new Set<Listener>();
	private closed = false;
	private backoff = INITIAL_BACKOFF_MS;

	constructor(private readonly options: SseClientOptions = {}) {}

	on(listener: Listener): () => void {
		this.listeners.add(listener);
		return () => this.listeners.delete(listener);
	}

	start(): void {
		if (this.abort) return;
		this.closed = false;
		void this.loop();
	}

	close(): void {
		this.closed = true;
		this.abort?.abort();
		this.abort = null;
	}

	private async loop(): Promise<void> {
		while (!this.closed) {
			this.abort = new AbortController();
			try {
				const { url, apiKey } = await buildApiUrl('/notifications/stream');
				const response = await httpFetch(url, {
					method: 'GET',
					headers: {
						Accept: 'text/event-stream',
						'X-API-KEY': apiKey
					},
					signal: this.abort.signal
				});

				if (!response.ok || !response.body) {
					if (response.status === 401) {
						invalidateSession();
					}
					throw new Error(`SSE stream failed: ${response.status}`);
				}

				this.options.onOpen?.();
				this.backoff = INITIAL_BACKOFF_MS;
				await this.readStream(response.body);
			} catch (err) {
				if (this.closed) return;
				const error = toError(err);
				this.options.onError?.(error);
				await this.sleep(this.backoff);
				this.backoff = Math.min(this.backoff * 2, MAX_BACKOFF_MS);
			}
		}
	}

	private async readStream(body: ReadableStream<Uint8Array>): Promise<void> {
		const reader = body.getReader();
		const decoder = new TextDecoder();
		let buffer = '';

		try {
			while (!this.closed) {
				const { value, done } = await reader.read();
				if (done) return;
				buffer = `${buffer}${decoder.decode(value, { stream: true })}`.replace(/\r\n/g, '\n');

				let boundary: number;
				while ((boundary = buffer.indexOf('\n\n')) !== -1) {
					const chunk = buffer.slice(0, boundary);
					buffer = buffer.slice(boundary + 2);
					this.handleChunk(chunk);
				}
			}
		} finally {
			reader.releaseLock();
		}
	}

	private handleChunk(chunk: string): void {
		let eventType = 'message';
		const dataLines: string[] = [];
		for (const line of chunk.split('\n')) {
			const normalizedLine = line.endsWith('\r') ? line.slice(0, -1) : line;
			if (normalizedLine.startsWith(':')) continue; // heartbeat / comment
			if (normalizedLine.startsWith('event:')) {
				eventType = normalizedLine.slice(6).trim();
			} else if (normalizedLine.startsWith('data:')) {
				dataLines.push(normalizedLine.slice(5).trim());
			}
		}
		if (!KNOWN_EVENT_TYPES.has(eventType) || dataLines.length === 0) return;
		const raw = dataLines.join('\n');
		try {
			const parsed = JSON.parse(raw) as StreamNotification;
			for (const listener of this.listeners) listener(parsed);
		} catch {
			/* ignore invalid payload */
		}
	}

	private sleep(ms: number): Promise<void> {
		return new Promise((resolve) => setTimeout(resolve, ms));
	}
}
