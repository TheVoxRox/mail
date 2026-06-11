import { error } from '@sveltejs/kit';
import type { PageLoad } from './$types.js';

export const load: PageLoad = ({ params, url }) => {
	const accountId = Number(params.accountId);
	if (!Number.isInteger(accountId) || accountId <= 0) {
		error(400, 'Invalid account ID.');
	}
	const query = url.searchParams.get('q') ?? '';
	const page = Number(url.searchParams.get('page') ?? '0');
	return { accountId, query, page: Number.isFinite(page) ? page : 0 };
};
