import { error } from '@sveltejs/kit';
import { get } from 'svelte/store';
import type { ContactSort } from '$lib/api/contacts.js';
import { contactSortPreference } from '$lib/stores/contactSort.js';
import type { EmailLabel } from '$lib/types.js';
import type { PageLoad } from './$types.js';

const SORT_VALUES: ContactSort[] = ['name', 'surname', 'recent'];
const LABEL_VALUES: EmailLabel[] = ['HOME', 'WORK', 'OTHER'];

export const load: PageLoad = ({ params, url }) => {
	const accountId = Number(params.accountId);
	if (!Number.isInteger(accountId) || accountId <= 0) {
		error(400, 'Invalid account ID.');
	}
	const query = url.searchParams.get('q') ?? '';
	const create = url.searchParams.get('create') === '1';
	const editRaw = url.searchParams.get('edit');
	const editId = editRaw != null && /^\d+$/.test(editRaw) ? Number(editRaw) : null;
	const edit = editId != null && editId > 0 ? editId : null;
	const sortRaw = url.searchParams.get('sort');
	const labelRaw = url.searchParams.get('label');
	const urlSort = (SORT_VALUES as string[]).includes(sortRaw ?? '')
		? (sortRaw as ContactSort)
		: null;
	// Sort is a persisted view preference — a clean URL (sidebar view links)
	// falls back to the last applied sort. 'surname' is the default → null.
	const storedSort = get(contactSortPreference);
	const sort = urlSort ?? (storedSort === 'surname' ? null : storedSort);
	const label = (LABEL_VALUES as string[]).includes(labelRaw ?? '')
		? (labelRaw as EmailLabel)
		: null;
	return { accountId, query, create, edit, sort, label };
};
