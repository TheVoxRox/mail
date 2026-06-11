/**
 * `/api/v1/messages` (actions) — changing IMAP flags, moving messages,
 * moving to trash and manually triggering an account sync.
 */

import { api } from './client.js';
import type { MailFlagType, MoveRequest } from '$lib/types.js';

export function setMessageFlag(
	stableId: string,
	type: MailFlagType,
	value: boolean
): Promise<void> {
	return api.patch<void>(`/messages/${encodeURIComponent(stableId)}/flags`, undefined, {
		params: { type, value: String(value) }
	});
}

export function deleteMessage(stableId: string): Promise<void> {
	return api.delete<void>(`/messages/${encodeURIComponent(stableId)}`);
}

export function moveMessage(stableId: string, body: MoveRequest): Promise<void> {
	return api.post<void>(`/messages/${encodeURIComponent(stableId)}/move`, body);
}

/** Triggers incremental sync of all folders for the account (202 Accepted, async). */
export function triggerAccountSync(accountId: number): Promise<void> {
	return api.post<void>(`/messages/account/${accountId}/sync`);
}
