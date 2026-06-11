/**
 * Shares the message detail heading state between `+layout.svelte` (which
 * watches effectiveReadingPane) and `MessageHeaderCard.svelte` (which
 * renders the subject).
 *
 * - off mode + open message → subject is `<h1>` (folder is a breadcrumb in
 *   the toolbar bar, so the back button in the message header is
 *   redundant).
 * - split mode (right/bottom) or list view → subject is `<h2>` (folder
 *   stays `<h1>` in the main bar) and the back button stays visible.
 */

export interface MessageHeadingContext {
	level: 1 | 2;
	showBackButton: boolean;
}

export const MESSAGE_HEADING_CONTEXT_KEY = Symbol('mail.messageHeading');
