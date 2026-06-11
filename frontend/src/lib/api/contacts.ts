/** `/api/v1/accounts/{accountId}/contacts` — CRUD for contacts + their emails. */

import { api, apiRaw } from './client.js';
import type {
	BulkContactCreateRequest,
	BulkContactCreateResponse,
	BulkContactDeleteRequest,
	BulkContactDeleteResponse,
	ContactAutocompleteResponse,
	ContactCreateRequest,
	ContactEmailRequest,
	ContactEmailResponse,
	ContactMergeRequest,
	ContactPatchRequest,
	ContactResponse,
	EmailLabel,
	PagedResponse
} from '$lib/types.js';
import type { PageParams } from './mailRead.js';

export type ContactSort = 'name' | 'surname' | 'recent';

export function listContacts(
	accountId: number,
	options: PageParams & { q?: string; sort?: ContactSort; label?: EmailLabel } = {}
): Promise<PagedResponse<ContactResponse>> {
	const params: Record<string, string> = {};
	if (options.q) params.q = options.q;
	if (options.page != null) params.page = String(options.page);
	if (options.size != null) params.size = String(options.size);
	if (options.sort) params.sort = options.sort;
	if (options.label) params.label = options.label;
	return api.get(`/accounts/${accountId}/contacts`, { params });
}

export function autocompleteContacts(
	accountId: number,
	q: string,
	limit?: number
): Promise<ContactAutocompleteResponse[]> {
	const params: Record<string, string> = { q };
	if (limit != null) params.limit = String(limit);
	return api.get(`/accounts/${accountId}/contacts/autocomplete`, { params });
}

export function createContact(
	accountId: number,
	body: ContactCreateRequest
): Promise<ContactResponse> {
	return api.post<ContactResponse>(`/accounts/${accountId}/contacts`, body);
}

export function bulkCreateContacts(
	accountId: number,
	body: BulkContactCreateRequest
): Promise<BulkContactCreateResponse> {
	return api.post<BulkContactCreateResponse>(`/accounts/${accountId}/contacts/bulk`, body);
}

export function patchContact(
	accountId: number,
	contactId: number,
	body: ContactPatchRequest
): Promise<ContactResponse> {
	return api.patch<ContactResponse>(`/accounts/${accountId}/contacts/${contactId}`, body);
}

export function deleteContact(accountId: number, contactId: number): Promise<void> {
	return api.delete<void>(`/accounts/${accountId}/contacts/${contactId}`);
}

export function bulkDeleteContacts(
	accountId: number,
	body: BulkContactDeleteRequest
): Promise<BulkContactDeleteResponse> {
	return api.delete<BulkContactDeleteResponse>(`/accounts/${accountId}/contacts/bulk`, body);
}

/** Merges source contacts into the target — see `POST /accounts/{id}/contacts/{targetId}/merge`. */
export function mergeContacts(
	accountId: number,
	targetId: number,
	body: ContactMergeRequest
): Promise<ContactResponse> {
	return api.post<ContactResponse>(`/accounts/${accountId}/contacts/${targetId}/merge`, body);
}

export function addContactEmail(
	accountId: number,
	contactId: number,
	body: ContactEmailRequest
): Promise<ContactEmailResponse> {
	return api.post<ContactEmailResponse>(
		`/accounts/${accountId}/contacts/${contactId}/emails`,
		body
	);
}

export function deleteContactEmail(
	accountId: number,
	contactId: number,
	emailId: number
): Promise<void> {
	return api.delete<void>(`/accounts/${accountId}/contacts/${contactId}/emails/${emailId}`);
}

export function setPrimaryEmail(
	accountId: number,
	contactId: number,
	emailId: number
): Promise<ContactResponse> {
	return api.patch<ContactResponse>(
		`/accounts/${accountId}/contacts/${contactId}/emails/${emailId}/primary`
	);
}

/** Downloads all account contacts as vCard 4.0 (RFC 6350). */
export async function exportVCard(accountId: number): Promise<{ blob: Blob; filename: string }> {
	const response = await apiRaw(`/accounts/${accountId}/contacts/export.vcf`);
	const blob = await response.blob();
	const disposition = response.headers.get('content-disposition') ?? '';
	const match = /filename="([^"]+)"/.exec(disposition);
	const filename = match?.[1] ?? `contacts-${accountId}.vcf`;
	return { blob, filename };
}
