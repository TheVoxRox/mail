/**
 * `/api/v1/messages` (read-only) — folder listings, search, detail, message
 * content and attachment download (streaming).
 */

import { api, apiRaw } from './client.js';
import type {
	ConversationSummaryResponse,
	MailContentResponse,
	MailDetailResponse,
	MailSummaryResponse,
	PagedResponse
} from '$lib/types.js';

export interface PageParams {
	page?: number;
	size?: number;
}

function paramsFromPage({ page, size }: PageParams): Record<string, string> {
	const params: Record<string, string> = {};
	if (page != null) params.page = String(page);
	if (size != null) params.size = String(size);
	return params;
}

/** Lists messages in the given IMAP folder. `folderName` is the technical folder name. */
export function listMessages(
	accountId: number,
	folderName: string,
	page: PageParams = {}
): Promise<PagedResponse<MailSummaryResponse>> {
	return api.get(`/messages/account/${accountId}/folder`, {
		params: { folderRef: folderName, ...paramsFromPage(page) }
	});
}

/**
 * Lists the folder collapsed by conversation — one row per thread (its newest
 * message + folder-scoped counts). Server-grouped so a thread that spans a page
 * boundary still folds into a single row.
 */
export function listConversations(
	accountId: number,
	folderName: string,
	page: PageParams = {}
): Promise<PagedResponse<ConversationSummaryResponse>> {
	return api.get(`/messages/account/${accountId}/folder/conversations`, {
		params: { folderRef: folderName, ...paramsFromPage(page) }
	});
}

/** Full-text search across folders (subject/from/body). */
export function searchMessages(
	accountId: number,
	query: string,
	page: PageParams = {}
): Promise<PagedResponse<MailSummaryResponse>> {
	return api.get(`/messages/account/${accountId}/search`, {
		params: { q: query, ...paramsFromPage(page) }
	});
}

export function getMessageDetail(stableId: string): Promise<MailDetailResponse> {
	return api.get<MailDetailResponse>(`/messages/${encodeURIComponent(stableId)}`);
}

export function getMessageContent(stableId: string): Promise<MailContentResponse> {
	return api.get<MailContentResponse>(`/messages/${encodeURIComponent(stableId)}/content`);
}

/** Downloads an attachment as a Blob (X-API-KEY auth is handled by `apiRaw`). */
export async function downloadAttachment(
	stableId: string,
	partPath: string,
	fileName?: string
): Promise<Blob> {
	const response = await apiRaw(
		`/messages/${encodeURIComponent(stableId)}/attachments/${encodeURIComponent(partPath)}`,
		{ params: fileName ? { fileName } : undefined }
	);
	return response.blob();
}
