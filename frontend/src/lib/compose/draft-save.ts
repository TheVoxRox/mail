import {
	draftStableIdFromSaveResult,
	listDraftStableIds,
	resolveSavedDraftStableId,
	saveDraft as apiSaveDraft
} from '$lib/api/drafts.js';
import { buildDraftRequest, type ComposeDraft } from '$lib/compose/request.js';
import type { DraftRequest } from '$lib/types.js';

type ComposeDraftSaveResult = 'saved' | 'identity-pending';

function cloneDraftRequest(request: DraftRequest): DraftRequest {
	return JSON.parse(JSON.stringify(request)) as DraftRequest;
}

async function safeListDraftStableIds(accountId: number): Promise<string[]> {
	try {
		return await listDraftStableIds(accountId);
	} catch {
		return [];
	}
}

export class ComposeDraftSaveCoordinator {
	#replacesDraftId: string | null = null;
	#identityPending = false;
	#pendingBaselineIds: string[] = [];
	#pendingRequest: DraftRequest | null = null;

	get replacesDraftId(): string | null {
		return this.#replacesDraftId;
	}

	setReplacesDraftId(stableId: string | null): void {
		this.#replacesDraftId = stableId;
		this.#identityPending = false;
		this.#pendingRequest = null;
		this.#pendingBaselineIds = [];
	}

	async saveDraft(
		accountId: number,
		draft: ComposeDraft,
		options: { silent?: boolean } = {}
	): Promise<ComposeDraftSaveResult> {
		const { silent = false } = options;
		if (!(await this.#resolvePendingIdentity(accountId))) {
			return 'identity-pending';
		}

		const request = buildDraftRequest(draft);
		const baselineIds = await safeListDraftStableIds(accountId);
		const result = await apiSaveDraft(accountId, request, this.#replacesDraftId ?? undefined);
		await this.#updateIdentityAfterSave(accountId, request, result, baselineIds, silent);
		return 'saved';
	}

	async #resolvePendingIdentity(accountId: number): Promise<boolean> {
		if (!this.#identityPending || !this.#pendingRequest) return true;

		const stableId = await resolveSavedDraftStableId(accountId, this.#pendingRequest, {
			baselineIds: this.#pendingBaselineIds,
			attempts: 3,
			delayMs: 500
		});
		if (!stableId) return false;

		this.setReplacesDraftId(stableId);
		return true;
	}

	async #updateIdentityAfterSave(
		accountId: number,
		request: DraftRequest,
		result: Awaited<ReturnType<typeof apiSaveDraft>>,
		baselineIds: string[],
		silent: boolean
	): Promise<void> {
		const returnedStableId = draftStableIdFromSaveResult(result);
		if (returnedStableId) {
			this.setReplacesDraftId(returnedStableId);
			return;
		}

		const resolvedStableId = await resolveSavedDraftStableId(accountId, request, {
			baselineIds,
			attempts: silent ? 3 : 5,
			delayMs: 500
		});
		if (resolvedStableId) {
			this.setReplacesDraftId(resolvedStableId);
			return;
		}

		this.#identityPending = true;
		this.#pendingRequest = cloneDraftRequest(request);
		this.#pendingBaselineIds = baselineIds;
	}
}
