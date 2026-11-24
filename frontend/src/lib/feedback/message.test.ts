import { describe, expect, it } from 'vitest';
import {
	buildFeedbackMessage,
	FEEDBACK_ADDRESS,
	FEEDBACK_SUMMARY_MAX_LENGTH,
	feedbackErrors,
	feedbackMessageAsText,
	type FeedbackTechnicalInfo,
	type FeedbackTranslate
} from './message.js';

/** Echoes the key and its values, so the tests see what was asked for and with what. */
const t: FeedbackTranslate = (key, options) => {
	const values = options?.values;
	if (!values) return `<${key}>`;
	const args = Object.entries(values)
		.map(([name, value]) => `${name}=${value}`)
		.join(',');
	return `<${key}|${args}>`;
};

const technical: FeedbackTechnicalInfo = {
	clientVersion: '0.2.1',
	backendVersion: '0.2.1-b',
	channel: 'beta',
	languageLabel: 'English'
};

describe('feedbackErrors', () => {
	it('accepts a report with a category, a summary and a description', () => {
		expect(
			feedbackErrors({ category: 'bug', summary: 'Crash', description: 'It crashed.' })
		).toEqual([]);
	});

	it('names every missing field in reading order', () => {
		expect(
			feedbackErrors({ category: null, summary: '  ', description: '\n' }).map((e) => e.field)
		).toEqual(['category', 'summary', 'description']);
	});

	it('measures the summary without the spaces around it', () => {
		const exactly = 'x'.repeat(FEEDBACK_SUMMARY_MAX_LENGTH);
		expect(
			feedbackErrors({ category: 'idea', summary: `  ${exactly}  `, description: 'd' })
		).toEqual([]);
	});

	it('refuses a summary past the limit, saying how long it is', () => {
		const tooLong = 'x'.repeat(FEEDBACK_SUMMARY_MAX_LENGTH + 1);
		expect(feedbackErrors({ category: 'idea', summary: tooLong, description: 'd' })).toEqual([
			{
				field: 'summary',
				messageKey: 'settings.feedback.errors.summaryTooLong',
				values: { max: FEEDBACK_SUMMARY_MAX_LENGTH, length: FEEDBACK_SUMMARY_MAX_LENGTH + 1 }
			}
		]);
	});
});

describe('buildFeedbackMessage', () => {
	it('puts the fixed prefix and the category in front of the summary', () => {
		const { subject } = buildFeedbackMessage(
			{ category: 'bug', summary: '  Moving a message loses it ', description: 'd' },
			technical,
			t
		);
		expect(subject).toBe(
			'[VoxRox Mail] <settings.feedback.message.category.bug>: Moving a message loses it'
		);
	});

	it('ends the description with the technical details as a Markdown list', () => {
		const { body } = buildFeedbackMessage(
			{ category: 'question', summary: 's', description: '\nFirst line\nSecond line\n\n' },
			technical,
			t
		);
		expect(body.split('\n')).toEqual([
			'First line',
			'Second line',
			'',
			'<settings.feedback.message.technical.heading>',
			'- <settings.feedback.message.technical.clientVersion|version=0.2.1>',
			'- <settings.feedback.message.technical.backendVersion|version=0.2.1-b>',
			'- <settings.feedback.message.technical.channel|channel=<settings.feedback.message.technical.channels.beta>>',
			'- <settings.feedback.message.technical.language|language=English>'
		]);
	});

	it('says the backend version is unavailable rather than leaving it blank', () => {
		const { body } = buildFeedbackMessage(
			{ category: 'other', summary: 's', description: 'd' },
			{ ...technical, backendVersion: null },
			t
		);
		expect(body).toContain(
			'- <settings.feedback.message.technical.backendVersion|version=<settings.feedback.message.technical.backendUnavailable>>'
		);
	});
});

describe('feedbackMessageAsText', () => {
	it('heads the copied text with the address and the subject', () => {
		const text = feedbackMessageAsText({ subject: 'Subj', body: 'Body' }, t);
		expect(text.split('\n')).toEqual([
			`<settings.feedback.message.toLine|address=${FEEDBACK_ADDRESS}>`,
			'<settings.feedback.message.subjectLine|subject=Subj>',
			'',
			'Body'
		]);
	});
});
