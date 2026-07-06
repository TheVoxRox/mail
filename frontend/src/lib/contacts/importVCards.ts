/**
 * Shared vCard import pipeline: filter candidate files, parse them and create
 * the contacts via the bulk endpoint, reporting the outcome through toasts.
 * Used by both entry points — the drag-and-drop handler on the contacts page
 * and the file-picker action in the contacts sidebar — so a keyboard or
 * screen-reader user gets the exact same behaviour as a mouse user dropping
 * a file.
 */

import { bulkCreateContacts } from '$lib/api/contacts.js';
import { toErrorMessage } from '$lib/api/errors.js';
import { parseVCard } from '$lib/contacts/vcard.js';
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
 * Imports the vCard files among `candidates` into the account and toasts the
 * outcome (including all error cases). Returns true when the bulk call
 * succeeded — the caller should then reload its contact list.
 */
export async function importVCardFiles(
	accountId: number,
	candidates: File[],
	t: ImportMessageFn
): Promise<boolean> {
	const files = candidates.filter(looksLikeVCardFile);
	if (files.length === 0) {
		pushToast(t('contacts.vcardImportNoFiles'), { tone: 'error' });
		return false;
	}

	try {
		const allContacts: ContactCreateRequest[] = [];
		for (const file of files) {
			const text = await file.text();
			allContacts.push(...parseVCard(text));
		}
		if (allContacts.length === 0) {
			pushToast(t('contacts.vcardImportEmpty'), { tone: 'error' });
			return false;
		}
		const result = await bulkCreateContacts(accountId, { contacts: allContacts });
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
