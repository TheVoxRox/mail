/**
 * `/api/v1/contacts` — CRUD for contacts + their emails.
 *
 * There is one address book for the whole application, not one per mail
 * account, so nothing here is account-scoped. The single exception is
 * `autocomplete`, which takes the composing mailbox so the backend knows whose
 * correspondence history to blend in with the (global) address book.
 */

import { api, apiRaw } from './client.js';
import type {
	BulkContactCreateRequest,
	BulkContactCreateResponse,
	BulkContactDeleteRequest,
	BulkContactDeleteResponse,
	ContactAutocompleteResponse,
	ContactCountsResponse,
	ContactCreateRequest,
	ContactEmailResponse,
	ContactLabelCountResponse,
	ContactLabelResponse,
	ContactMergeRequest,
	ContactResponse,
	ContactUpdateRequest,
	PagedResponse
} from '$lib/types.js';
import type { PageParams } from './mailRead.js';

export type ContactSort = 'name' | 'surname' | 'recent';

/*
 * ── What the wire may actually carry ──────────────────────────────────────
 *
 * The contract says the list fields are always there — they are Java `List`s
 * and Jackson never omits them — so `ContactResponse` declares them required
 * and the UI reads them without a guard, once per row. A backend built before
 * a field existed answers 200 without the key, and then one absent list takes
 * the whole view down: the pre-labels sidecar sent contacts with no `labels`,
 * `c.labels.length` threw while rendering and the contact list stayed blank
 * with no message (#252). Its counts endpoint answered in the pre-labels
 * shape for the same reason.
 *
 * The version handshake is what keeps a mismatched pair from running at all;
 * these types are what keeps the damage proportionate when something slips
 * past it — a contact with no labels, not a dead page.
 */
type WireContactResponse = Omit<ContactResponse, 'emails' | 'labels'> & {
	emails?: ContactEmailResponse[] | null;
	labels?: ContactLabelResponse[] | null;
};

type WireContactPage = Omit<PagedResponse<ContactResponse>, 'content'> & {
	content?: WireContactResponse[] | null;
};

type WireContactCountsResponse = Omit<ContactCountsResponse, 'labels'> & {
	labels?: ContactLabelCountResponse[] | null;
};

function normalizeContact(contact: WireContactResponse): ContactResponse {
	return { ...contact, emails: contact.emails ?? [], labels: contact.labels ?? [] };
}

function normalizeContactPage(page: WireContactPage): PagedResponse<ContactResponse> {
	return { ...page, content: (page.content ?? []).map(normalizeContact) };
}

export async function listContacts(
	options: PageParams & { q?: string; sort?: ContactSort; labelId?: number } = {}
): Promise<PagedResponse<ContactResponse>> {
	const params: Record<string, string> = {};
	if (options.q) params.q = options.q;
	if (options.page != null) params.page = String(options.page);
	if (options.size != null) params.size = String(options.size);
	if (options.sort) params.sort = options.sort;
	if (options.labelId != null) params.labelId = String(options.labelId);
	return normalizeContactPage(await api.get<WireContactPage>(`/contacts`, { params }));
}

export async function getContactCounts(): Promise<ContactCountsResponse> {
	const counts = await api.get<WireContactCountsResponse>(`/contacts/counts`);
	return { ...counts, labels: counts.labels ?? [] };
}

/**
 * `accountId` is the mailbox being composed from. It selects whose
 * correspondence history is blended into the suggestions; the address book half
 * is global and ignores it.
 */
export function autocompleteContacts(
	accountId: number,
	q: string,
	limit?: number
): Promise<ContactAutocompleteResponse[]> {
	const params: Record<string, string> = { accountId: String(accountId), q };
	if (limit != null) params.limit = String(limit);
	return api.get(`/contacts/autocomplete`, { params });
}

export async function createContact(body: ContactCreateRequest): Promise<ContactResponse> {
	return normalizeContact(await api.post<WireContactResponse>(`/contacts`, body));
}

export function bulkCreateContacts(
	body: BulkContactCreateRequest
): Promise<BulkContactCreateResponse> {
	return api.post<BulkContactCreateResponse>(`/contacts/bulk`, body);
}

export async function getContact(contactId: number): Promise<ContactResponse> {
	return normalizeContact(await api.get<WireContactResponse>(`/contacts/${contactId}`));
}

/** Full contact replace (PUT) — name/surname/note plus the whole e-mail list; the first e-mail becomes primary. */
export async function updateContact(
	contactId: number,
	body: ContactUpdateRequest
): Promise<ContactResponse> {
	return normalizeContact(await api.put<WireContactResponse>(`/contacts/${contactId}`, body));
}

export function deleteContact(contactId: number): Promise<void> {
	return api.delete<void>(`/contacts/${contactId}`);
}

export function bulkDeleteContacts(
	body: BulkContactDeleteRequest
): Promise<BulkContactDeleteResponse> {
	return api.delete<BulkContactDeleteResponse>(`/contacts/bulk`, body);
}

/** Merges source contacts into the target — see `POST /contacts/{targetId}/merge`. */
export async function mergeContacts(
	targetId: number,
	body: ContactMergeRequest
): Promise<ContactResponse> {
	return normalizeContact(await api.post<WireContactResponse>(`/contacts/${targetId}/merge`, body));
}

/** Downloads the whole address book as vCard 4.0 (RFC 6350). */
export async function exportVCard(): Promise<{ blob: Blob; filename: string }> {
	const response = await apiRaw(`/contacts/export.vcf`);
	const blob = await response.blob();
	const disposition = response.headers.get('content-disposition') ?? '';
	const match = /filename="([^"]+)"/.exec(disposition);
	const filename = match?.[1] ?? `contacts.vcf`;
	return { blob, filename };
}
