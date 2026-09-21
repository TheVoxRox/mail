import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get, readable } from 'svelte/store';

vi.mock('$lib/api/mailRead.js', () => ({ getMessageDetail: vi.fn(), getMessageContent: vi.fn() }));
vi.mock('$lib/i18n/index.js', () => ({ _: readable((key: string) => key) }));
vi.mock('$lib/stores/toasts.js', () => ({ pushToast: vi.fn(), announcePolite: vi.fn() }));
vi.mock('$lib/stores/selectedMessage.js', async () => {
	const { writable } = await import('svelte/store');
	return { selectedMessage: writable(null) };
});

import {
	finishPrintJob,
	openMessagePrintRefusal,
	printInProgress,
	printJob,
	printMessages,
	printOpenMessage
} from './printMessages.js';
import { getMessageContent, getMessageDetail } from '$lib/api/mailRead.js';
import { selectedMessage, type SelectedMessage } from '$lib/stores/selectedMessage.js';
import { announcePolite, pushToast } from '$lib/stores/toasts.js';
import type { MailContentResponse, MailDetailResponse } from '$lib/types.js';

const content = (id: string): MailContentResponse => ({
	content: `<p>${id}</p>`,
	senderEmail: '',
	remoteImagesAllowedForSender: false
});

describe('printMessages', () => {
	beforeEach(() => {
		vi.clearAllMocks();
		vi.mocked(getMessageDetail).mockImplementation((id) =>
			Promise.resolve({ stableId: id } as MailDetailResponse)
		);
		vi.mocked(getMessageContent).mockImplementation((id) => Promise.resolve(content(id)));
		finishPrintJob();
	});

	it('fetches detail and content of every message and keeps the list order', async () => {
		await printMessages(['c', 'a', 'b', 'a']);
		const job = get(printJob);
		expect(job?.items.map((item) => item.stableId)).toEqual(['c', 'a', 'b']);
		expect(job?.items[0]?.content.content).toBe('<p>c</p>');
		expect(getMessageDetail).toHaveBeenCalledTimes(3);
		expect(getMessageContent).toHaveBeenCalledTimes(3);
		expect(announcePolite).toHaveBeenCalledWith('messages.printPreparing');
		expect(get(printInProgress)).toBe(true);
	});

	it('starts no second job while one is in progress, and says so', async () => {
		await printMessages(['a']);
		const first = get(printJob);
		vi.mocked(announcePolite).mockClear();
		await printMessages(['b']);
		expect(get(printJob)).toBe(first);
		expect(getMessageDetail).toHaveBeenCalledTimes(1);
		expect(announcePolite).toHaveBeenCalledExactlyOnceWith('messages.printAlreadyPreparing');
	});

	it('stops fetching the rest once one message has failed', async () => {
		// The failure lands before any other fetch settles, so every worker sees
		// it before it would take the next id.
		const later = <T>(value: T): Promise<T> =>
			new Promise((resolve) => setTimeout(() => resolve(value), 0));
		vi.mocked(getMessageDetail).mockImplementation((id) =>
			later({ stableId: id } as MailDetailResponse)
		);
		vi.mocked(getMessageContent).mockImplementation((id) =>
			id === 'm1' ? Promise.reject(new Error('gone')) : later(content(id))
		);
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		const ids = Array.from({ length: 12 }, (_, index) => `m${index}`);
		vi.useFakeTimers();
		try {
			const printing = printMessages(ids);
			// Drained, not merely awaited: printMessages returns as soon as the
			// job is cancelled, and a worker that keeps going does so afterwards.
			await vi.runAllTimersAsync();
			await printing;
		} finally {
			vi.useRealTimers();
		}

		expect(get(printJob)).toBeNull();
		// The four ids the workers took before the failure, not all twelve.
		expect(getMessageDetail).toHaveBeenCalledTimes(4);
		warn.mockRestore();
	});

	it('cancels the whole job when one message fails to load, and says so', async () => {
		vi.mocked(getMessageContent).mockImplementation((id) =>
			id === 'b' ? Promise.reject(new Error('gone')) : Promise.resolve(content(id))
		);
		const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
		await printMessages(['a', 'b', 'c']);
		expect(get(printJob)).toBeNull();
		expect(get(printInProgress)).toBe(false);
		expect(pushToast).toHaveBeenCalledWith('messages.printLoadFailed', { tone: 'error' });
		warn.mockRestore();
	});

	it('does nothing for an empty selection', async () => {
		await printMessages([]);
		expect(get(printInProgress)).toBe(false);
		expect(announcePolite).not.toHaveBeenCalled();
	});

	it('finishing drops the sheet and frees the next job', async () => {
		await printMessages(['a']);
		finishPrintJob();
		expect(get(printJob)).toBeNull();
		expect(get(printInProgress)).toBe(false);
	});
});

describe('printing the open message', () => {
	const open = (overrides: Partial<SelectedMessage>): SelectedMessage => ({
		stableId: 'm',
		detail: null,
		content: null,
		loading: false,
		error: null,
		notFound: false,
		...overrides
	});

	beforeEach(() => {
		vi.clearAllMocks();
	});

	it.each([
		{ name: 'its body is there', state: open({ content: content('m') }), refusal: null },
		{
			// The cached copy shown when a refresh failed: the pane shows the mail.
			name: 'its body is there beside a failed refresh',
			state: open({ content: content('m'), error: new Error('refresh') }),
			refusal: null
		},
		{
			name: 'its body is still loading',
			state: open({ loading: true }),
			refusal: 'detail.printStillLoading'
		},
		{
			name: 'its header arrived and the body not yet',
			state: open({ loading: true, detail: { stableId: 'm' } as MailDetailResponse }),
			refusal: 'detail.printStillLoading'
		},
		{
			name: 'it failed to load',
			state: open({ error: new Error('gone') }),
			refusal: 'detail.printUnavailable'
		},
		{
			name: 'it no longer exists',
			state: open({ notFound: true }),
			refusal: 'detail.printUnavailable'
		},
		{ name: 'nothing is open', state: null, refusal: 'detail.nothingToPrint' }
	])('when $name, the refusal is $refusal', ({ state, refusal }) => {
		expect(openMessagePrintRefusal(state)).toBe(refusal);
	});

	it('prints once the body is there, and until then says why not', () => {
		// This suite runs without a DOM; the one call printOpenMessage makes is stubbed.
		const print = vi.fn();
		vi.stubGlobal('window', { print });
		try {
			selectedMessage.set(open({ loading: true }));
			printOpenMessage();
			expect(print).not.toHaveBeenCalled();
			expect(pushToast).toHaveBeenCalledExactlyOnceWith('detail.printStillLoading', {
				tone: 'info'
			});

			selectedMessage.set(open({ content: content('m') }));
			printOpenMessage();
			expect(print).toHaveBeenCalledOnce();
			expect(pushToast).toHaveBeenCalledOnce();
		} finally {
			vi.unstubAllGlobals();
			selectedMessage.set(null);
		}
	});
});
