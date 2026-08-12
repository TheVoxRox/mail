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
 * outcome (including all error cases). Returns true when the bulk call
 * succeeded — the caller should then reload its contact list.
 */
export async function importVCardFiles(candidates: File[], t: ImportMessageFn): Promise<boolean> {
	const files = candidates.filter(looksLikeVCardFile);
	if (files.length === 0) {
		pushToast(t('contacts.vcardImportNoFiles'), { tone: 'error' });
		return false;
	}

	try {
		const parsed: ParsedVCard[] = [];
		for (const file of files) {
			const text = await file.text();
			parsed.push(...parseVCard(text));
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

		const result = await bulkCreateContacts({ contacts: allContacts });
		pushToast(
			t('contacts.vcardImportDone', {
				values: { created: result.created ?? 0, failed: result.failed ?? 0 }
			}),
			{ tone: (result.failed ?? 0) > 0 ? 'error' : 'success' }
		);
		return true;
	} catch (err) {
		pushToast(toErrorMessage(err), { tone: 'error' });
		return false;
	}
}
