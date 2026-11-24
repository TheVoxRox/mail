/**
 * The message the "Contact us" form prepares. It goes out from the user's own
 * mail account through the composer, never to an endpoint of ours: VoxRox runs
 * no server for the app, and the user sees what leaves and sends it themselves
 * (docs/DECISIONS.md, "Feedback goes out by the user's own mail").
 *
 * Kept free of Svelte and the DOM so the wording is unit-testable; the caller
 * hands in the translate function.
 */

/** Where reports go: the support and security contact in PRIVACY.md and SECURITY.md. */
export const FEEDBACK_ADDRESS = 'info@voxrox.org';

/**
 * In front of every subject, in both languages, so the maintainer's mailbox can
 * filter reports by it whatever language the user wrote in.
 */
export const FEEDBACK_SUBJECT_PREFIX = '[VoxRox Mail]';

export const FEEDBACK_CATEGORIES = ['bug', 'idea', 'question', 'other'] as const;
export type FeedbackCategory = (typeof FEEDBACK_CATEGORIES)[number];

/** The summary becomes the subject; past this it stops being a summary. */
export const FEEDBACK_SUMMARY_MAX_LENGTH = 120;

export interface FeedbackFields {
	category: FeedbackCategory | null;
	summary: string;
	description: string;
}

export type FeedbackField = keyof FeedbackFields;

export interface FeedbackFieldError {
	field: FeedbackField;
	messageKey: string;
	values?: Record<string, number>;
}

/** Every invalid field, in the form's reading order (the first one gets focus). */
export function feedbackErrors(fields: FeedbackFields): FeedbackFieldError[] {
	const errors: FeedbackFieldError[] = [];
	if (fields.category === null) {
		errors.push({ field: 'category', messageKey: 'settings.feedback.errors.categoryRequired' });
	}
	const summary = fields.summary.trim();
	if (!summary) {
		errors.push({ field: 'summary', messageKey: 'settings.feedback.errors.summaryRequired' });
	} else if (summary.length > FEEDBACK_SUMMARY_MAX_LENGTH) {
		errors.push({
			field: 'summary',
			messageKey: 'settings.feedback.errors.summaryTooLong',
			values: { max: FEEDBACK_SUMMARY_MAX_LENGTH, length: summary.length }
		});
	}
	if (!fields.description.trim()) {
		errors.push({
			field: 'description',
			messageKey: 'settings.feedback.errors.descriptionRequired'
		});
	}
	return errors;
}

/** What the report says about the app, at the end of the body where the user can see and edit it. */
export interface FeedbackTechnicalInfo {
	clientVersion: string;
	/** Null while the backend is not connected. */
	backendVersion: string | null;
	channel: 'stable' | 'beta';
	/** The interface language by its own name, as the language select shows it. */
	languageLabel: string;
}

export type FeedbackTranslate = (
	key: string,
	options?: { values?: Record<string, string | number> }
) => string;

export interface FeedbackMessage {
	subject: string;
	body: string;
}

/**
 * Subject and body of a valid report. The body is Markdown, as everything the
 * composer sends is: the technical lines are a list because plain lines one
 * under another would render as one paragraph.
 */
export function buildFeedbackMessage(
	fields: FeedbackFields & { category: FeedbackCategory },
	technical: FeedbackTechnicalInfo,
	t: FeedbackTranslate
): FeedbackMessage {
	const category = t(`settings.feedback.message.category.${fields.category}`);
	const subject = `${FEEDBACK_SUBJECT_PREFIX} ${category}: ${fields.summary.trim()}`;
	const backendVersion =
		technical.backendVersion ?? t('settings.feedback.message.technical.backendUnavailable');
	const technicalLines = [
		t('settings.feedback.message.technical.clientVersion', {
			values: { version: technical.clientVersion }
		}),
		t('settings.feedback.message.technical.backendVersion', {
			values: { version: backendVersion }
		}),
		t('settings.feedback.message.technical.channel', {
			values: { channel: t(`settings.feedback.message.technical.channels.${technical.channel}`) }
		}),
		t('settings.feedback.message.technical.language', {
			values: { language: technical.languageLabel }
		})
	];
	const body = [
		fields.description.trim(),
		'',
		t('settings.feedback.message.technical.heading'),
		...technicalLines.map((line) => `- ${line}`)
	].join('\n');
	return { subject, body };
}

/** The report as text for the clipboard, for a user who sends it from elsewhere. */
export function feedbackMessageAsText(message: FeedbackMessage, t: FeedbackTranslate): string {
	return [
		t('settings.feedback.message.toLine', { values: { address: FEEDBACK_ADDRESS } }),
		t('settings.feedback.message.subjectLine', { values: { subject: message.subject } }),
		'',
		message.body
	].join('\n');
}
