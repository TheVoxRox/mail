import { get } from 'svelte/store';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { composeSnapshotFingerprint, createComposeSession } from './session.js';
import type { ComposeDraft } from './request.js';

vi.mock('$lib/api/drafts.js', () => ({
	saveDraft: vi.fn()
}));
vi.mock('$lib/api/mailAction.js', () => ({
	deleteMessage: vi.fn()
}));

import { saveDraft } from '$lib/api/drafts.js';
import { deleteMessage } from '$lib/api/mailAction.js';

const saveDraftMock = saveDraft as Mock;
const deleteMessageMock = deleteMessage as Mock;

function makeDraft(overrides: Partial<ComposeDraft> = {}): ComposeDraft {
	return {
		to: 'jana@example.com',
		cc: '',
		bcc: '',
		subject: 'Subject',
		body: 'text',
		attachments: [],
		inReplyTo: null,
		references: null,
		...overrides
	};
}

const emptyDraft: ComposeDraft = makeDraft({ to: '', subject: '', body: '' });

function deferred<T>() {
	let resolve!: (value: T) => void;
	let reject!: (reason?: unknown) => void;
	const promise = new Promise<T>((res, rej) => {
		resolve = res;
		reject = rej;
	});
	return { promise, resolve, reject };
}

/** Lets promise chains queued behind resolved timers/mocks settle. */
function flushMicrotasks(): Promise<void> {
	return vi.advanceTimersByTimeAsync(0) as unknown as Promise<void>;
}

