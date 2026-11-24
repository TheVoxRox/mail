/**
 * Shared vCard import pipeline: filter candidate files, parse them and create
 * the contacts via the bulk endpoint, reporting the outcome through toasts.
 * Used by both entry points — the drag-and-drop handler on the contacts page
 * and the file-picker action in the contacts sidebar — so a keyboard or
 * screen-reader user gets the exact same behaviour as a mouse user dropping
 * a file.
 */

import { ApiError } from '#lib/api/client.js';
import { createContactLabel, listContactLabels } from '#lib/api/contactLabels.js';
import { bulkCreateContacts } from '#lib/api/contacts.js';
import { toErrorMessage } from '#lib/api/errors.js';
import { parseVCard, type ParsedVCard } from '#lib/contacts/vcard.js';
import { refreshContactCounts } from '#lib/stores/contactCounts.js';
import { pushToast } from '#lib/stores/toasts.js';
import type { BulkContactCreateResponse, ContactCreateRequest } from '#lib/types.js';

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

/**
 * The results of a batch that failed only because the address is already in
 * the address book. The backend counts them among `failed`, but re-importing
 * a file is the ordinary way to meet them, and calling them failures made a
 * harmless re-import read as an error with no reason given.
 */
function alreadyInAddressBook(result: BulkContactCreateResponse): number {
	return (result.results ?? []).filter(
		(item) => item.status === 'FAILED' && item.errorCode === 'CONTACT_DUPLICATE'
	).length;
}

/**
 * The bulk endpoint validates every field of every contact before it writes
 * any, and one that fails refuses the whole request: a duplicate fails only
 * its own item, a field the backend validates fails the batch. The parser
 * leaves out what it can tell is invalid, but not everything the backend
 * bounds (a note past 1000 characters, an eleventh address), so such a batch
 * goes again one contact per request and only the offending ones fail.
 */
function refusedAsInvalid(err: unknown): boolean {
	return err instanceof ApiError && err.problem?.errorCode === 'VALIDATION_ERROR';
}

export function looksLikeVCardFile(file: File): boolean {
	const type = file.type.toLowerCase();
	if (type === 'text/vcard' || type === 'text/x-vcard') return true;
	return file.name.toLowerCase().endsWith('.vcf');
}

/**
 * Maps every CATEGORIES name in the parsed cards to a label id of the address book,
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
 * Imports the vCard files among `candidates` into the address book and toasts the
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
		let invalid = 0;
		for (const file of files) {
			const text = await file.text();
			const { cards, skippedWithoutEmail, invalidAddresses } = parseVCard(text);
			parsed.push(...cards);
			skipped += skippedWithoutEmail;
			invalid += invalidAddresses;
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
		let duplicates = 0;
		let failed = 0;
		let firstErrorMessage: string | null = null;

		const count = (result: BulkContactCreateResponse) => {
			const known = alreadyInAddressBook(result);
			created += result.created ?? 0;
			duplicates += known;
			failed += Math.max(0, (result.failed ?? 0) - known);
		};
		// One rejected request must not throw away the batches that already
		// landed: its items count as failures, the rest of the file still goes
		// in, and the reason is kept for the report below.
		const send = async (contacts: ContactCreateRequest[]) => {
			try {
				count(await bulkCreateContacts({ contacts }));
			} catch (err) {
				if (contacts.length > 1 && refusedAsInvalid(err)) {
					for (const contact of contacts) await send([contact]);
					return;
				}
				firstErrorMessage ??= toErrorMessage(err);
				failed += contacts.length;
			}
		};

		for (let start = 0; start < allContacts.length; start += BULK_CHUNK_SIZE) {
			await send(allContacts.slice(start, start + BULK_CHUNK_SIZE));
		}

		if (firstErrorMessage !== null) {
			// Even next to a summary this has to be said: the counts alone would
			// leave the user guessing which half of the file is missing and why.
			pushToast(firstErrorMessage, { tone: 'error' });
			// Nothing was written at all — the plain failure the caller used to
			// get, with no summary to add and nothing to reload.
			if (created === 0) return false;
		}

		// Only a real failure is an error, which the toast region also says
		// assertively; cards already in the address book, without a valid
		// address or with one left out are what the file held, not something
		// that went wrong.
		pushToast(
			skipped > 0
				? t('contacts.vcardImportDoneSkipped', {
						values: { created, duplicates, failed, invalid, skipped }
					})
				: t('contacts.vcardImportDone', { values: { created, duplicates, failed, invalid } }),
			{
				tone:
					failed > 0 ? 'error' : skipped > 0 || duplicates > 0 || invalid > 0 ? 'info' : 'success'
			}
		);
		return true;
	} catch (err) {
		pushToast(toErrorMessage(err), { tone: 'error' });
		return false;
	}
}
