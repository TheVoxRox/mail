import type { ContactResponse } from '$lib/types.js';

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
