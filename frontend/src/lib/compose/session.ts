/**
 * Compose lifecycle state that must outlive the composer component
 * (docs/COMPOSE_DRAFT_LIFECYCLE.md, F1+F2): the draft identity, a
 * single-flight save queue with a dirty-check, and two fingerprints.
 *
 * Two baselines, because "safe to lose" and "already persisted" differ:
 *  - **baseline** — the post-prefill content. Editing back to it is a no-op
 *    (avoids the signature-swap / prefill zombie draft, finding 1), and the
 *    leave guard treats it as safe (a reply's quoted text is regenerable).
 *  - **saved** — content actually written to the Drafts folder ('' until the
 *    first save). Skipping a save only when the content equals *this* means a
 *    manual "Save draft" on an untouched reply still persists it, while a
 *    re-save of unchanged already-saved content is a cheap no-op.
 *
 * Saves are debounced (~1 s until the compose is persisted, 3 s steady),
 * serialized and coalesced to one trailing save of the latest content; the
 * `replaces` chain always uses the stableId the previous save returned (minted
 * at save time by the backend, B1). Because the module — not the component —
 * owns the queue, an in-flight save finishes and keeps its identity even after
 * the composer unmounts.
 */

import { writable, type Readable } from 'svelte/store';
import { saveDraft as apiSaveDraft } from '$lib/api/drafts.js';
import { deleteMessage } from '$lib/api/mailAction.js';
import { toErrorMessage } from '$lib/api/errors.js';
import { buildDraftRequest, draftFingerprint, type ComposeDraft } from './request.js';

export type ComposeFlushOutcome = 'saved' | 'skipped' | 'blocked' | 'failed';

export interface ComposeSessionState {
	/** Identity of the draft chain; set by prefill (`?draft=`) or the first save. */
	draftStableId: string | null;
	/** Post-prefill content fingerprint — "safe to lose" for the leave guard. */
	baselineFingerprint: string;
	/** Fingerprint of the last successfully saved content ('' = nothing saved). */
	savedFingerprint: string;
	saving: boolean;
	savedAt: Date | null;
	/** Message of the most recent failed save; cleared by the next change or success. */
	saveError: string | null;
}

export interface ComposeSessionBeginOptions {
	/** May be null while accounts are not ready yet; see the fingerprint rebase below. */
	accountId: number | null;
	draft: ComposeDraft;
	/** stableId of an opened `?draft=`, or null for a fresh compose. */
	draftStableId: string | null;
	/** Debounced autosaves are deferred while true (an explicit flush is not). */
	isSuspended?: () => boolean;
}

const FIRST_SAVE_DEBOUNCE_MS = 1000;
const STEADY_SAVE_DEBOUNCE_MS = 3000;
/** One retry for the discard delete: the async append may not have landed yet. */
const DISCARD_RETRY_DELAY_MS = 1500;

/** The "never create an empty draft" floor (an existing draft may be emptied). */
function hasComposeContent(draft: ComposeDraft): boolean {
	return Boolean(
		draft.to || draft.cc || draft.bcc || draft.subject || draft.body || draft.attachments.length > 0
	);
}

function fingerprintOf(accountId: number | null, draftFp: string): string {
	return JSON.stringify({ accountId, draft: draftFp });
}

/**
 * Fingerprint of what a save would persist and where. Exported so the
 * component derives its unsaved-changes flag with the same function.
 */
export function composeSnapshotFingerprint(accountId: number | null, draft: ComposeDraft): string {
	return fingerprintOf(accountId, draftFingerprint(draft));
}

interface DraftSnapshot {
	accountId: number;
	draft: ComposeDraft;
}

