/**
 * `/api/v1/accounts/{accountId}/contact-labels` — user-defined contact labels
 * and their bulk assignment. Separate from `contacts.ts` because a label lives
 * on its own, independent of any contact carrying it.
 */

import { api } from './client.js';
import type {
	ContactLabelAssignmentRequest,
	ContactLabelAssignmentResponse,
	ContactLabelCreateRequest,
	ContactLabelResponse,
	ContactLabelUpdateRequest
} from '$lib/types.js';

export function listContactLabels(accountId: number): Promise<ContactLabelResponse[]> {
	return api.get<ContactLabelResponse[]>(`/accounts/${accountId}/contact-labels`);
}

/** 409 when the account already has a label with this name (case-insensitively). */
export function createContactLabel(
	accountId: number,
	body: ContactLabelCreateRequest
): Promise<ContactLabelResponse> {
	return api.post<ContactLabelResponse>(`/accounts/${accountId}/contact-labels`, body);
}

export function renameContactLabel(
	accountId: number,
	labelId: number,
	body: ContactLabelUpdateRequest
): Promise<ContactLabelResponse> {
	return api.patch<ContactLabelResponse>(`/accounts/${accountId}/contact-labels/${labelId}`, body);
}

/** Removes the label everywhere; the contacts that carried it are kept. */
export function deleteContactLabel(accountId: number, labelId: number): Promise<void> {
	return api.delete<void>(`/accounts/${accountId}/contact-labels/${labelId}`);
}

/** Adds and/or removes labels across a contact selection in one request. */
export function assignContactLabels(
	accountId: number,
	body: ContactLabelAssignmentRequest
): Promise<ContactLabelAssignmentResponse> {
	return api.post<ContactLabelAssignmentResponse>(
		`/accounts/${accountId}/contact-labels/assignments`,
		body
	);
}
