import { get } from 'svelte/store';
import type { ContactSort } from '$lib/api/contacts.js';
import { contactSortPreference } from '$lib/stores/contactSort.js';
import type { PageLoad } from './$types.js';

const SORT_VALUES: ContactSort[] = ['name', 'surname', 'recent'];

export const load: PageLoad = ({ url }) => {
	const query = url.searchParams.get('q') ?? '';
	const create = url.searchParams.get('create') === '1';
	const editRaw = url.searchParams.get('edit');
	const editId = editRaw != null && /^\d+$/.test(editRaw) ? Number(editRaw) : null;
	const edit = editId != null && editId > 0 ? editId : null;
	const sortRaw = url.searchParams.get('sort');
	const labelIdRaw = url.searchParams.get('labelId');
	const urlSort = (SORT_VALUES as string[]).includes(sortRaw ?? '')
		? (sortRaw as ContactSort)
		: null;
	// Sort is a persisted view preference — a clean URL (sidebar view links)
	// falls back to the last applied sort. 'surname' is the default → null.
	const storedSort = get(contactSortPreference);
	const sort = urlSort ?? (storedSort === 'surname' ? null : storedSort);
	// Only the shape is validated here — whether the label exists is the
	// backend's call, and it answers 404 rather than an empty list, so a stale
	// bookmark surfaces as an error instead of "this label has no contacts".
	const labelIdParsed = labelIdRaw != null && /^\d+$/.test(labelIdRaw) ? Number(labelIdRaw) : null;
	const labelId = labelIdParsed != null && labelIdParsed > 0 ? labelIdParsed : null;
	return { query, create, edit, sort, labelId };
};