function delay(ms: number): Promise<void> {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

export interface ComposeSession extends Readable<ComposeSessionState> {
	begin(options: ComposeSessionBeginOptions): void;
	/** Mirror of the latest form state; schedules a debounced autosave when dirty. */
	noteChange(accountId: number | null, draft: ComposeDraft): void;
	/** Autosaves are held while an attachment read is in flight; settling retries. */
	setAttachmentReading(reading: boolean): void;
	/** Save now (manual save / save-before-leave). Coalesces with an in-flight save. */
	flush(): Promise<ComposeFlushOutcome>;
	/**
	 * Cancels any pending autosave, waits for an in-flight save to settle and
	 * returns the final draft identity — the send's `supersedesDraftId`.
	 * Deliberately no trailing save: a failed send of a never-saved compose is
	 * covered by the backend recovery draft (B2), and for an autosaved compose
	 * the last revision is at most one debounce stale.
	 */
	prepareForSend(): Promise<string | null>;
	/**
	 * Discard, as decided: deletes the persisted draft entirely (an autosaved
	 * new compose and an opened `?draft=` alike) and ends the session. Runs in
	 * the background — safe to navigate away immediately. Resolves with the
	 * deleted stableId, or null when nothing was persisted.
	 */
	discard(): Promise<string | null>;
	/** Ends the session (after a send 202): in-flight results are dropped. */
	end(): void;
	/** Component unmount: stop future autosaves but let an in-flight save finish. */
	detach(): void;
}

export function createComposeSession(): ComposeSession {
	const store = writable<ComposeSessionState>({
		draftStableId: null,
		baselineFingerprint: '',
		savedFingerprint: '',
		saving: false,
		savedAt: null,
		saveError: null
	});

	let generation = 0;
	let isSuspended: () => boolean = () => false;

	let draftStableId: string | null = null;
	/**
	 * Post-prefill baseline. A null accountId means it was captured before
	 * accounts were ready; the first snapshot with a matching draft part rebases
	 * it instead of treating the account becoming known as an edit.
	 */
	let baselineAccountId: number | null = null;
	let baselineDraftFp = '';
	/** Persisted content; null draftFp means nothing has been saved yet. */
	let savedAccountId: number | null = null;
	let savedDraftFp: string | null = null;
	let savedAt: Date | null = null;
	let saveError: string | null = null;
	let saving = false;

	let latest: DraftSnapshot | null = null;
	let attachmentReading = false;
	let dirty = false;
	let detached = false;
	let timer: ReturnType<typeof setTimeout> | null = null;
	/** Serializes queue runs; each run coalesces everything requested before it. */
	let tail: Promise<unknown> = Promise.resolve();

	function baselineFingerprint(): string {
		return fingerprintOf(baselineAccountId, baselineDraftFp);
	}

	function savedFingerprint(): string {
		return savedDraftFp == null ? '' : fingerprintOf(savedAccountId, savedDraftFp);
	}

	function publish(): void {
		store.set({
			draftStableId,
			baselineFingerprint: baselineFingerprint(),
			savedFingerprint: savedFingerprint(),
			saving,
			savedAt,
			saveError
		});
	}

	function cancelTimer(): void {
		if (timer == null) return;
		clearTimeout(timer);
		timer = null;
	}

	/** Draft part equal, tolerating a not-yet-known (null) stored account. */
	function matchesDraftPart(
		storedAccountId: number | null,
		storedDraftFp: string | null,
		snapshot: DraftSnapshot
	): boolean {
		if (storedDraftFp == null || storedDraftFp !== draftFingerprint(snapshot.draft)) return false;
		return storedAccountId === null || storedAccountId === snapshot.accountId;
	}

	function equalsBaseline(snapshot: DraftSnapshot): boolean {
		return matchesDraftPart(baselineAccountId, baselineDraftFp, snapshot);
	}

	function equalsSaved(snapshot: DraftSnapshot): boolean {
		return matchesDraftPart(savedAccountId, savedDraftFp, snapshot);
	}

	function scheduleAutosave(): void {
		cancelTimer();
		const delayMs = draftStableId == null ? FIRST_SAVE_DEBOUNCE_MS : STEADY_SAVE_DEBOUNCE_MS;
		timer = setTimeout(() => {
			timer = null;
			if (isSuspended()) {
				// Busy with a send/manual save — retry instead of dropping the edit.
				scheduleAutosave();
				return;
			}
			dirty = true;
			void enqueueRun();
		}, delayMs);
	}

	/** One serialized queue turn: saves the latest snapshot if still dirty. */
	function enqueueRun(): Promise<ComposeFlushOutcome> {
		const run = tail.then(() => runLatest());
		tail = run.catch(() => undefined);
		return run;
	}

	async function runLatest(): Promise<ComposeFlushOutcome> {
		if (!dirty) return 'skipped';
		if (attachmentReading) return 'blocked'; // dirty stays set; settling retries
		dirty = false;
		const snapshot = latest;
		if (!snapshot) return 'skipped';
		// Skip only against *saved* content: an untouched reply (baseline set,
		// nothing saved) must still persist on an explicit flush.
		if (equalsSaved(snapshot)) return 'skipped';
		if (!hasComposeContent(snapshot.draft) && draftStableId == null) return 'skipped';
		return saveOnce(snapshot);
	}

	async function saveOnce(snapshot: DraftSnapshot): Promise<ComposeFlushOutcome> {
		const gen = generation;
		saving = true;
		publish();
		try {
			const request = buildDraftRequest(snapshot.draft);
			const response = await apiSaveDraft(snapshot.accountId, request, draftStableId ?? undefined);
			if (gen !== generation) return 'skipped';
			draftStableId = response.stableId ?? draftStableId;
			savedAccountId = snapshot.accountId;
			savedDraftFp = draftFingerprint(snapshot.draft);
			savedAt = new Date();
			saveError = null;
			return 'saved';
		} catch (err) {
			if (gen !== generation) return 'skipped';
			// No dirty re-arm: the next change, flush or attachment settle retries.
			saveError = toErrorMessage(err);
			return 'failed';
		} finally {
			if (gen === generation) {
				saving = false;
				publish();
			}
		}
	}

	function reset(): void {
		generation += 1;
		cancelTimer();
		isSuspended = () => false;
		draftStableId = null;
		baselineAccountId = null;
		baselineDraftFp = '';
		savedAccountId = null;
		savedDraftFp = null;
		savedAt = null;
		saveError = null;
		saving = false;
		latest = null;
		attachmentReading = false;
		dirty = false;
		detached = false;
		publish();
	}

	return {
		subscribe: store.subscribe,

		begin(options) {
			reset();
			isSuspended = options.isSuspended ?? (() => false);
			draftStableId = options.draftStableId;
			baselineAccountId = options.accountId;
			baselineDraftFp = draftFingerprint(options.draft);
			if (options.draftStableId != null) {
				// An opened draft is already persisted: its baseline IS the saved state.
				savedAccountId = options.accountId;
				savedDraftFp = baselineDraftFp;
			}
			publish();
		},

		noteChange(accountId, draft) {
			if (detached) return;
			if (saveError != null) {
				saveError = null;
				publish();
			}
			// A save needs a target account; the change is picked up once ready.
			if (accountId == null) return;
			const snapshot: DraftSnapshot = { accountId, draft };
			latest = snapshot;
			const atBaseline = equalsBaseline(snapshot);
			const atSaved = equalsSaved(snapshot);
			// Rebase a pre-ready baseline: the account becoming known is not an edit.
			if (atBaseline && baselineAccountId === null) {
				baselineAccountId = accountId;
				publish();
			}
			if (atSaved && savedAccountId === null) {
				savedAccountId = accountId;
				publish();
			}
			if (atBaseline || atSaved) {
				// Unchanged versus a safe state → no autosave (kills zombie drafts).
				cancelTimer();
				return;
			}
			scheduleAutosave();
		},

		setAttachmentReading(reading) {
			attachmentReading = reading;
			if (!reading && dirty && !detached) void enqueueRun();
		},

		async flush() {
			cancelTimer();
			dirty = true;
			// Leave dirty set: once the attachment read settles, the queue retries.
			if (attachmentReading) return 'blocked';
			return enqueueRun();
		},

		async prepareForSend() {
			cancelTimer();
			dirty = false;
			await tail.catch(() => undefined);
			return draftStableId;
		},

		async discard() {
			const gen = generation;
			cancelTimer();
			dirty = false;
			await tail.catch(() => undefined);
			if (gen !== generation) return null;
			const stableId = draftStableId;
			reset();
			if (stableId == null) return null;
			try {
				await deleteMessage(stableId);
			} catch {
				// The async append may not have landed yet — retry once, then give
				// up silently (the next sync surfaces the draft for manual cleanup).
				await delay(DISCARD_RETRY_DELAY_MS);
				try {
					await deleteMessage(stableId);
				} catch {
					return null;
				}
			}
			return stableId;
		},

		end() {
			reset();
		},

		detach() {
			detached = true;
			cancelTimer();
			dirty = false;
		}
	};
}

/** The app-wide compose session (one composer at a time). */
export const composeSession = createComposeSession();
