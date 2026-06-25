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
});
