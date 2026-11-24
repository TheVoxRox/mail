import { writable } from 'svelte/store';
import type { FeedbackFields } from './message.js';

/**
 * A prepared report on its way from the "Contact us" form to the composer.
 * Neither the body nor the diagnostics fit a URL, so the form stages them here
 * and navigates to `/compose?feedback=<id>`, and the composer takes them once.
 *
 * Taking clears the stage: from then on the composer holds the only copy, and
 * it treats the content as unsaved so the draft autosave and the leave guard
 * protect it (`ComposePrefill.onlyCopy`).
 */
export interface FeedbackHandoff {
	subject: string;
	body: string;
	/** The diagnostic ZIP to attach, as the backend served it. */
	diagnostics: { blob: Blob; fileName: string } | null;
}

let staged: { id: string; handoff: FeedbackHandoff } | null = null;

/** Stages a report, replacing any earlier one, and returns the id the composer URL carries. */
export function stageFeedback(handoff: FeedbackHandoff): string {
	const id = crypto.randomUUID();
	staged = { id, handoff };
	return id;
}

/**
 * The report staged under `id`, once. An id that is not the staged one (a
 * history entry of a report already opened, a reload) yields null and leaves a
 * newer report staged for its own navigation.
 */
export function takeStagedFeedback(id: string): FeedbackHandoff | null {
	if (staged?.id !== id) return null;
	const { handoff } = staged;
	staged = null;
	return handoff;
}

export interface FeedbackFormState extends FeedbackFields {
	includeDiagnostics: boolean;
}

export function emptyFeedbackForm(): FeedbackFormState {
	return { category: null, summary: '', description: '', includeDiagnostics: false };
}

/**
 * What the user has typed into the form, kept while they look elsewhere in the
 * app (to find the message that went wrong, say) and cleared once the report
 * moves to the composer.
 */
export const feedbackForm = writable<FeedbackFormState>(emptyFeedbackForm());
