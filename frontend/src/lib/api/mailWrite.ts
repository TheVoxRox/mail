/**
 * `/api/v1/messages` (write) — sending, preparing reply / forward templates.
 * Backend returns 202 Accepted for `send`; the actual delivery runs async.
 */

import { api } from './client.js';
import type { MailRequest, SendAcceptedResponse } from '$lib/types.js';

/**
 * With `supersedesDraftId`, the draft the message was edited from is
 * hard-deleted by the backend only AFTER successful delivery — the client
 * must not delete it itself on the 202 (a failed send keeps the draft).
 */
export function sendMail(
	accountId: number,
	body: MailRequest,
	supersedesDraftId?: string
): Promise<SendAcceptedResponse> {
	return api.post<SendAcceptedResponse>(`/messages/account/${accountId}/send`, body, {
		params: supersedesDraftId ? { supersedesDraftId } : undefined
	});
}

export function prepareReply(stableId: string, all = false): Promise<MailRequest> {
	return api.get<MailRequest>(`/messages/${encodeURIComponent(stableId)}/reply`, {
		params: { all: String(all) }
	});
}

export function prepareForward(stableId: string): Promise<MailRequest> {
	return api.get<MailRequest>(`/messages/${encodeURIComponent(stableId)}/forward`);
}
