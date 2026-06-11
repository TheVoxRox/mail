import { error } from '@sveltejs/kit';
import type { ContactSort } from '$lib/api/contacts.js';
import type { EmailLabel } from '$lib/types.js';
import type { PageLoad } from './$types.js';

const SORT_VALUES: ContactSort[] = ['name', 'surname', 'recent'];
const LABEL_VALUES: EmailLabel[] = ['WORK', 'HOME', 'OTHER'];

export const load: PageLoad = ({ params, url }) => {
	const accountId = Number(params.accountId);
	if (!Number.isInteger(accountId) || accountId <= 0) {
		error(400, 'Invalid account ID.');
	}
	const query = url.searchParams.get('q') ?? '';
	const create = url.searchParams.get('create') === '1';
	const sortRaw = url.searchParams.get('sort');
	const labelRaw = url.searchParams.get('label');
	const sort = (SORT_VALUES as string[]).includes(sortRaw ?? '') ? (sortRaw as ContactSort) : null;
	const label = (LABEL_VALUES as string[]).includes(labelRaw ?? '')
		? (labelRaw as EmailLabel)
		: null;
	return { accountId, query, create, sort, label };
};
