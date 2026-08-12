/**
 * `/api/v1/contact-labels` — user-defined contact labels
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

export function listContactLabels(): Promise<ContactLabelResponse[]> {
	return api.get<ContactLabelResponse[]>(`/contact-labels`);
}

/** 409 when the account already has a label with this name (case-insensitively). */
export function createContactLabel(body: ContactLabelCreateRequest): Promise<ContactLabelResponse> {
	return api.post<ContactLabelResponse>(`/contact-labels`, body);
}

export function renameContactLabel(
	labelId: number,
	body: ContactLabelUpdateRequest
): Promise<ContactLabelResponse> {
	return api.patch<ContactLabelResponse>(`/contact-labels/${labelId}`, body);
}

/** Removes the label everywhere; the contacts that carried it are kept. */
export function deleteContactLabel(labelId: number): Promise<void> {
	return api.delete<void>(`/contact-labels/${labelId}`);
}

/** Adds and/or removes labels across a contact selection in one request. */
export function assignContactLabels(
	body: ContactLabelAssignmentRequest
): Promise<ContactLabelAssignmentResponse> {
	return api.post<ContactLabelAssignmentResponse>(`/contact-labels/assignments`, body);
}
