import { describe, expect, it } from 'vitest';
import { stageFeedback, takeStagedFeedback, type FeedbackHandoff } from './handoff.js';

const report = (subject: string): FeedbackHandoff => ({ subject, body: 'b', diagnostics: null });

describe('feedback handoff', () => {
	it('hands a staged report over once', () => {
		const id = stageFeedback(report('first'));

		expect(takeStagedFeedback(id)?.subject).toBe('first');
		expect(takeStagedFeedback(id)).toBeNull();
	});

	it('leaves a newer report staged when an older id comes back', () => {
		const older = stageFeedback(report('older'));
		const newer = stageFeedback(report('newer'));

		expect(takeStagedFeedback(older)).toBeNull();
		expect(takeStagedFeedback(newer)?.subject).toBe('newer');
	});
});
