import type { ContactResponse } from '#lib/types.js';

/**
 * Maximum number of e-mail addresses a single contact may hold. The merge
 * preview blocks a merge whose deduplicated union would exceed this; the backend
 * enforces the same cap, so this is a pre-flight guard that stops the user from
 * submitting a request the server would reject.
 */
export const MAX_EMAILS_PER_CONTACT = 10;

export interface MergedEmailEntry {
	email: string;
	primary: boolean;
	/** True when the address comes from the merge target (vs. a merged-in source). */
	fromTarget: boolean;
}

/**
 * Builds the deduplicated e-mail union shown in the merge preview: the target's
 * addresses first (original order and {@link MergedEmailEntry.primary} flag
 * preserved), then every source address not already present. Deduplication is
 * case-insensitive on the address; a source address is never marked primary, so
 * the target keeps the single primary address after the merge.
 *
 * Pure mirror of the union the backend computes — used both to render the
 * preview and to decide whether the merge would exceed
 * {@link MAX_EMAILS_PER_CONTACT}.
 */
export function buildMergePreview(
	target: ContactResponse | null,
	sources: ContactResponse[]
): MergedEmailEntry[] {
	if (!target) return [];
	const seen: string[] = [];
	const out: MergedEmailEntry[] = [];
	for (const e of target.emails) {
		const key = e.email.toLowerCase();
		if (!seen.includes(key)) {
			seen.push(key);
			out.push({ email: e.email, primary: e.primary, fromTarget: true });
		}
	}
	for (const src of sources) {
		for (const e of src.emails) {
			const key = e.email.toLowerCase();
			if (!seen.includes(key)) {
				seen.push(key);
				out.push({ email: e.email, primary: false, fromTarget: false });
			}
		}
	}
	return out;
}

/** Whether a merged preview of {@code count} addresses exceeds the per-contact cap. */
export function exceedsEmailLimit(count: number): boolean {
	return count > MAX_EMAILS_PER_CONTACT;
}

/**
 * Longest note a contact may hold, the `@Size(max = 1000)` of the create and
 * update requests. The backend refuses a merge whose joined notes exceed it
 * (`ContactService.merge`): SQLite does not enforce the column's width, so such
 * a merge used to store the whole note, and the contact could then not be saved
 * again until a note nobody had flagged was shortened.
 */
export const MAX_NOTE_LENGTH = 1000;

/** What the backend puts between two joined notes (`ContactService.NOTE_SEPARATOR`). */
const NOTE_SEPARATOR = '\n\n---\n\n';

/**
 * Length of the note the merge would store: the target's note, then each
 * source's, the blank ones left out, joined by the backend's separator. The
 * same count the backend checks: both measure UTF-16 code units. A pre-flight
 * guard like {@link exceedsEmailLimit}; the backend stays the one that decides.
 */
export function mergedNoteLength(
	target: ContactResponse | null,
	sources: ContactResponse[]
): number {
	if (!target) return 0;
	const notes = [target, ...sources]
		.map((contact) => contact.note)
		.filter((note): note is string => note != null && note.trim() !== '');
	if (notes.length === 0) return 0;
	return notes.join(NOTE_SEPARATOR).length;
}

/** Whether a note of {@code length} characters is longer than a contact may hold. */
export function exceedsNoteLimit(length: number): boolean {
	return length > MAX_NOTE_LENGTH;
}
