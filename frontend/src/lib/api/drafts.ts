/** `/api/v1/accounts/{accountId}/drafts` — drafts (async save + sending). */

import { api } from './client.js';
import type { DraftRequest, DraftSaveAcceptedResponse, SendAcceptedResponse } from '$lib/types.js';

/**
 * Asynchronously saves a draft. If `replaces` is set, the old revision is
 * deleted after the new one is saved (update semantics). The 202 carries the
 * deterministic stableId the draft persists under — valid immediately for
 * `replaces=` chaining and `?draft=` reopen.
 */
export function saveDraft(
	accountId: number,
	body: DraftRequest,
	replaces?: string
): Promise<DraftSaveAcceptedResponse> {
	return api.post<DraftSaveAcceptedResponse>(`/accounts/${accountId}/drafts`, body, {
		params: replaces ? { replaces } : undefined
	});
}

/**
 * Asynchronously sends an existing draft as-is: the backend re-sends the
 * original MIME (preserving attachments and threading headers) and then
 * hard-deletes it from the Drafts folder. Returns 202 Accepted.
 */
export function sendDraft(accountId: number, stableId: string): Promise<SendAcceptedResponse> {
	return api.post<SendAcceptedResponse>(
		`/accounts/${accountId}/drafts/${encodeURIComponent(stableId)}/send`
	);
}
