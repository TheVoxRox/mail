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
