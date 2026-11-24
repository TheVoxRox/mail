// @vitest-environment jsdom
// The report's diagnostics reach the composer through FileReader, which Node lacks.
import { describe, expect, it } from 'vitest';
import { stageFeedback } from '#lib/feedback/handoff.js';
import { FEEDBACK_ADDRESS } from '#lib/feedback/message.js';
import { loadComposePrefill } from './prefill.js';

function params(query: string): URLSearchParams {
	return new URLSearchParams(query);
}

describe('loadComposePrefill for ?feedback=', () => {
	it('opens the staged report to the support address, as the only copy of its content', async () => {
		const id = stageFeedback({
			subject: '[VoxRox Mail] Bug: Crash',
			body: 'It crashed.',
			diagnostics: {
				blob: new Blob(['ABC'], { type: 'application/zip' }),
				fileName: 'mail-diagnostic.zip'
			}
		});

		const prefill = await loadComposePrefill(params(`feedback=${id}`));

		expect(prefill).toMatchObject({
			to: FEEDBACK_ADDRESS,
			subject: '[VoxRox Mail] Bug: Crash',
			body: 'It crashed.',
			replacesDraftId: null,
			onlyCopy: true,
			attachments: [
				{
					fileName: 'mail-diagnostic.zip',
					contentType: 'application/zip',
					base64Data: 'QUJD',
					size: 3
				}
			]
		});
	});

	it('opens an empty message to the support address once the report has been taken', async () => {
		const id = stageFeedback({ subject: 's', body: 'b', diagnostics: null });
		await loadComposePrefill(params(`feedback=${id}`));

		const again = await loadComposePrefill(params(`feedback=${id}`));

		expect(again).toMatchObject({
			to: FEEDBACK_ADDRESS,
			subject: '',
			body: '',
			attachments: [],
			onlyCopy: false
		});
	});
});
