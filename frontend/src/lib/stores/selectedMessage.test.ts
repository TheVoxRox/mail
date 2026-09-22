// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { get } from 'svelte/store';
import type { MailContentResponse, MailDetailResponse } from '$lib/types.js';

const { getMessageDetailMock, getMessageContentMock, reloadCurrentPageMock } = vi.hoisted(() => ({
	getMessageDetailMock: vi.fn(),
	getMessageContentMock: vi.fn(),
	reloadCurrentPageMock: vi.fn()
}));

vi.mock('$lib/api/mailRead.js', () => ({
	getMessageDetail: getMessageDetailMock,
	getMessageContent: getMessageContentMock
}));
vi.mock('$lib/stores/messages.js', () => ({
	reloadCurrentPage: reloadCurrentPageMock
}));

// Real ApiError so `err instanceof ApiError` in the store matches.
import { ApiError } from '$lib/api/client.js';
import { selectMessage, selectedMessage } from './selectedMessage.js';

function detail(stableId: string): MailDetailResponse {
	return { stableId, subject: 'Subject' } as unknown as MailDetailResponse;
}
function content(): MailContentResponse {
	return { content: '<p>body</p>' } as unknown as MailContentResponse;
}

beforeEach(() => {
	getMessageDetailMock.mockReset();
	getMessageContentMock.mockReset();
	// The body is requested alongside the detail on every path, the failing
	// ones included, so it needs an answer even where a test is not about it.
	getMessageContentMock.mockResolvedValue(content());
	reloadCurrentPageMock.mockReset();
	selectedMessage.set(null);
});

afterEach(() => {
	vi.restoreAllMocks();
});

describe('selectMessage', () => {
	it('loads detail and content on success', async () => {
		getMessageDetailMock.mockResolvedValue(detail('ok-1'));
		getMessageContentMock.mockResolvedValue(content());

		await selectMessage('ok-1');

		const state = get(selectedMessage);
		expect(state?.detail).not.toBeNull();
		expect(state?.content).not.toBeNull();
		expect(state?.loading).toBe(false);
		expect(state?.notFound).toBe(false);
		expect(state?.error).toBeNull();
	});

	it('on a 404 flags notFound and reloads the list (ghost recovery)', async () => {
		getMessageDetailMock.mockRejectedValue(new ApiError(404, 'Not Found', null));

		await selectMessage('ghost-1');

		const state = get(selectedMessage);
		expect(state?.notFound).toBe(true);
		expect(state?.error).toBeNull();
		expect(state?.loading).toBe(false);
		expect(state?.detail).toBeNull();
		expect(reloadCurrentPageMock).toHaveBeenCalledOnce();
	});

	it('on a non-404 error surfaces the error and does not reload', async () => {
		getMessageDetailMock.mockRejectedValue(new ApiError(503, 'Service Unavailable', null));

		await selectMessage('err-1');

		const state = get(selectedMessage);
		expect(state?.error).not.toBeNull();
		expect(state?.notFound).toBe(false);
		expect(reloadCurrentPageMock).not.toHaveBeenCalled();
	});

	it('asks for the body without waiting for the detail', async () => {
		let resolveDetail!: (value: MailDetailResponse) => void;
		getMessageDetailMock.mockReturnValue(new Promise((resolve) => (resolveDetail = resolve)));
		getMessageContentMock.mockResolvedValue(content());

		const selecting = selectMessage('par-1');

		expect(getMessageContentMock).toHaveBeenCalledWith('par-1');
		resolveDetail(detail('par-1'));
		await selecting;
		expect(get(selectedMessage)?.content).not.toBeNull();
	});

	it('holds a body that arrives first until the detail is there', async () => {
		let resolveDetail!: (value: MailDetailResponse) => void;
		getMessageDetailMock.mockReturnValue(new Promise((resolve) => (resolveDetail = resolve)));
		getMessageContentMock.mockResolvedValue(content());

		const selecting = selectMessage('order-1');
		await Promise.resolve();

		expect(get(selectedMessage)?.content).toBeNull();
		resolveDetail(detail('order-1'));
		await selecting;
		const state = get(selectedMessage);
		expect(state?.detail).not.toBeNull();
		expect(state?.content).not.toBeNull();
	});

	it('keeps the header when only the body fails', async () => {
		getMessageDetailMock.mockResolvedValue(detail('body-err-1'));
		getMessageContentMock.mockRejectedValue(new ApiError(502, 'Bad Gateway', null));

		await selectMessage('body-err-1');

		const state = get(selectedMessage);
		expect(state?.detail).not.toBeNull();
		expect(state?.content).toBeNull();
		expect(state?.error).not.toBeNull();
		expect(state?.loading).toBe(false);
	});

	it('reports a detail failure once when the body fails with it', async () => {
		getMessageDetailMock.mockRejectedValue(new ApiError(404, 'Not Found', null));
		getMessageContentMock.mockRejectedValue(new ApiError(404, 'Not Found', null));

		await selectMessage('ghost-2');

		const state = get(selectedMessage);
		expect(state?.notFound).toBe(true);
		expect(state?.error).toBeNull();
		expect(reloadCurrentPageMock).toHaveBeenCalledOnce();
	});
});