describe('composeSession', () => {
	beforeEach(() => {
		vi.useFakeTimers();
		saveDraftMock.mockReset();
		deleteMessageMock.mockReset();
	});

	afterEach(() => {
		vi.useRealTimers();
	});

	function beginNewCompose(session = createComposeSession()) {
		session.begin({
			accountId: 1,
			draft: emptyDraft,
			draftStableId: null
		});
		return session;
	}

	it('debounces the first save of an unpersisted compose at ~1 s, steady saves at 3 s', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		const session = beginNewCompose();

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(999);
		expect(saveDraftMock).not.toHaveBeenCalled();
		await vi.advanceTimersByTimeAsync(1);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
		expect(saveDraftMock).toHaveBeenLastCalledWith(1, expect.anything(), undefined);
		await flushMicrotasks();
		expect(get(session).draftStableId).toBe('d1');

		saveDraftMock.mockResolvedValue({ stableId: 'd2' });
		session.noteChange(1, makeDraft({ body: 'text v2' }));
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(2000);
		expect(saveDraftMock).toHaveBeenCalledTimes(2);
		// The replaces chain uses the stableId returned by the previous save.
		expect(saveDraftMock).toHaveBeenLastCalledWith(1, expect.anything(), 'd1');
		await flushMicrotasks();
		expect(get(session).draftStableId).toBe('d2');
	});

	it('coalesces changes during an in-flight save into one trailing save of the latest content', async () => {
		const first = deferred<{ stableId: string }>();
		saveDraftMock.mockReturnValueOnce(first.promise).mockResolvedValue({ stableId: 'd2' });
		const session = beginNewCompose();

		session.noteChange(1, makeDraft({ body: 'v1' }));
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);

		// Two edits while the save is in flight → exactly one trailing save.
		session.noteChange(1, makeDraft({ body: 'v2' }));
		session.noteChange(1, makeDraft({ body: 'v3' }));
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);

		first.resolve({ stableId: 'd1' });
		await flushMicrotasks();
		expect(saveDraftMock).toHaveBeenCalledTimes(2);
		expect(saveDraftMock.mock.calls[1][1]).toMatchObject({ body: 'v3' });
		expect(saveDraftMock.mock.calls[1][2]).toBe('d1');
	});

	it('skips the save when the content matches the last saved fingerprint', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		const session = beginNewCompose();
		const draft = makeDraft();

		session.noteChange(1, draft);
		await vi.advanceTimersByTimeAsync(1000);
		await flushMicrotasks();
		expect(saveDraftMock).toHaveBeenCalledTimes(1);

		// Unchanged content → no zombie revision.
		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
	});

	it('cancels a scheduled save when the content reverts to the saved state', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		const session = beginNewCompose();

		session.noteChange(1, makeDraft({ body: 'edit' }));
		await vi.advanceTimersByTimeAsync(500);
		session.noteChange(1, emptyDraft);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).not.toHaveBeenCalled();
	});

	it('never creates a draft for an emptied unpersisted compose, but persists emptying an existing draft', async () => {
		const prefill = makeDraft({ body: 'reply citace' });
		const session = createComposeSession();
		session.begin({ accountId: 1, draft: prefill, draftStableId: null });

		// Prefilled content deleted before anything was saved → nothing to persist.
		session.noteChange(1, emptyDraft);
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).not.toHaveBeenCalled();

		// The same edit on an opened draft is a real revision (replaces chain).
		saveDraftMock.mockResolvedValue({ stableId: 'open-2' });
		session.begin({ accountId: 1, draft: prefill, draftStableId: 'open-1' });
		session.noteChange(1, emptyDraft);
		await vi.advanceTimersByTimeAsync(3000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
		expect(saveDraftMock).toHaveBeenLastCalledWith(1, expect.anything(), 'open-1');
	});

	it('holds the save while an attachment is being read and retries once reading settles', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		const session = beginNewCompose();

		session.setAttachmentReading(true);
		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(5000);
		expect(saveDraftMock).not.toHaveBeenCalled();

		session.setAttachmentReading(false);
		await flushMicrotasks();
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
	});

	it('defers a debounced save while suspended and retries after', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		let busy = true;
		const session = createComposeSession();
		session.begin({
			accountId: 1,
			draft: emptyDraft,
			draftStableId: null,
			isSuspended: () => busy
		});

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).not.toHaveBeenCalled();

		busy = false;
		await vi.advanceTimersByTimeAsync(3000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
	});

	it('surfaces a failed save and retries on the next change', async () => {
		saveDraftMock.mockRejectedValueOnce(new Error('IMAP down'));
		const session = beginNewCompose();

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(1000);
		await flushMicrotasks();
		expect(get(session).saveError).toBeTruthy();
		expect(get(session).draftStableId).toBeNull();

		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		session.noteChange(1, makeDraft({ body: 'second attempt' }));
		expect(get(session).saveError).toBeNull();
		await vi.advanceTimersByTimeAsync(1000);
		await flushMicrotasks();
		expect(get(session).draftStableId).toBe('d1');
	});

	it('flush saves immediately, reports failures and coalesces with an in-flight save', async () => {
		const first = deferred<{ stableId: string }>();
		saveDraftMock.mockReturnValueOnce(first.promise).mockResolvedValue({ stableId: 'd2' });
		const session = beginNewCompose();

		session.noteChange(1, makeDraft({ body: 'v1' }));
		await vi.advanceTimersByTimeAsync(1000);
		session.noteChange(1, makeDraft({ body: 'v2' }));

		const flushPromise = session.flush();
		first.resolve({ stableId: 'd1' });
		await flushMicrotasks();
		await expect(flushPromise).resolves.toBe('saved');
		expect(saveDraftMock).toHaveBeenCalledTimes(2);
		expect(saveDraftMock.mock.calls[1][1]).toMatchObject({ body: 'v2' });

		// Nothing changed since → flush is a clean no-op.
		await expect(session.flush()).resolves.toBe('skipped');
		expect(saveDraftMock).toHaveBeenCalledTimes(2);

		saveDraftMock.mockRejectedValueOnce(new Error('save failed'));
		session.noteChange(1, makeDraft({ body: 'v3' }));
		await expect(session.flush()).resolves.toBe('failed');
		expect(get(session).saveError).toBeTruthy();
	});

	it('flush reports blocked while an attachment is being read', async () => {
		const session = beginNewCompose();
		session.noteChange(1, makeDraft());
		session.setAttachmentReading(true);
		await expect(session.flush()).resolves.toBe('blocked');
		expect(saveDraftMock).not.toHaveBeenCalled();
	});

	it('prepareForSend cancels the pending autosave and returns the settled identity', async () => {
		const inFlight = deferred<{ stableId: string }>();
		saveDraftMock.mockReturnValueOnce(inFlight.promise);
		const session = beginNewCompose();

		// Pending debounce only → cancelled, nothing saved, no identity.
		session.noteChange(1, makeDraft());
		const idle = await session.prepareForSend();
		expect(idle).toBeNull();
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).not.toHaveBeenCalled();

		// In-flight save → prepareForSend waits for the final chain id.
		session.noteChange(1, makeDraft({ body: 'v2' }));
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
		const pending = session.prepareForSend();
		inFlight.resolve({ stableId: 'd1' });
		await flushMicrotasks();
		await expect(pending).resolves.toBe('d1');
	});

	it('discard deletes the settled draft, retrying once when the delete races the append', async () => {
		saveDraftMock.mockResolvedValue({ stableId: 'd1' });
		deleteMessageMock.mockRejectedValueOnce(new Error('404')).mockResolvedValueOnce(undefined);
		const session = beginNewCompose();

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(1000);
		await flushMicrotasks();

		const discardPromise = session.discard();
		await flushMicrotasks();
		expect(deleteMessageMock).toHaveBeenCalledTimes(1);
		await vi.advanceTimersByTimeAsync(1500);
		expect(deleteMessageMock).toHaveBeenCalledTimes(2);
		await expect(discardPromise).resolves.toBe('d1');
		expect(deleteMessageMock).toHaveBeenCalledWith('d1');
		expect(get(session).draftStableId).toBeNull();
	});

	it('discard of a never-persisted compose deletes nothing', async () => {
		const session = beginNewCompose();
		session.noteChange(1, makeDraft());
		await expect(session.discard()).resolves.toBeNull();
		expect(deleteMessageMock).not.toHaveBeenCalled();
		// The pending autosave died with the session.
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).not.toHaveBeenCalled();
	});

	it('rebases the pristine baseline when the account becomes known instead of autosaving', async () => {
		const prefill = makeDraft({ body: 'opened draft' });
		const session = createComposeSession();
		session.begin({ accountId: null, draft: prefill, draftStableId: 'open-1' });

		session.noteChange(2, makeDraft({ body: 'opened draft' }));
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).not.toHaveBeenCalled();
		expect(get(session).savedFingerprint).toBe(
			composeSnapshotFingerprint(2, makeDraft({ body: 'opened draft' }))
		);
	});

	it('detach stops future autosaves but an in-flight save still finishes the chain', async () => {
		const inFlight = deferred<{ stableId: string }>();
		saveDraftMock.mockReturnValueOnce(inFlight.promise);
		const session = beginNewCompose();

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(1000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);

		session.detach();
		inFlight.resolve({ stableId: 'd1' });
		await flushMicrotasks();
		expect(get(session).draftStableId).toBe('d1');

		session.noteChange(1, makeDraft({ body: 'po unmountu' }));
		await vi.advanceTimersByTimeAsync(10_000);
		expect(saveDraftMock).toHaveBeenCalledTimes(1);
	});

	it('end drops the result of a save still in flight', async () => {
		const inFlight = deferred<{ stableId: string }>();
		saveDraftMock.mockReturnValueOnce(inFlight.promise);
		const session = beginNewCompose();

		session.noteChange(1, makeDraft());
		await vi.advanceTimersByTimeAsync(1000);
		session.end();
		inFlight.resolve({ stableId: 'd1' });
		await flushMicrotasks();
		expect(get(session).draftStableId).toBeNull();
		expect(get(session).saving).toBe(false);
	});
});
