import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { loadComposePrefill } from './prefill.js';
import type { ComposeDraft } from './request.js';
import type { NewestDraftSave } from './session.js';

vi.mock('#lib/api/mailRead.js', () => ({
	getMessageDetail: vi.fn(),
	getMessageContent: vi.fn(),
	downloadAttachment: vi.fn()
}));

import { getMessageContent, getMessageDetail } from '#lib/api/mailRead.js';

const getMessageDetailMock = getMessageDetail as Mock;
const getMessageContentMock = getMessageContent as Mock;

function params(query: string): URLSearchParams {
	return new URLSearchParams(query);
}

function session(newest: NewestDraftSave | null) {
	return { newestSaveOf: vi.fn().mockResolvedValue(newest) };
}

describe('loadComposePrefill for ?draft=', () => {
	beforeEach(() => {
		getMessageDetailMock.mockReset();
		getMessageContentMock.mockReset();
	});

	it('opens the server copy of a draft no save of this session has replaced', async () => {
		getMessageDetailMock.mockResolvedValue({
			subject: 'Server subject',
			recipientsTo: 'jana@example.com',
			recipientsCc: '',
			recipientsBcc: '',
			inReplyTo: '',
			references: '',
			attachments: []
		});
		getMessageContentMock.mockResolvedValue({ content: 'Server body' });
		const noNewerSave = session(null);

		const prefill = await loadComposePrefill(params('draft=draft-42'), noNewerSave);

		expect(noNewerSave.newestSaveOf).toHaveBeenCalledWith('draft-42');
		expect(prefill).toMatchObject({
			to: 'jana@example.com',
			subject: 'Server subject',
			body: 'Server body',
			replacesDraftId: 'draft-42',
			fromNewestSave: false,
			accountId: null
		});
	});

	/*
	 * IMAP/SMTP audit B1-26: the Drafts list still shows a revision the composer's
	 * save replaced until that save is stored. Opened again, it showed the old
	 * text, and its save lost the newer one.
	 */
	it("opens the session's newest save of a revision that save replaced, without asking the server", async () => {
		const draft: ComposeDraft = {
			to: 'jana@example.com',
			cc: 'petr@example.com',
			bcc: '',
			subject: 'Edited subject',
			body: 'What the user last wrote',
			attachments: [
				{
					localId: 'a1',
					fileName: 'plan.pdf',
					contentType: 'application/pdf',
					base64Data: 'QUJD',
					size: 3
				}
			],
			inReplyTo: '<parent@example.com>',
			references: '<root@example.com> <parent@example.com>'
		};

		const prefill = await loadComposePrefill(
			params('draft=draft-42'),
			session({ draftStableId: 'saved-later', accountId: 2, draft })
		);

		expect(prefill).toEqual({
			...draft,
			replacesDraftId: 'saved-later',
			fromNewestSave: true,
			accountId: 2
		});
		expect(getMessageDetailMock).not.toHaveBeenCalled();
		expect(getMessageContentMock).not.toHaveBeenCalled();
	});
});
