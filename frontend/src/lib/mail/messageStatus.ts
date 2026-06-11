/**
 * Builds the screen-reader label for a message's status flags (flagged /
 * has attachments / answered). Shared by the inbox grid (MessageList) and
 * the search results grid (SearchResultsGrid) so both announce the status
 * cell identically. Returns `undefined` when no flag is set, so the cell
 * carries no aria-label at all.
 */

import type { MailSummaryResponse } from '$lib/types.js';

/**
 * Structurally compatible with `MessageFormatter` from svelte-i18n (`$_` /
 * `get(_)`). Only the key id is needed — the status labels take no values.
 */
type StatusLabelFn = (id: string) => string;

type MessageFlags = Pick<MailSummaryResponse, 'flagged' | 'hasAttachments' | 'answered'>;

export function messageStatusLabel(message: MessageFlags, t: StatusLabelFn): string | undefined {
	const parts: string[] = [];
	if (message.flagged) parts.push(t('messages.flaggedLabel'));
	if (message.hasAttachments) parts.push(t('messages.attachmentsLabel'));
	if (message.answered) parts.push(t('messages.answeredLabel'));
	return parts.length > 0 ? parts.join(', ') : undefined;
}
