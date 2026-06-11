/** `/api/v1/accounts/{accountId}/drafts` — drafts (async save + listing). */

import { api } from './client.js';
import type {
	DraftRequest,
	MailSummaryResponse,
	PagedResponse,
	SendAcceptedResponse
} from '$lib/types.js';
import type { PageParams } from './mailRead.js';

interface SaveDraftResult {
	stableId?: string | null;
	draftId?: string | null;
	id?: string | number | null;
}

interface ResolveSavedDraftOptions {
	baselineIds?: Iterable<string>;
	attempts?: number;
	delayMs?: number;
}

/**
 * Asynchronously saves a draft. If `replaces` is set, the old draft is
 * deleted (update semantics). Backend returns 202 Accepted.
 */
export async function saveDraft(
	accountId: number,
	body: DraftRequest,
	replaces?: string
): Promise<SaveDraftResult> {
	const result = await api.post<SaveDraftResult | string | void>(
		`/accounts/${accountId}/drafts`,
		body,
		{
			params: replaces ? { replaces } : undefined
		}
	);
	if (typeof result === 'string') return { stableId: result };
	return result ?? {};
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

function listDrafts(
	accountId: number,
	page: PageParams = {}
): Promise<PagedResponse<MailSummaryResponse>> {
	const params: Record<string, string> = {};
	if (page.page != null) params.page = String(page.page);
	if (page.size != null) params.size = String(page.size);
	return api.get(`/accounts/${accountId}/drafts`, { params });
}

function delay(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

function resultStableId(result: SaveDraftResult): string | null {
	const value = result.stableId ?? result.draftId ?? result.id;
	if (value == null) return null;
	const stableId = String(value).trim();
	return stableId || null;
}

function matchesDraft(summary: MailSummaryResponse, draft: DraftRequest): boolean {
	const expectedSubject = draft.subject?.trim();
	const hasExpectedAttachments = Boolean(draft.attachments?.length);
	if (summary.hasAttachments !== hasExpectedAttachments) return false;
	if (!expectedSubject) return true;
	return summary.subject === expectedSubject;
}

export function draftStableIdFromSaveResult(result: SaveDraftResult): string | null {
	return resultStableId(result);
}

export async function listDraftStableIds(accountId: number): Promise<string[]> {
	const page = await listDrafts(accountId, { page: 0, size: 50 });
	return page.content.map((draft) => draft.stableId);
}

export async function resolveSavedDraftStableId(
	accountId: number,
	draft: DraftRequest,
	options: ResolveSavedDraftOptions = {}
): Promise<string | null> {
	const { baselineIds = [], attempts = 5, delayMs = 500 } = options;
	const baseline = new Set([...baselineIds]);

	for (let attempt = 0; attempt < attempts; attempt += 1) {
		const page = await listDrafts(accountId, { page: 0, size: 50 });
		const match = page.content.find(
			(summary) => !baseline.has(summary.stableId) && matchesDraft(summary, draft)
		);
		if (match) return match.stableId;
		if (attempt < attempts - 1) {
			await delay(delayMs);
		}
	}

	return null;
}
