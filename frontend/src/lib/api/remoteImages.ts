/**
 * `/api/v1/remote-images/allowlist` — per-sender opt-in for loading remote images
 * in HTML mail bodies. Remote images are blocked by default (tracking-pixel
 * defense); trusting a sender here makes that sender's messages auto-load their
 * remote https images. See docs/CONTENT_RENDERING_AUDIT.md finding F2.
 */

import { api } from './client.js';

/** Trusts a sender: its messages then auto-load remote images. Idempotent. */
export function allowSenderRemoteImages(accountId: number, senderEmail: string): Promise<void> {
	return api.put<void>('/remote-images/allowlist', { accountId, senderEmail });
}

/** The account's trusted senders, in address order. */
export function listRemoteImageSenders(accountId: number): Promise<string[]> {
	return api.get<string[]>('/remote-images/allowlist', {
		params: { accountId: String(accountId) }
	});
}

/**
 * Stops trusting a sender: its messages open with their images blocked again.
 * Idempotent. Both values are query parameters and there is no body, which is
 * how `RemoteImageAllowlistController.disallow` reads them; the request goes to
 * the sidecar on this machine and nowhere else.
 */
export function disallowSenderRemoteImages(accountId: number, senderEmail: string): Promise<void> {
	return api.delete<void>('/remote-images/allowlist', undefined, {
		params: { accountId: String(accountId), senderEmail }
	});
}
