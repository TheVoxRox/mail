/**
 * Shared vCard import pipeline: filter candidate files, parse them and create
 * the contacts via the bulk endpoint, reporting the outcome through toasts.
 * Used by both entry points — the drag-and-drop handler on the contacts page
 * and the file-picker action in the contacts sidebar — so a keyboard or
 * screen-reader user gets the exact same behaviour as a mouse user dropping
 * a file.
 */

import { createContactLabel, listContactLabels } from '$lib/api/contactLabels.js';
import { bulkCreateContacts } from '$lib/api/contacts.js';
import { toErrorMessage } from '$lib/api/errors.js';
import { parseVCard, type ParsedVCard } from '$lib/contacts/vcard.js';
import { refreshContactCounts } from '$lib/stores/contactCounts.js';
import { pushToast } from '$lib/stores/toasts.js';
import type { ContactCreateRequest } from '$lib/types.js';

/**
 * Structurally compatible with `MessageFormatter` from svelte-i18n (`$_` /
 * `get(_)`) — the import outcome messages interpolate created/failed counts.
 */
type ImportMessageFn = (
	id: string,
	options?: { values?: Record<string, string | number> }
) => string;

/**
 * Contacts per bulk request, matching the `@Size(max = 100)` ceiling on
 * `BulkContactCreateRequest`.
 *
 * The whole file used to go into a single call, so anything above the ceiling
 * failed validation as one — and nothing at all was imported. A Google or
 * Outlook address-book export routinely holds more than a hundred cards, which
 * made the import that matters most the one that could not run.
 */
const BULK_CHUNK_SIZE = 100;

/** True when the drag payload contains files (vs. text/link drags). */
export function dragHasFiles(event: DragEvent): boolean {
	const dt = event.dataTransfer;
	if (!dt) return false;
	if (dt.types && Array.from(dt.types).includes('Files')) return true;
	if (dt.files && dt.files.length > 0) return true;
	const items = dt.items;
	if (!items) return false;
	for (let i = 0; i < items.length; i++) {
		if (items[i].kind === 'file') return true;
	}
	return false;
}

export function looksLikeVCardFile(file: File): boolean {
	const type = file.type.toLowerCase();
	if (type === 'text/vcard' || type === 'text/x-vcard') return true;
	return file.name.toLowerCase().endsWith('.vcf');
}

/**
 * Maps every CATEGORIES name in the parsed cards to a label id of the account,
 * creating the ones that do not exist yet. Keyed by lower-cased name, matching
 * the backend's case-insensitive uniqueness.
 * <p>
 * Deliberately fail-soft: label creation is a convenience on top of the import,
 * so a failure here (an account at its label ceiling, a race with another
 * client) warns and returns what it managed to resolve rather than losing the
 * contacts themselves. Contacts always matter more than their labels.
 */
async function resolveCategories(
	parsed: ParsedVCard[],
	t: ImportMessageFn
): Promise<Map<string, number>> {
	const wanted = new Map<string, string>();
	for (const card of parsed) {
		for (const name of card.categories) {
			const key = name.toLowerCase();
			if (!wanted.has(key)) wanted.set(key, name);
		}
	}
	if (wanted.size === 0) return new Map();

	const byName = new Map<string, number>();
	try {
		for (const label of await listContactLabels()) {
			byName.set(label.name.toLowerCase(), label.id);
		}
		for (const [key, name] of wanted) {
			if (byName.has(key)) continue;
			const created = await createContactLabel({ name });
			byName.set(key, created.id);
		}
		// New labels have to reach the sidebar even if the caller only reloads
		// the list; the counts store is what feeds those badges.
		await refreshContactCounts();
	} catch {
		pushToast(t('contacts.vcardImportLabelsFailed'), { tone: 'error' });
	}
	return byName;
}

/**
 * Imports the vCard files among `candidates` into the account and toasts the
 * outcome (including all error cases). Contacts go in batches of
 * {@link BULK_CHUNK_SIZE}, and a batch that fails costs only its own items.
 * Returns true when anything reached the address book — the caller should then
 * reload its contact list.
 */
export async function importVCardFiles(candidates: File[], t: ImportMessageFn): Promise<boolean> {
	const files = candidates.filter(looksLikeVCardFile);
	if (files.length === 0) {
		pushToast(t('contacts.vcardImportNoFiles'), { tone: 'error' });
		return false;
	}

	try {
		const parsed: ParsedVCard[] = [];
		let skipped = 0;
		for (const file of files) {
			const text = await file.text();
			const { cards, skippedWithoutEmail } = parseVCard(text);
			parsed.push(...cards);
			skipped += skippedWithoutEmail;
		}
		if (parsed.length === 0) {
			pushToast(t('contacts.vcardImportEmpty'), { tone: 'error' });
			return false;
		}

		const labelIdsByName = await resolveCategories(parsed, t);
		const allContacts: ContactCreateRequest[] = parsed.map(({ contact, categories }) => ({
			...contact,
			labelIds: categories
				.map((name) => labelIdsByName.get(name.toLowerCase()))
				.filter((id): id is number => id != null)
		}));

		let created = 0;
		let failed = 0;
		let firstErrorMessage: string | null = null;

		for (let start = 0; start < allContacts.length; start += BULK_CHUNK_SIZE) {
			const chunk = allContacts.slice(start, start + BULK_CHUNK_SIZE);
			try {
				const result = await bulkCreateContacts({ contacts: chunk });
				created += result.created ?? 0;
				failed += result.failed ?? 0;
			} catch (err) {
				// One rejected request must not throw away the batches that already
				// landed: its items count as failures, the rest of the file still
				// goes in, and the reason is kept for the report below.
				firstErrorMessage ??= toErrorMessage(err);
				failed += chunk.length;
			}
		}

		if (firstErrorMessage !== null) {
			// Even next to a summary this has to be said: the counts alone would
			// leave the user guessing which half of the file is missing and why.
			pushToast(firstErrorMessage, { tone: 'error' });
			// Nothing was written at all — the plain failure the caller used to
			// get, with no summary to add and nothing to reload.
			if (created === 0) return false;
		}

		pushToast(
			skipped > 0
				? t('contacts.vcardImportDoneSkipped', { values: { created, failed, skipped } })
				: t('contacts.vcardImportDone', { values: { created, failed } }),
			{ tone: failed > 0 ? 'error' : skipped > 0 ? 'info' : 'success' }
		);
		return true;
	} catch (err) {
		pushToast(toErrorMessage(err), { tone: 'error' });
		return false;
	}
}
