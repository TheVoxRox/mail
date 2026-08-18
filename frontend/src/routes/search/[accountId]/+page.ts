import { requireAccountId } from '$lib/routeParams.js';
import type { PageLoad } from './$types.js';

export const load: PageLoad = ({ params, url }) => {
	const page = Number(url.searchParams.get('page') ?? '0');
	return {
		accountId: requireAccountId(params.accountId),
		query: url.searchParams.get('q') ?? '',
		page: Number.isFinite(page) ? page : 0
	};
};
